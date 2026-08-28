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

// ── ngtcp2 callbacks ────────────────────────────────────────────────────────

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
    ngtcp2_ssize nwrite;
    uint8_t buf[1452];
    jsize capacity;

    if (!c) {
        return err;
    }
    capacity = (*env)->GetArrayLength(env, out);
    if ((size_t)capacity < sizeof(buf)) {
        say(c, "the send buffer is smaller than one datagram");
        return SW_ERR_STATE;
    }

    ngtcp2_path_storage_zero(&ps);
    nwrite = ngtcp2_conn_writev_stream(c->conn, &ps.path, &pi, buf, sizeof(buf),
                                       NULL, NGTCP2_WRITE_STREAM_FLAG_NONE, -1,
                                       NULL, 0, now_ns());
    if (nwrite < 0) {
        say(c, ngtcp2_strerror((int)nwrite));
        ngtcp2_ccerr_set_liberr(&c->last_error, (int)nwrite, NULL, 0);
        return (jint)nwrite;
    }
    if (nwrite == 0) {
        return 0;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)nwrite, (const jbyte *)buf);
    return (jint)nwrite;
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
