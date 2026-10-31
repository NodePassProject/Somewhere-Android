#!/usr/bin/env python3
"""The host-side fixture the QUIC device cases dial through the Portal.

QuicAuthenticationTest, QuicPayloadTest and QuicKeepAliveTest open flows to an
HTTP origin at 127.0.0.1:28091; QuicDatagramTest sends packets to a UDP echo at
127.0.0.1:28092. Both addresses are as the *Portal* sees them: the Portal runs
on this host beside the fixture, so loopback here is right, and the device's
own idea of this host (10.0.2.2 on an emulator) is not.

The origin serves /blob.bin (20 MiB of random bytes, by default) and
/small.bin (64 KiB, for the concurrency case), each with its SHA-256 in
X-Content-Sha256, so a digest computed as the bytes arrive on the
device can be compared with what was sent.

Usage: conformance/scripts/quic-origin.py   (runs until killed)
       BLOB_BYTES=1048576 conformance/scripts/quic-origin.py
"""

import hashlib
import os
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HTTP_PORT = int(os.environ.get("QUIC_ORIGIN_PORT", "28091"))
ECHO_PORT = int(os.environ.get("QUIC_ECHO_PORT", "28092"))
BODIES = {
    "/blob.bin": os.urandom(int(os.environ.get("BLOB_BYTES", str(20 * 1024 * 1024)))),
    "/small.bin": os.urandom(64 * 1024),
}
DIGESTS = {path: hashlib.sha256(body).hexdigest() for path, body in BODIES.items()}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        body = BODIES.get(self.path)
        if body is None:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Sha256", DIGESTS[self.path])
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def echo() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", ECHO_PORT))
    while True:
        data, peer = sock.recvfrom(65535)
        sock.sendto(data, peer)


threading.Thread(target=echo, daemon=True).start()
print(f"serving http://127.0.0.1:{HTTP_PORT}{sorted(BODIES)} and udp echo :{ECHO_PORT}", flush=True)
ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), Handler).serve_forever()
