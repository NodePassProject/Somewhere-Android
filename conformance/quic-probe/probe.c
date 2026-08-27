/*
 * Derived from ngtcp2's `examples/simpleclient.c`, whose copyright and MIT
 * licence follow below and are preserved as that licence requires.
 *
 * What was changed, and why, for `conformance/scripts/quic-probe.sh`:
 *
 *   - the quictls crypto backend became the BoringSSL one, which is the
 *     backend aws-lc speaks;
 *   - libev was replaced with a `poll()` loop, so the probe has no dependency
 *     that is not already on the machine;
 *   - the ALPN is `now/1` and the destination comes from the command line;
 *   - and the stream carries an AuthFrame, a FlowHeader and a Target instead
 *     of a fixed message, so the Portal has something to answer.
 *
 * This file is a spike, not a component. Nothing in the app links against it:
 * it exists to answer D-15 with a measurement, and to keep answering it.
 *
 * SPDX-License-Identifier: MIT
 */

/*
 * ngtcp2
 *
 * Copyright (c) 2021 ngtcp2 contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
#ifdef HAVE_CONFIG_H
#  include <config.h>
#endif /* defined(HAVE_CONFIG_H) */

#include <time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <assert.h>

#include <ngtcp2/ngtcp2.h>
#include <ngtcp2/ngtcp2_crypto.h>
#include <ngtcp2/ngtcp2_crypto_boringssl.h>

#include <openssl/ssl.h>
#include <openssl/rand.h>
#include <openssl/err.h>

#include <poll.h>
#include <openssl/hmac.h>
#include <openssl/sha.h>

/* Filled from argv: this probe is pointed at a Portal from
   conformance/scripts/portal-for-tests.sh and nothing else. */
static const char *remote_host = "127.0.0.1";
static const char *remote_port = "4433";
static const char *shared_key = "conformance-smoke-key";
#define ALPN "\x5now/1"

/*
 * Example 1: Handshake with www.google.com
 *
 * #define remote_host "www.google.com"
 * #define remote_port "443"
 * #define ALPN "\x2h3"
 *
 * and undefine MESSAGE macro.
 */

static uint64_t timestamp(void) {
  struct timespec tp;

  if (clock_gettime(CLOCK_MONOTONIC, &tp) != 0) {
    fprintf(stderr, "clock_gettime: %s\n", strerror(errno));
    exit(EXIT_FAILURE);
  }

  return (uint64_t)tp.tv_sec * NGTCP2_SECONDS + (uint64_t)tp.tv_nsec;
}

static int create_sock(struct sockaddr *addr, socklen_t *paddrlen,
                       const char *host, const char *port) {
  struct addrinfo hints = {0};
  struct addrinfo *res, *rp;
  int rv;
  int fd = -1;

  hints.ai_flags = AF_UNSPEC;
  hints.ai_socktype = SOCK_DGRAM;

  rv = getaddrinfo(host, port, &hints, &res);
  if (rv != 0) {
    fprintf(stderr, "getaddrinfo: %s\n", gai_strerror(rv));
    return -1;
  }

  for (rp = res; rp; rp = rp->ai_next) {
    fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
    if (fd == -1) {
      continue;
    }

    break;
  }

  if (fd == -1) {
    goto end;
  }

  *paddrlen = rp->ai_addrlen;
  memcpy(addr, rp->ai_addr, rp->ai_addrlen);

end:
  freeaddrinfo(res);

  return fd;
}

static int connect_sock(struct sockaddr *local_addr, socklen_t *plocal_addrlen,
                        int fd, const struct sockaddr *remote_addr,
                        size_t remote_addrlen) {
  socklen_t len;

  if (connect(fd, remote_addr, (socklen_t)remote_addrlen) != 0) {
    fprintf(stderr, "connect: %s\n", strerror(errno));
    return -1;
  }

  len = *plocal_addrlen;

  if (getsockname(fd, local_addr, &len) == -1) {
    fprintf(stderr, "getsockname: %s\n", strerror(errno));
    return -1;
  }

  *plocal_addrlen = len;

  return 0;
}

struct client {
  ngtcp2_crypto_conn_ref conn_ref;
  int fd;
  struct sockaddr_storage local_addr;
  socklen_t local_addrlen;
  SSL_CTX *ssl_ctx;
  SSL *ssl;
  ngtcp2_conn *conn;

  struct {
    int64_t stream_id;
    const uint8_t *data;
    size_t datalen;
    size_t nwrite;
  } stream;

  ngtcp2_ccerr last_error;

  int done;
  uint8_t opening[512];
  size_t openinglen;
};

static int numeric_host_family(const char *hostname, int family) {
  uint8_t dst[sizeof(struct in6_addr)];
  return inet_pton(family, hostname, dst) == 1;
}

static int numeric_host(const char *hostname) {
  return numeric_host_family(hostname, AF_INET) ||
         numeric_host_family(hostname, AF_INET6);
}

static int client_ssl_init(struct client *c) {
  c->ssl_ctx = SSL_CTX_new(TLS_client_method());
  if (!c->ssl_ctx) {
    fprintf(stderr, "SSL_CTX_new: %s\n",
            ERR_error_string(ERR_get_error(), NULL));
    return -1;
  }

  if (ngtcp2_crypto_boringssl_configure_client_context(c->ssl_ctx) != 0) {
    fprintf(stderr, "ngtcp2_crypto_boringssl_configure_client_context failed\n");
    return -1;
  }

  c->ssl = SSL_new(c->ssl_ctx);
  if (!c->ssl) {
    fprintf(stderr, "SSL_new: %s\n", ERR_error_string(ERR_get_error(), NULL));
    return -1;
  }

  SSL_set_app_data(c->ssl, &c->conn_ref);
  SSL_set_connect_state(c->ssl);
  SSL_set_alpn_protos(c->ssl, (const unsigned char *)ALPN, sizeof(ALPN) - 1);
  if (!numeric_host(remote_host)) {
    SSL_set_tlsext_host_name(c->ssl, remote_host);
  }

  return 0;
}

static void rand_cb(uint8_t *dest, size_t destlen,
                    const ngtcp2_rand_ctx *rand_ctx) {
  int rv;
  (void)rand_ctx;

  rv = RAND_bytes(dest, (int)destlen);
  if (rv != 1) {
    assert(0);
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

static int extend_max_local_streams_bidi(ngtcp2_conn *conn,
                                         uint64_t max_streams,
                                         void *user_data) {
  struct client *c = user_data;
  int rv;
  int64_t stream_id;
  (void)max_streams;

  if (c->stream.stream_id != -1) {
    return 0;
  }

  rv = ngtcp2_conn_open_bidi_stream(conn, &stream_id, NULL);
  if (rv != 0) {
    return 0;
  }

  c->stream.stream_id = stream_id;
  c->stream.data = c->opening;
  c->stream.datalen = c->openinglen;

  return 0;
}

static void log_printf(void *user_data, const char *fmt, ...) {
  va_list ap;
  (void)user_data;

  va_start(ap, fmt);
  vfprintf(stderr, fmt, ap);
  va_end(ap);

  fprintf(stderr, "\n");
}

/* The Portal's one setup byte, on the logical downlink. Section 6. */
static int recv_stream_data_cb(ngtcp2_conn *conn, uint32_t flags,
                               int64_t stream_id, uint64_t offset,
                               const uint8_t *data, size_t datalen,
                               void *user_data, void *stream_user_data) {
  struct client *c = user_data;
  static const char *names[] = {
    "READY",      "INVALID_REQUEST",  "METADATA_CONFLICT", "PAIR_TIMEOUT",
    "FLOW_LIMIT", "DIAL_FAILED",      "SESSION_REPLACED",  "INTERNAL_ERROR"};
  (void)conn; (void)flags; (void)stream_id; (void)offset; (void)stream_user_data;

  if (datalen > 0) {
    printf("setup_result=0x%02x %s\n", data[0],
           data[0] < 8 ? names[data[0]] : "unknown");
    c->done = 1;
  }
  return 0;
}

static int client_quic_init(struct client *c,
                            const struct sockaddr *remote_addr,
                            socklen_t remote_addrlen,
                            const struct sockaddr *local_addr,
                            socklen_t local_addrlen) {
  ngtcp2_path path = {
    .local =
      {
        .addr = (struct sockaddr *)local_addr,
        .addrlen = local_addrlen,
      },
    .remote =
      {
        .addr = (struct sockaddr *)remote_addr,
        .addrlen = remote_addrlen,
      },
  };
  ngtcp2_callbacks callbacks = {
    .client_initial = ngtcp2_crypto_client_initial_cb,
    .recv_crypto_data = ngtcp2_crypto_recv_crypto_data_cb,
    .encrypt = ngtcp2_crypto_encrypt_cb,
    .decrypt = ngtcp2_crypto_decrypt_cb,
    .hp_mask = ngtcp2_crypto_hp_mask_cb,
    .recv_retry = ngtcp2_crypto_recv_retry_cb,
    .extend_max_local_streams_bidi = extend_max_local_streams_bidi,
    .recv_stream_data = recv_stream_data_cb,
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
  if (RAND_bytes(dcid.data, (int)dcid.datalen) != 1) {
    fprintf(stderr, "RAND_bytes failed\n");
    return -1;
  }

  scid.datalen = 8;
  if (RAND_bytes(scid.data, (int)scid.datalen) != 1) {
    fprintf(stderr, "RAND_bytes failed\n");
    return -1;
  }

  ngtcp2_settings_default(&settings);

  settings.initial_ts = timestamp();
  settings.log_printf = log_printf;

  ngtcp2_transport_params_default(&params);

  params.initial_max_streams_uni = 3;
  params.initial_max_stream_data_bidi_local = 128 * 1024;
  params.initial_max_data = 1024 * 1024;

  rv =
    ngtcp2_conn_client_new(&c->conn, &dcid, &scid, &path, NGTCP2_PROTO_VER_V1,
                           &callbacks, &settings, &params, NULL, c);
  if (rv != 0) {
    fprintf(stderr, "ngtcp2_conn_client_new: %s\n", ngtcp2_strerror(rv));
    return -1;
  }

  ngtcp2_conn_set_tls_native_handle(c->conn, c->ssl);

  return 0;
}

static int client_read(struct client *c) {
  uint8_t buf[65536];
  struct sockaddr_storage addr;
  struct iovec iov = {
    .iov_base = buf,
    .iov_len = sizeof(buf),
  };
  struct msghdr msg = {0};
  ssize_t nread;
  ngtcp2_path path;
  ngtcp2_pkt_info pi = {0};
  int rv;

  msg.msg_name = &addr;
  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;

  for (;;) {
    msg.msg_namelen = sizeof(addr);

    nread = recvmsg(c->fd, &msg, MSG_DONTWAIT);

    if (nread == -1) {
      if (errno != EAGAIN && errno != EWOULDBLOCK) {
        fprintf(stderr, "recvmsg: %s\n", strerror(errno));
      }

      break;
    }

    path.local.addrlen = c->local_addrlen;
    path.local.addr = (struct sockaddr *)&c->local_addr;
    path.remote.addrlen = msg.msg_namelen;
    path.remote.addr = msg.msg_name;

    rv = ngtcp2_conn_read_pkt(c->conn, &path, &pi, buf, (size_t)nread,
                              timestamp());
    if (rv != 0) {
      fprintf(stderr, "ngtcp2_conn_read_pkt: %s\n", ngtcp2_strerror(rv));
      if (!c->last_error.error_code) {
        if (rv == NGTCP2_ERR_CRYPTO) {
          ngtcp2_ccerr_set_tls_alert(
            &c->last_error, ngtcp2_conn_get_tls_alert(c->conn), NULL, 0);
        } else {
          ngtcp2_ccerr_set_liberr(&c->last_error, rv, NULL, 0);
        }
      }
      return -1;
    }
  }

  return 0;
}

static int client_send_packet(struct client *c, const uint8_t *data,
                              size_t datalen) {
  struct iovec iov = {
    .iov_base = (uint8_t *)data,
    .iov_len = datalen,
  };
  struct msghdr msg = {0};
  ssize_t nwrite;

  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;

  do {
    nwrite = sendmsg(c->fd, &msg, 0);
  } while (nwrite == -1 && errno == EINTR);

  if (nwrite == -1) {
    fprintf(stderr, "sendmsg: %s\n", strerror(errno));

    return -1;
  }

  return 0;
}

static size_t client_get_message(struct client *c, int64_t *pstream_id,
                                 int *pfin, ngtcp2_vec *datav,
                                 size_t datavcnt) {
  if (datavcnt == 0) {
    return 0;
  }

  if (c->stream.stream_id != -1 && c->stream.nwrite < c->stream.datalen) {
    *pstream_id = c->stream.stream_id;
    *pfin = 1;
    datav->base = (uint8_t *)c->stream.data + c->stream.nwrite;
    datav->len = c->stream.datalen - c->stream.nwrite;
    return 1;
  }

  *pstream_id = -1;
  *pfin = 0;
  datav->base = NULL;
  datav->len = 0;

  return 0;
}

static int client_write_streams(struct client *c) {
  ngtcp2_tstamp ts = timestamp();
  ngtcp2_pkt_info pi;
  ngtcp2_ssize nwrite;
  uint8_t buf[1452];
  ngtcp2_path_storage ps;
  ngtcp2_vec datav;
  size_t datavcnt;
  int64_t stream_id;
  ngtcp2_ssize wdatalen;
  uint32_t flags;
  int fin;

  ngtcp2_path_storage_zero(&ps);

  for (;;) {
    datavcnt = client_get_message(c, &stream_id, &fin, &datav, 1);

    flags = NGTCP2_WRITE_STREAM_FLAG_MORE;
    if (fin) {
      flags |= NGTCP2_WRITE_STREAM_FLAG_FIN;
    }

    nwrite = ngtcp2_conn_writev_stream(c->conn, &ps.path, &pi, buf, sizeof(buf),
                                       &wdatalen, flags, stream_id, &datav,
                                       datavcnt, ts);
    if (nwrite < 0) {
      switch (nwrite) {
      case NGTCP2_ERR_WRITE_MORE:
        c->stream.nwrite += (size_t)wdatalen;
        continue;
      default:
        fprintf(stderr, "ngtcp2_conn_writev_stream: %s\n",
                ngtcp2_strerror((int)nwrite));
        ngtcp2_ccerr_set_liberr(&c->last_error, (int)nwrite, NULL, 0);
        return -1;
      }
    }

    if (nwrite == 0) {
      return 0;
    }

    if (wdatalen > 0) {
      c->stream.nwrite += (size_t)wdatalen;
    }

    if (client_send_packet(c, buf, (size_t)nwrite) != 0) {
      break;
    }
  }

  return 0;
}

static int client_write(struct client *c) { return client_write_streams(c); }

static int client_handle_expiry(struct client *c) {
  int rv = ngtcp2_conn_handle_expiry(c->conn, timestamp());
  if (rv != 0) {
    fprintf(stderr, "ngtcp2_conn_handle_expiry: %s\n", ngtcp2_strerror(rv));
    return -1;
  }

  return 0;
}

static void client_close(struct client *c) {
  ngtcp2_ssize nwrite;
  ngtcp2_pkt_info pi;
  ngtcp2_path_storage ps;
  uint8_t buf[1280];

  if (ngtcp2_conn_in_closing_period(c->conn) ||
      ngtcp2_conn_in_draining_period(c->conn)) {
    goto fin;
  }

  ngtcp2_path_storage_zero(&ps);

  nwrite = ngtcp2_conn_write_connection_close(
    c->conn, &ps.path, &pi, buf, sizeof(buf), &c->last_error, timestamp());
  if (nwrite < 0) {
    fprintf(stderr, "ngtcp2_conn_write_connection_close: %s\n",
            ngtcp2_strerror((int)nwrite));
    goto fin;
  }

  client_send_packet(c, buf, (size_t)nwrite);

fin:
  c->done = 1;
}

static ngtcp2_conn *get_conn(ngtcp2_crypto_conn_ref *conn_ref) {
  struct client *c = conn_ref->user_data;
  return c->conn;
}

static int client_init(struct client *c) {
  struct sockaddr_storage remote_addr, local_addr;
  socklen_t remote_addrlen, local_addrlen = sizeof(local_addr);

  memset(c, 0, sizeof(*c));

  ngtcp2_ccerr_default(&c->last_error);

  c->fd = create_sock((struct sockaddr *)&remote_addr, &remote_addrlen,
                      remote_host, remote_port);
  if (c->fd == -1) {
    return -1;
  }

  if (connect_sock((struct sockaddr *)&local_addr, &local_addrlen, c->fd,
                   (struct sockaddr *)&remote_addr, remote_addrlen) != 0) {
    return -1;
  }

  memcpy(&c->local_addr, &local_addr, sizeof(c->local_addr));
  c->local_addrlen = local_addrlen;

  if (client_ssl_init(c) != 0) {
    return -1;
  }

  if (client_quic_init(c, (struct sockaddr *)&remote_addr, remote_addrlen,
                       (struct sockaddr *)&local_addr, local_addrlen) != 0) {
    return -1;
  }

  c->stream.stream_id = -1;

  c->conn_ref.get_conn = get_conn;
  c->conn_ref.user_data = c;

  return 0;
}

static void client_free(struct client *c) {
  ngtcp2_conn_del(c->conn);
  SSL_free(c->ssl);
  SSL_CTX_free(c->ssl_ctx);
}

/* --- The Nowhere half ----------------------------------------------------
 *
 * Everything above is ngtcp2's own simpleclient with libev taken out and the
 * BoringSSL crypto backend put in. Everything below is the question C0 exists
 * to answer: does the exporter come out of this stack, and does a real Portal
 * accept the AuthFrame built from it.
 */

static void put_u32(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)(v >> 24);
  p[1] = (uint8_t)(v >> 16);
  p[2] = (uint8_t)(v >> 8);
  p[3] = (uint8_t)v;
}

/* AuthFrame, FlowHeader and Target, in the one write the specification asks
   for. docs/protocol.md sections 2, 4 and 5. */
static int build_opening(struct client *c, const char *target_host,
                         uint16_t target_port) {
  uint8_t exporter[32], salt[SHA256_DIGEST_LENGTH];
  uint8_t auth_root[SHA256_DIGEST_LENGTH], auth_key[SHA256_DIGEST_LENGTH];
  uint8_t session_id[16], tag_input[1 + 32 + 16], tag[SHA256_DIGEST_LENGTH];
  uint8_t info[15];
  unsigned int len = 0;
  const char *root_label = "nowhere/now/1/auth-root";
  size_t n = 0, i;
  struct in_addr addr;

  /* The whole point of the spike. Empty context, and the label is fixed even
     when a custom ALPN is configured. */
  if (SSL_export_keying_material(c->ssl, exporter, sizeof(exporter),
                                 "EXPORTER-Nowhere-Auth", 21, NULL, 0,
                                 1) != 1) {
    fprintf(stderr, "PROBE FAIL: SSL_export_keying_material refused\n");
    return -1;
  }

  printf("exporter=");
  for (i = 0; i < sizeof(exporter); i++) printf("%02x", exporter[i]);
  printf("\n");

  SHA256((const uint8_t *)root_label, strlen(root_label), salt);
  HMAC(EVP_sha256(), salt, sizeof(salt), (const uint8_t *)shared_key,
       strlen(shared_key), auth_root, &len);
  memcpy(info, "authentication", 14);
  info[14] = 0x01;
  HMAC(EVP_sha256(), auth_root, sizeof(auth_root), info, sizeof(info), auth_key,
       &len);

  if (RAND_bytes(session_id, sizeof(session_id)) != 1) {
    fprintf(stderr, "PROBE FAIL: RAND_bytes\n");
    return -1;
  }

  tag_input[0] = 0x02; /* transport = QUIC */
  memcpy(tag_input + 1, exporter, 32);
  memcpy(tag_input + 33, session_id, 16);
  HMAC(EVP_sha256(), auth_key, sizeof(auth_key), tag_input, sizeof(tag_input),
       tag, &len);

  memcpy(c->opening, session_id, 16);
  memcpy(c->opening + 16, tag, 16);
  n = 32;

  /* FlowHeader: DUPLEX, TCP, up=QUIC, down=QUIC, hops 0. */
  c->opening[n++] = 0x18;
  put_u32(c->opening + n, 1);
  n += 4;

  /* Target: an IPv4 literal. */
  c->opening[n++] = 0x01;
  if (inet_pton(AF_INET, target_host, &addr) != 1) {
    fprintf(stderr, "PROBE FAIL: %s is not an IPv4 literal\n", target_host);
    return -1;
  }
  memcpy(c->opening + n, &addr, 4);
  n += 4;
  c->opening[n++] = (uint8_t)(target_port >> 8);
  c->opening[n++] = (uint8_t)target_port;

  c->openinglen = n;
  /* The stream is credited when the peer's transport parameters arrive, which
     is before the handshake completes and therefore before the exporter
     exists. So the payload is attached here, after the fact: without this the
     stream was opened carrying nothing, the AuthFrame never left, and the
     Portal closed the connection on its authentication deadline — which reads
     exactly like a wrong key. */
  c->stream.data = c->opening;
  c->stream.datalen = n;
  return 0;
}

int main(int argc, char **argv) {
  struct client c;
  struct pollfd pfd;
  const char *target_host = "127.0.0.1";
  uint16_t target_port = 80;
  int handshaked = 0;
  uint64_t deadline;

  if (argc >= 3) {
    remote_host = argv[1];
    remote_port = argv[2];
  }
  if (argc >= 4) shared_key = argv[3];
  if (argc >= 6) {
    target_host = argv[4];
    target_port = (uint16_t)atoi(argv[5]);
  }

  srandom((unsigned int)timestamp());

  if (client_init(&c) != 0) exit(EXIT_FAILURE);
  if (client_write(&c) != 0) exit(EXIT_FAILURE);

  pfd.fd = c.fd;
  pfd.events = POLLIN;
  deadline = timestamp() + 15 * NGTCP2_SECONDS;

  while (!c.done && timestamp() < deadline) {
    ngtcp2_tstamp expiry = ngtcp2_conn_get_expiry(c.conn);
    ngtcp2_tstamp now = timestamp();
    int wait_ms = expiry <= now ? 0 : (int)((expiry - now) / NGTCP2_MILLISECONDS);
    int ready;

    if (wait_ms > 50) wait_ms = 50;
    ready = poll(&pfd, 1, wait_ms);
    if (ready > 0 && client_read(&c) != 0) break;
    if (ready == 0 && ngtcp2_conn_handle_expiry(c.conn, timestamp()) != 0) break;

    /* The exporter exists only once the handshake is done, and the opening
       write has to be built before the stream is offered any credit. */
    if (!handshaked && ngtcp2_conn_get_handshake_completed(c.conn)) {
      handshaked = 1;
      printf("handshake=complete\n");
      if (build_opening(&c, target_host, target_port) != 0) break;
    }

    if (client_write(&c) != 0) break;
  }

  if (!handshaked) {
    fprintf(stderr, "PROBE FAIL: the handshake did not complete\n");
    client_free(&c);
    return 1;
  }

  client_free(&c);
  return 0;
}
