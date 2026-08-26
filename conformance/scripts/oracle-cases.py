#!/usr/bin/env python3
"""The oracle's half of the differential: the same cases, through the Rust client.

Every case is driven through the Rust `vector`'s SOCKS5 front end and reported
in the alphabet `oracle-diff.sh` compares — a SOCKS reply code and, where bytes
came back, their SHA-256.

Why a raw SOCKS client rather than curl: curl reports "empty reply from server"
for a rejection and for a truncated response alike, and it never shows the
reply code. The reply code is the whole point — upstream maps the Portal's
SetupResult onto it (`src/vector/flow.rs`, OpenFlowError::socks_reply), so it
is the one place the oracle's view of a rejection is observable at all.

Written against RFC 1928. No third-party dependency, because a conformance
suite that needs a package index is one that stops running.
"""

import hashlib
import socket
import struct
import sys

BLOB_PATH = "/blob.bin"

# RFC 1928 section 6.
REPLY_SUCCEEDED = 0
REPLY_GENERAL_FAILURE = 1

ATYP_IPV4 = 1
ATYP_DOMAIN = 3

COMMAND_CONNECT = 1
COMMAND_UDP_ASSOCIATE = 3


def _negotiate(sock):
    sock.sendall(b"\x05\x01\x00")
    greeting = sock.recv(2)
    if greeting != b"\x05\x00":
        raise OSError("SOCKS5 greeting refused: %r" % (greeting,))


def _address(host, port, as_name):
    if as_name:
        encoded = host.encode("ascii")
        return bytes([ATYP_DOMAIN, len(encoded)]) + encoded + struct.pack(">H", port)
    return bytes([ATYP_IPV4]) + socket.inet_aton(host) + struct.pack(">H", port)


def _read_reply(sock):
    """Reads a SOCKS5 reply and returns its code, consuming the bound address."""
    head = sock.recv(4)
    if len(head) < 4:
        return REPLY_GENERAL_FAILURE, None
    code, atyp = head[1], head[3]
    if atyp == ATYP_IPV4:
        rest = 4 + 2
    elif atyp == ATYP_DOMAIN:
        rest = sock.recv(1)[0] + 2
    else:
        rest = 16 + 2
    bound = b""
    while len(bound) < rest:
        chunk = sock.recv(rest - len(bound))
        if not chunk:
            break
        bound += chunk
    return code, bound


def _command(socks, command, host, port, as_name, timeout):
    sock = socket.create_connection(socks, timeout)
    sock.settimeout(timeout)
    _negotiate(sock)
    sock.sendall(bytes([5, command, 0]) + _address(host, port, as_name))
    code, bound = _read_reply(sock)
    return sock, code, bound


def http_case(socks, host, port, as_name, timeout):
    """Fetches the blob and returns (reply code, sha256 of the body, note)."""
    try:
        sock, code, _ = _command(socks, COMMAND_CONNECT, host, port, as_name, timeout)
    except OSError as error:
        return REPLY_GENERAL_FAILURE, "", "socks: %s" % error
    if code != REPLY_SUCCEEDED:
        sock.close()
        return code, "", "socks reply %d" % code

    try:
        request = (
            "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" % (BLOB_PATH, host)
        ).encode("ascii")
        sock.sendall(request)
        received = b""
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            received += chunk
    except OSError as error:
        return REPLY_GENERAL_FAILURE, "", "read: %s" % error
    finally:
        sock.close()

    separator = received.find(b"\r\n\r\n")
    if separator < 0:
        # The flow opened and then produced nothing usable. Reported as a
        # general failure rather than as an empty payload, because "no answer"
        # and "an answer of zero bytes" are different observations.
        return REPLY_GENERAL_FAILURE, "", "no HTTP response (%d bytes)" % len(received)
    body = received[separator + 4:]
    return REPLY_SUCCEEDED, hashlib.sha256(body).hexdigest(), "%d bytes" % len(body)


def udp_case(socks, host, port, timeout):
    """One datagram out and back through UDP ASSOCIATE."""
    try:
        control, code, bound = _command(socks, COMMAND_UDP_ASSOCIATE, "0.0.0.0", 0, False, timeout)
    except OSError as error:
        return REPLY_GENERAL_FAILURE, "", "associate: %s" % error
    if code != REPLY_SUCCEEDED:
        control.close()
        return code, "", "socks reply %d" % code

    relay_host = socket.inet_ntoa(bound[:4])
    relay_port = struct.unpack(">H", bound[4:6])[0]
    # A relay that names 0.0.0.0 means "the address you already reached me on".
    if relay_host == "0.0.0.0":
        relay_host = socks[0]

    payload = bytes((index * 31 + 7) & 0xFF for index in range(512))
    datagram = b"\x00\x00\x00" + _address(host, port, False) + payload
    try:
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.settimeout(timeout)
        udp.sendto(datagram, (relay_host, relay_port))
        answer, _ = udp.recvfrom(65535)
    except OSError as error:
        return REPLY_GENERAL_FAILURE, "", "datagram: %s" % error
    finally:
        # The control connection must outlive the datagrams; closing it is what
        # ends the association.
        try:
            udp.close()
        except Exception:
            pass
        control.close()

    # Strip the request header the relay echoes back: RSV(2) FRAG(1) ATYP(1) …
    if len(answer) < 10 or answer[3] != ATYP_IPV4:
        return REPLY_GENERAL_FAILURE, "", "unparseable UDP reply (%d bytes)" % len(answer)
    body = answer[10:]
    return REPLY_SUCCEEDED, hashlib.sha256(body).hexdigest(), "%d bytes back" % len(body)


def main():
    if len(sys.argv) != 8:
        print(
            "usage: oracle-cases.py SOCKS SOCKS_WRONG_KEY TARGET TARGET_NAME UDP CLOSED OUT",
            file=sys.stderr,
        )
        return 2

    (
        socks_text,
        wrong_key_socks_text,
        target_text,
        name_text,
        udp_text,
        closed_text,
        out_path,
    ) = sys.argv[1:]

    def endpoint(text):
        host, _, port = text.rpartition(":")
        return host, int(port)

    socks = endpoint(socks_text)
    wrong_key_socks = endpoint(wrong_key_socks_text)
    target_host, target_port = endpoint(target_text)
    name_host, name_port = endpoint(name_text)
    udp_host, udp_port = endpoint(udp_text)
    closed_host, closed_port = endpoint(closed_text)

    # Longer than the oracle's own five-second setup deadline, so a silent
    # Portal is observed as the oracle's answer rather than as our impatience.
    timeout = 30

    verdicts = [
        ("tcp_ip_payload", http_case(socks, target_host, target_port, False, timeout)),
        ("tcp_domain_payload", http_case(socks, name_host, name_port, True, timeout)),
        ("dial_failed", http_case(socks, closed_host, closed_port, False, timeout)),
        ("wrong_key", http_case(wrong_key_socks, target_host, target_port, False, timeout)),
        ("uot_round_trip", udp_case(socks, udp_host, udp_port, timeout)),
    ]

    # "-" rather than an empty column. `read` with a whitespace IFS collapses
    # consecutive tabs, so an empty field shifts every column after it and the
    # comparison silently reads a note as a digest.
    with open(out_path, "w") as handle:
        for case, (code, digest, note) in verdicts:
            handle.write("%s\t%d\t%s\t%s\n" % (case, code, digest or "-", note or "-"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
