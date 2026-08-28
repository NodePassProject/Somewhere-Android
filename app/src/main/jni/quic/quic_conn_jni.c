// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors
//
// A QUIC connection, driven from Kotlin, that cannot reach the network.
//
// ## The rule this file exists to make structural
//
// Inside a VPN every outbound socket must be VpnService.protect()-ed or it
// routes back into the TUN, arrives at lwIP, and is dialled again -- a loop
// that looks like a hang and takes the device with it. This project has
// already shipped the version of that defect where protect() silently returned
// false for every socket, and it refused every flow the moment the tunnel came
// up.
//
// So the socket lives in Kotlin, where DirectDialer's rule already applies, and
// this file has no way to open one. That is not a convention: **there is no
// networking function of any kind in this translation unit.** Addresses arrive
// as raw bytes and are copied into sockaddr structures; not even inet_pton is
// called. checkNativeBridge greps for the syscalls, and the claim it checks is
// one a reader can also check by eye.
//
// ngtcp2 is built for exactly this. It never owns a socket: the caller feeds it
// the datagrams it received and sends the ones it produces. What crosses this
// bridge is three things -- here are received bytes, here are bytes to send,
// here is when to come back -- plus the handshake state and the exporter that
// NW-P-01 authenticates with.
//
// ## One thread
//
// ngtcp2_conn is not thread-safe and this bridge does not make it so. The
// thread that opens a connection owns it, every later call is checked against
// that, and a call from anywhere else is refused rather than being allowed to
// corrupt state that will fail somewhere else much later. lwIP taught this
// project the same lesson at a much higher price: two threads drove a NO_SYS
// stack and the symptom was a device retransmitting SYNs for minutes.
//
// Adapted from the client in conformance/quic-probe/probe.c, which is itself
// derived from ngtcp2's examples/simpleclient.c (MIT). What changed here is
// everything to do with the socket, which is gone, and the lifetime, which is
// now owned by a Kotlin object rather than by main().

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <pthread.h>
#include <netinet/in.h>

#include <ngtcp2/ngtcp2.h>
#include <ngtcp2/ngtcp2_crypto.h>
#include <ngtcp2/ngtcp2_crypto_boringssl.h>

#include <openssl/ssl.h>
#include <openssl/rand.h>
#include <openssl/err.h>

// Refusals that are this bridge's own, kept clear of ngtcp2's error space,
// which is negative and small.
#define SW_ERR_HANDLE (-1000)
#define SW_ERR_WRONG_THREAD (-1001)
#define SW_ERR_STATE (-1002)
// Not an error: the peer has not credited another bidirectional stream yet.
// NW-P-19 says exactly one is credited before authentication, so this is the
// ordinary state of a second flow opened early, and the caller waits rather
// than failing.
#define SW_STREAM_BLOCKED (-1003)

// One bidirectional stream. QUIC gives ordered reliable bytes per stream, so
// each of these is a byte pipe the session layer can treat exactly as it treats
// a dedicated TLS lane -- which is why Transport is an interface over bytes
// rather than over a socket.
struct quic_stream {
    int64_t id;

    // Bytes the caller has handed over and ngtcp2 has not yet acknowledged.
    // `sent` is how far writev has consumed; `acked` is how far the peer has
    // confirmed, and only acknowledged bytes may be dropped -- a retransmit
    // needs the original.
    uint8_t *send;
    size_t send_len, send_cap, sent, acked;
    int fin_requested, fin_written;

    // ngtcp2 has forgotten this stream. Its buffered bytes may still be read by
    // the caller, but its id must never reach writev again: ngtcp2 answers a
    // write to a stream it no longer knows with ERR_STREAM_NOT_FOUND, which
    // reads like a connection failure and takes every other flow with it.
    int gone;

    // Bytes that arrived and the caller has not read.
    uint8_t *recv;
    size_t recv_len, recv_cap, recv_off;
    int recv_fin;

    struct quic_stream *next;
};

struct quic_conn {
    ngtcp2_conn *conn;
    ngtcp2_crypto_conn_ref conn_ref;
    SSL_CTX *ssl_ctx;
    SSL *ssl;

    struct sockaddr_storage local_addr;
    socklen_t local_addrlen;
    struct sockaddr_storage remote_addr;
    socklen_t remote_addrlen;

    ngtcp2_ccerr last_error;
    char message[192];

    struct quic_stream *streams;
    // Which stream writev should try next. Without this a busy first stream
    // starves every other one, because the loop would always start at the head.
    struct quic_stream *next_write;

    pthread_t owner;
};

// Every open increments and every close decrements. A leak test asserts this
// returns to zero, which is a statement about this file's bookkeeping rather
// than about the allocator's high-water mark.
static int live_connections = 0;
static pthread_mutex_t live_lock = PTHREAD_MUTEX_INITIALIZER;

static void live_add(int delta) {
    pthread_mutex_lock(&live_lock);
    live_connections += delta;
    pthread_mutex_unlock(&live_lock);
}

static uint64_t now_ns(void) {
    struct timespec tp;
    if (clock_gettime(CLOCK_MONOTONIC, &tp) != 0) {
        return 0;
    }
    return (uint64_t)tp.tv_sec * NGTCP2_SECONDS + (uint64_t)tp.tv_nsec;
}

static void say(struct quic_conn *c, const char *what) {
    snprintf(c->message, sizeof(c->message), "%s", what);
}

// ── streams ─────────────────────────────────────────────────────────────────

static struct quic_stream *stream_find(struct quic_conn *c, int64_t id) {
    struct quic_stream *s;
    for (s = c->streams; s; s = s->next) {
        if (s->id == id) {
            return s;
        }
    }
    return NULL;
}

static struct quic_stream *stream_add(struct quic_conn *c, int64_t id) {
    struct quic_stream *s = calloc(1, sizeof(*s));
    if (!s) {
        return NULL;
    }
    s->id = id;
    s->next = c->streams;
    c->streams = s;
    return s;
}

static void stream_free_all(struct quic_conn *c) {
    struct quic_stream *s = c->streams, *next;
    while (s) {
        next = s->next;
        free(s->send);
        free(s->recv);
        free(s);
        s = next;
    }
    c->streams = NULL;
    c->next_write = NULL;
}

static int buffer_append(uint8_t **buf, size_t *len, size_t *cap,
                         const uint8_t *data, size_t datalen) {
    if (*len + datalen > *cap) {
        size_t want = *cap ? *cap : 4096;
        uint8_t *grown;
        while (want < *len + datalen) {
            // Doubling, with a ceiling that is not a policy: a stream whose
            // reader never drains it is a bug in the caller, and growing
            // without bound turns that bug into an out-of-memory kill with no
            // diagnostic at all.
            if (want > (16u << 20)) {
                return -1;
            }
            want *= 2;
        }
        grown = realloc(*buf, want);
        if (!grown) {
            return -1;
        }
        *buf = grown;
        *cap = want;
    }
    memcpy(*buf + *len, data, datalen);
    *len += datalen;
    return 0;
}

// ── ngtcp2 callbacks ────────────────────────────────────────────────────────

static int recv_stream_data_cb(ngtcp2_conn *conn, uint32_t flags,
                               int64_t stream_id, uint64_t offset,
                               const uint8_t *data, size_t datalen,
                               void *user_data, void *stream_user_data) {
    struct quic_conn *c = user_data;
    struct quic_stream *s;
    (void)conn;
    (void)offset;
    (void)stream_user_data;

    // A peer-initiated stream is not something this client asked for, and the
    // protocol has no use for one: every stream here is client-initiated. It
    // is recorded rather than refused so that the caller sees the bytes if the
    // specification ever grows a reason for them.
    s = stream_find(c, stream_id);
    if (!s) {
        s = stream_add(c, stream_id);
        if (!s) {
            return NGTCP2_ERR_CALLBACK_FAILURE;
        }
    }

    if (datalen > 0 &&
        buffer_append(&s->recv, &s->recv_len, &s->recv_cap, data, datalen) != 0) {
        say(c, "a stream received more than its reader was ever going to drain");
        return NGTCP2_ERR_CALLBACK_FAILURE;
    }
    if (flags & NGTCP2_STREAM_DATA_FLAG_FIN) {
        s->recv_fin = 1;
    }
    return 0;
}

// Acknowledged bytes may be dropped and no others: an unacknowledged byte can
// still be retransmitted, and ngtcp2 asks for the original when it is.
static int acked_stream_data_offset_cb(ngtcp2_conn *conn, int64_t stream_id,
                                       uint64_t offset, uint64_t datalen,
                                       void *user_data,
                                       void *stream_user_data) {
    struct quic_conn *c = user_data;
    struct quic_stream *s = stream_find(c, stream_id);
    (void)conn;
    (void)offset;
    (void)stream_user_data;

    if (!s) {
        return 0;
    }
    s->acked += (size_t)datalen;
    if (s->acked >= s->send_len) {
        s->send_len = 0;
        s->sent = 0;
        s->acked = 0;
    }
    return 0;
}

static int stream_close_cb(ngtcp2_conn *conn, uint32_t flags, int64_t stream_id,
                           uint64_t app_error_code, void *user_data,
                           void *stream_user_data) {
    struct quic_conn *c = user_data;
    struct quic_stream *s = stream_find(c, stream_id);
    (void)conn;
    (void)flags;
    (void)app_error_code;
    (void)stream_user_data;

    // Not freed here. The caller may still be holding unread bytes, and a
    // close that discarded them would lose the Portal's last word -- which for
    // a rejected flow is the entire message. Marked gone so the write loop
    // stops offering it.
    if (s) {
        s->recv_fin = 1;
        s->gone = 1;
    }
    return 0;
}


static void rand_cb(uint8_t *dest, size_t destlen, const ngtcp2_rand_ctx *ctx) {
    (void)ctx;
    // A failure here would silently weaken the connection ids, so it is fatal
    // rather than ignored. RAND_bytes on aws-lc does not fail short of the
    // process being unable to continue anyway.
    if (RAND_bytes(dest, (int)destlen) != 1) {
        abort();
    }
}

static int get_new_connection_id_cb(ngtcp2_conn *conn, ngtcp2_cid *cid,
                                    uint8_t *token, size_t cidlen,
                                    void *user_data) {
    (void)conn;
    (void)user_data;
    if (RAND_bytes(cid->data, (int)cidlen) != 1) {
        return NGTCP2_ERR_CALLBACK_FAILURE;
    }
    cid->datalen = cidlen;
    if (RAND_bytes(token, NGTCP2_STATELESS_RESET_TOKENLEN) != 1) {
        return NGTCP2_ERR_CALLBACK_FAILURE;
    }
    return 0;
}

static ngtcp2_conn *get_conn(ngtcp2_crypto_conn_ref *conn_ref) {
    return ((struct quic_conn *)conn_ref->user_data)->conn;
}

// ── construction ────────────────────────────────────────────────────────────

static int fill_addr(struct sockaddr_storage *out, socklen_t *outlen,
                     const uint8_t *ip, size_t iplen, int port) {
    memset(out, 0, sizeof(*out));
    if (iplen == 4) {
        struct sockaddr_in *v4 = (struct sockaddr_in *)out;
        v4->sin_family = AF_INET;
        v4->sin_port = htons((uint16_t)port);
        memcpy(&v4->sin_addr, ip, 4);
        *outlen = sizeof(*v4);
        return 0;
    }
    if (iplen == 16) {
        struct sockaddr_in6 *v6 = (struct sockaddr_in6 *)out;
        v6->sin6_family = AF_INET6;
        v6->sin6_port = htons((uint16_t)port);
        memcpy(&v6->sin6_addr, ip, 16);
        *outlen = sizeof(*v6);
        return 0;
    }
    return -1;
}

static int ssl_init(struct quic_conn *c, const char *server_name,
                    const uint8_t *alpn, size_t alpnlen) {
    uint8_t wire[256];

    c->ssl_ctx = SSL_CTX_new(TLS_client_method());
    if (!c->ssl_ctx) {
        say(c, "SSL_CTX_new failed");
        return -1;
    }
    if (ngtcp2_crypto_boringssl_configure_client_context(c->ssl_ctx) != 0) {
        say(c, "the crypto backend refused to configure the context");
        return -1;
    }
    c->ssl = SSL_new(c->ssl_ctx);
    if (!c->ssl) {
        say(c, "SSL_new failed");
        return -1;
    }

    SSL_set_app_data(c->ssl, &c->conn_ref);
    SSL_set_connect_state(c->ssl);

    // ALPN travels as one length-prefixed entry. The value is the caller's:
    // this client embeds no protocol parameter, because a hardcoded ALPN is a
    // deployment decision compiled into every installed copy.
    if (alpnlen == 0 || alpnlen > 255) {
        say(c, "the ALPN must be 1 to 255 bytes");
        return -1;
    }
    wire[0] = (uint8_t)alpnlen;
    memcpy(wire + 1, alpn, alpnlen);
    if (SSL_set_alpn_protos(c->ssl, wire, (unsigned int)alpnlen + 1) != 0) {
        say(c, "SSL_set_alpn_protos refused the value");
        return -1;
    }

    // Only when a name was given. Passing a literal address as SNI is both
    // wrong and a fingerprint; `sni=none` is the documented default and the
    // caller decides.
    if (server_name && server_name[0] != '\0') {
        SSL_set_tlsext_host_name(c->ssl, server_name);
    }
    return 0;
}

static int quic_init(struct quic_conn *c) {
    ngtcp2_path path = {
        .local = {.addr = (struct sockaddr *)&c->local_addr,
                  .addrlen = c->local_addrlen},
        .remote = {.addr = (struct sockaddr *)&c->remote_addr,
                   .addrlen = c->remote_addrlen},
    };
    ngtcp2_callbacks callbacks = {
        .client_initial = ngtcp2_crypto_client_initial_cb,
        .recv_crypto_data = ngtcp2_crypto_recv_crypto_data_cb,
        .encrypt = ngtcp2_crypto_encrypt_cb,
        .decrypt = ngtcp2_crypto_decrypt_cb,
        .hp_mask = ngtcp2_crypto_hp_mask_cb,
        .recv_retry = ngtcp2_crypto_recv_retry_cb,
        .recv_stream_data = recv_stream_data_cb,
        .acked_stream_data_offset = acked_stream_data_offset_cb,
        .stream_close = stream_close_cb,
        .rand = rand_cb,
        .get_new_connection_id = get_new_connection_id_cb,
        .update_key = ngtcp2_crypto_update_key_cb,
        .delete_crypto_aead_ctx = ngtcp2_crypto_delete_crypto_aead_ctx_cb,
        .delete_crypto_cipher_ctx = ngtcp2_crypto_delete_crypto_cipher_ctx_cb,
        .get_path_challenge_data = ngtcp2_crypto_get_path_challenge_data_cb,
        .version_negotiation = ngtcp2_crypto_version_negotiation_cb,
    };
    ngtcp2_cid dcid, scid;
    ngtcp2_settings settings;
    ngtcp2_transport_params params;
    int rv;

    dcid.datalen = NGTCP2_MIN_INITIAL_DCIDLEN;
    scid.datalen = 8;
    if (RAND_bytes(dcid.data, (int)dcid.datalen) != 1 ||
        RAND_bytes(scid.data, (int)scid.datalen) != 1) {
        say(c, "no randomness for the connection ids");
        return -1;
    }

    ngtcp2_settings_default(&settings);
    settings.initial_ts = now_ns();

    ngtcp2_transport_params_default(&params);
    // NW-P-19: only one bidirectional stream is credited before
    // authentication, and unidirectional streams are never used. Advertising
    // none of the latter says so on the wire rather than in a comment.
    params.initial_max_streams_uni = 0;
    params.initial_max_stream_data_bidi_local = 512 * 1024;
    params.initial_max_data = 1024 * 1024;

    rv = ngtcp2_conn_client_new(&c->conn, &dcid, &scid, &path,
                                NGTCP2_PROTO_VER_V1, &callbacks, &settings,
                                &params, NULL, c);
    if (rv != 0) {
        say(c, ngtcp2_strerror(rv));
        return -1;
    }

    ngtcp2_conn_set_tls_native_handle(c->conn, c->ssl);
    return 0;
}

static void conn_free(struct quic_conn *c) {
    if (!c) {
        return;
    }
    stream_free_all(c);
    if (c->conn) {
        ngtcp2_conn_del(c->conn);
    }
    if (c->ssl) {
        SSL_free(c->ssl);
    }
    if (c->ssl_ctx) {
        SSL_CTX_free(c->ssl_ctx);
    }
    free(c);
}

// ── the handle, and the thread that owns it ─────────────────────────────────

static struct quic_conn *checked(jlong handle, int *err) {
    struct quic_conn *c = (struct quic_conn *)(intptr_t)handle;
    if (!c) {
        *err = SW_ERR_HANDLE;
        return NULL;
    }
    if (!pthread_equal(c->owner, pthread_self())) {
        // Refused rather than tolerated. ngtcp2_conn is not thread-safe, and a
        // second thread does not fail here -- it fails later, somewhere else,
        // as something that looks like a network problem.
        say(c, "this connection is being used from a thread that does not own it");
        *err = SW_ERR_WRONG_THREAD;
        return NULL;
    }
    *err = 0;
    return c;
}

// ── JNI ─────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeOpen(
    JNIEnv *env, jclass clazz, jbyteArray local_ip, jint local_port,
    jbyteArray remote_ip, jint remote_port, jstring server_name,
    jbyteArray alpn) {
    (void)clazz;
    struct quic_conn *c;
    jbyte local_buf[16], remote_buf[16], alpn_buf[255];
    jsize local_len, remote_len, alpn_len;
    const char *name = NULL;
    int ok;

    local_len = (*env)->GetArrayLength(env, local_ip);
    remote_len = (*env)->GetArrayLength(env, remote_ip);
    alpn_len = (*env)->GetArrayLength(env, alpn);
    if ((local_len != 4 && local_len != 16) ||
        (remote_len != 4 && remote_len != 16) || alpn_len < 1 ||
        alpn_len > 255) {
        return 0;
    }
    (*env)->GetByteArrayRegion(env, local_ip, 0, local_len, local_buf);
    (*env)->GetByteArrayRegion(env, remote_ip, 0, remote_len, remote_buf);
    (*env)->GetByteArrayRegion(env, alpn, 0, alpn_len, alpn_buf);

    c = calloc(1, sizeof(*c));
    if (!c) {
        return 0;
    }
    c->owner = pthread_self();
    c->conn_ref.get_conn = get_conn;
    c->conn_ref.user_data = c;
    ngtcp2_ccerr_default(&c->last_error);

    if (fill_addr(&c->local_addr, &c->local_addrlen, (uint8_t *)local_buf,
                  (size_t)local_len, local_port) != 0 ||
        fill_addr(&c->remote_addr, &c->remote_addrlen, (uint8_t *)remote_buf,
                  (size_t)remote_len, remote_port) != 0) {
        conn_free(c);
        return 0;
    }

    if (server_name) {
        name = (*env)->GetStringUTFChars(env, server_name, NULL);
    }
    ok = ssl_init(c, name, (uint8_t *)alpn_buf, (size_t)alpn_len) == 0 &&
         quic_init(c) == 0;
    if (name) {
        (*env)->ReleaseStringUTFChars(env, server_name, name);
    }
    if (!ok) {
        conn_free(c);
        return 0;
    }

    live_add(1);
    return (jlong)(intptr_t)c;
}

JNIEXPORT void JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeClose(JNIEnv *env,
                                                           jclass clazz,
                                                           jlong handle) {
    (void)env;
    (void)clazz;
    struct quic_conn *c = (struct quic_conn *)(intptr_t)handle;
    if (!c) {
        return;
    }
    // Deliberately not thread-checked: close has to work from whatever thread
    // is winding the connection down, and by this point nothing else may touch
    // it. The caller guarantees that by dropping its reference first.
    conn_free(c);
    live_add(-1);
}

JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeReceive(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray packet, jint length) {
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    ngtcp2_path path;
    ngtcp2_pkt_info pi = {0};
    jbyte *bytes;
    int rv;

    if (!c) {
        return err;
    }
    if (length <= 0 || length > (*env)->GetArrayLength(env, packet)) {
        say(c, "a datagram of an impossible length");
        return SW_ERR_STATE;
    }

    bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (!bytes) {
        return SW_ERR_STATE;
    }

    path.local.addr = (struct sockaddr *)&c->local_addr;
    path.local.addrlen = c->local_addrlen;
    path.remote.addr = (struct sockaddr *)&c->remote_addr;
    path.remote.addrlen = c->remote_addrlen;

    rv = ngtcp2_conn_read_pkt(c->conn, &path, &pi, (const uint8_t *)bytes,
                              (size_t)length, now_ns());
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);

    if (rv != 0) {
        say(c, ngtcp2_strerror(rv));
        if (!c->last_error.error_code) {
            if (rv == NGTCP2_ERR_CRYPTO) {
                ngtcp2_ccerr_set_tls_alert(
                    &c->last_error, ngtcp2_conn_get_tls_alert(c->conn), NULL, 0);
            } else {
                ngtcp2_ccerr_set_liberr(&c->last_error, rv, NULL, 0);
            }
        }
    }
    return rv;
}

JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeWrite(JNIEnv *env,
                                                            jclass clazz,
                                                            jlong handle,
                                                            jbyteArray out) {
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    ngtcp2_path_storage ps;
    ngtcp2_pkt_info pi;
    ngtcp2_ssize nwrite, wdatalen;
    uint8_t buf[1452];
    jsize capacity;
    struct quic_stream *start, *s;
    int64_t stream_id;
    ngtcp2_vec datav;
    size_t datavcnt;
    uint32_t flags;
    int visited;

    if (!c) {
        return err;
    }
    capacity = (*env)->GetArrayLength(env, out);
    if ((size_t)capacity < sizeof(buf)) {
        say(c, "the send buffer is smaller than one datagram");
        return SW_ERR_STATE;
    }

    // Round-robin from wherever the last call stopped. Starting at the head
    // every time lets one busy stream starve the rest, which on this client
    // would mean a large transfer stalling every other flow on the connection.
    start = c->next_write ? c->next_write : c->streams;
    s = start;
    stream_id = -1;
    datavcnt = 0;
    flags = NGTCP2_WRITE_STREAM_FLAG_NONE;
    visited = 0;

    while (s && visited <= 1) {
        size_t pending = s->send_len > s->sent ? s->send_len - s->sent : 0;
        if (!s->gone && (pending > 0 || (s->fin_requested && !s->fin_written))) {
            stream_id = s->id;
            datav.base = s->send + s->sent;
            datav.len = pending;
            datavcnt = 1;
            if (s->fin_requested && s->sent + pending >= s->send_len) {
                flags |= NGTCP2_WRITE_STREAM_FLAG_FIN;
            }
            break;
        }
        s = s->next ? s->next : c->streams;
        if (s == start) {
            visited++;
        }
    }

    ngtcp2_path_storage_zero(&ps);
    wdatalen = 0;
    nwrite = ngtcp2_conn_writev_stream(c->conn, &ps.path, &pi, buf, sizeof(buf),
                                       &wdatalen, flags, stream_id,
                                       datavcnt ? &datav : NULL, datavcnt,
                                       now_ns());
    if (nwrite < 0) {
        if (nwrite == NGTCP2_ERR_STREAM_DATA_BLOCKED ||
            nwrite == NGTCP2_ERR_STREAM_SHUT_WR) {
            // The peer's credit is exhausted or the half is closed. Neither is
            // an error: there is simply nothing to send for this stream right
            // now, and the caller comes back when a WINDOW-equivalent arrives.
            return 0;
        }
        say(c, ngtcp2_strerror((int)nwrite));
        ngtcp2_ccerr_set_liberr(&c->last_error, (int)nwrite, NULL, 0);
        return (jint)nwrite;
    }

    if (stream_id != -1 && wdatalen > 0) {
        struct quic_stream *written = stream_find(c, stream_id);
        if (written) {
            written->sent += (size_t)wdatalen;
            if ((flags & NGTCP2_WRITE_STREAM_FLAG_FIN) &&
                written->sent >= written->send_len) {
                written->fin_written = 1;
            }
            c->next_write = written->next ? written->next : c->streams;
        }
    }

    if (nwrite == 0) {
        return 0;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)nwrite, (const jbyte *)buf);
    return (jint)nwrite;
}

// Opens one client-initiated bidirectional stream.
//
// Unidirectional streams are never opened -- the specification says they are
// not used, and this client advertises initial_max_streams_uni = 0 so the
// statement is on the wire as well as in the source.
//
// NGTCP2_ERR_STREAM_ID_BLOCKED is not a failure but the observable form of
// NW-P-19: before authentication the peer credits exactly one bidirectional
// stream, so a second one has to wait for the peer to extend the limit rather
// than be opened and stalled.
JNIEXPORT jlong JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeOpenStream(JNIEnv *env,
                                                                 jclass clazz,
                                                                 jlong handle) {
    (void)env;
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    int64_t stream_id;
    int rv;

    if (!c) {
        return err;
    }
    rv = ngtcp2_conn_open_bidi_stream(c->conn, &stream_id, NULL);
    if (rv == NGTCP2_ERR_STREAM_ID_BLOCKED) {
        // Distinguished from every other failure, because it is not one. The
        // caller waits for the peer to extend the limit; the loop keeps
        // pumping in the meantime, which is how the extension arrives.
        return SW_STREAM_BLOCKED;
    }
    if (rv != 0) {
        say(c, ngtcp2_strerror(rv));
        return rv;
    }
    if (!stream_add(c, stream_id)) {
        say(c, "out of memory for a stream");
        return SW_ERR_STATE;
    }
    return (jlong)stream_id;
}

// Queues bytes on a stream. They leave on a later nativeWrite, which is what
// keeps this bridge free of anything that could send.
JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeSend(JNIEnv *env,
                                                           jclass clazz,
                                                           jlong handle,
                                                           jlong stream_id,
                                                           jbyteArray data,
                                                           jint length,
                                                           jboolean fin) {
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    struct quic_stream *s;
    jbyte *bytes;
    int rv = 0;

    if (!c) {
        return err;
    }
    s = stream_find(c, (int64_t)stream_id);
    if (!s) {
        say(c, "no such stream");
        return SW_ERR_STATE;
    }
    if (s->gone) {
        say(c, "this stream has been closed by the peer");
        return SW_ERR_STATE;
    }
    if (s->fin_requested) {
        say(c, "the write half of this stream is already closed");
        return SW_ERR_STATE;
    }
    if (length < 0 || length > (*env)->GetArrayLength(env, data)) {
        say(c, "a write of an impossible length");
        return SW_ERR_STATE;
    }

    if (length > 0) {
        bytes = (*env)->GetByteArrayElements(env, data, NULL);
        if (!bytes) {
            return SW_ERR_STATE;
        }
        if (buffer_append(&s->send, &s->send_len, &s->send_cap,
                          (const uint8_t *)bytes, (size_t)length) != 0) {
            say(c, "the send buffer would not grow");
            rv = SW_ERR_STATE;
        }
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    }
    if (rv == 0 && fin) {
        s->fin_requested = 1;
    }
    return rv;
}

// Drains what arrived on a stream.
//
// Returns the count copied, 0 when nothing has arrived yet, and -1 at a clean
// end of stream with nothing left -- which is the same contract Transport.read
// already has, so the session layer needs no second spelling of "the peer is
// done".
JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeRead(JNIEnv *env,
                                                           jclass clazz,
                                                           jlong handle,
                                                           jlong stream_id,
                                                           jbyteArray out,
                                                           jint offset,
                                                           jint length) {
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    struct quic_stream *s;
    size_t available, take;

    if (!c) {
        return err;
    }
    s = stream_find(c, (int64_t)stream_id);
    if (!s) {
        say(c, "no such stream");
        return SW_ERR_STATE;
    }
    if (offset < 0 || length < 0 ||
        offset + length > (*env)->GetArrayLength(env, out)) {
        say(c, "a read outside the buffer");
        return SW_ERR_STATE;
    }

    available = s->recv_len - s->recv_off;
    if (available == 0) {
        return s->recv_fin ? -1 : 0;
    }
    take = available < (size_t)length ? available : (size_t)length;
    (*env)->SetByteArrayRegion(env, out, offset, (jsize)take,
                               (const jbyte *)(s->recv + s->recv_off));
    s->recv_off += take;
    if (s->recv_off == s->recv_len) {
        s->recv_len = 0;
        s->recv_off = 0;
    }

    // Credit has to be returned or the peer stops sending. L2 shipped the
    // version of this defect where returned credit was truncated into a u16
    // and a transfer stalled short of the end looking like a dead network;
    // here the equivalent mistake is not returning any.
    ngtcp2_conn_extend_max_stream_offset(c->conn, (int64_t)stream_id, take);
    ngtcp2_conn_extend_max_offset(c->conn, take);
    return (jint)take;
}

JNIEXPORT jlong JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeExpiry(JNIEnv *env,
                                                             jclass clazz,
                                                             jlong handle) {
    (void)env;
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    ngtcp2_tstamp expiry;

    if (!c) {
        return -1;
    }
    expiry = ngtcp2_conn_get_expiry(c->conn);
    // UINT64_MAX means "no deadline", which does not survive a signed jlong.
    if (expiry == UINT64_MAX) {
        return -1;
    }
    return (jlong)expiry;
}

JNIEXPORT jlong JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeNow(JNIEnv *env,
                                                          jclass clazz) {
    (void)env;
    (void)clazz;
    // The same clock the expiry is on. A caller comparing an ngtcp2 deadline
    // against System.nanoTime() would be comparing two unrelated origins.
    return (jlong)now_ns();
}

JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeHandleExpiry(JNIEnv *env,
                                                                   jclass clazz,
                                                                   jlong handle) {
    (void)env;
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    int rv;

    if (!c) {
        return err;
    }
    rv = ngtcp2_conn_handle_expiry(c->conn, now_ns());
    if (rv != 0) {
        say(c, ngtcp2_strerror(rv));
    }
    return rv;
}

// Tri-state rather than boolean, deliberately. A wrong-thread call answering
// "false" would be indistinguishable from a handshake that has not finished,
// and a caller would loop on it until its deadline reporting a peer problem
// that is really a threading one.
JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeHandshakeCompleted(
    JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    if (!c) {
        return err;
    }
    return ngtcp2_conn_get_handshake_completed(c->conn) ? 1 : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeExportKeyingMaterial(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray label, jint length) {
    (void)clazz;
    int err;
    struct quic_conn *c = checked(handle, &err);
    uint8_t out[64];
    jbyte label_buf[128];
    jsize label_len;
    jbyteArray result;

    if (!c) {
        return NULL;
    }
    if (length <= 0 || (size_t)length > sizeof(out)) {
        say(c, "an exporter length this bridge does not serve");
        return NULL;
    }
    label_len = (*env)->GetArrayLength(env, label);
    if (label_len <= 0 || (size_t)label_len > sizeof(label_buf)) {
        say(c, "an exporter label of an impossible length");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, label, 0, label_len, label_buf);

    // Empty context, and `use_context` is 1: RFC 5705 distinguishes "no
    // context" from "an empty context" and they derive different bytes. This
    // is the shape the specification's own vectors were computed with, and
    // getting it wrong produces 32 plausible bytes that no Portal accepts.
    if (SSL_export_keying_material(c->ssl, out, (size_t)length,
                                   (const char *)label_buf, (size_t)label_len,
                                   NULL, 0, 1) != 1) {
        say(c, "the TLS backend refused to export keying material");
        return NULL;
    }

    result = (*env)->NewByteArray(env, length);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)out);
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeLastMessage(JNIEnv *env,
                                                                  jclass clazz,
                                                                  jlong handle) {
    (void)clazz;
    struct quic_conn *c = (struct quic_conn *)(intptr_t)handle;
    if (!c || c->message[0] == '\0') {
        return NULL;
    }
    return (*env)->NewStringUTF(env, c->message);
}

JNIEXPORT jint JNICALL
Java_eu_nodepass_somewhere_quic_QuicConnection_nativeLiveConnections(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    int n;
    pthread_mutex_lock(&live_lock);
    n = live_connections;
    pthread_mutex_unlock(&live_lock);
    return n;
}
