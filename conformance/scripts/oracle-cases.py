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

## Two carriers, one case list

Each case runs twice: once through a vector configured `mux=0` and once
through one configured `mux=1`. The second set is prefixed `mux_`. Nothing
about a case changes between the two — that *is* the claim being tested, since
upstream's Mux carrier is a way of moving the same frames and not a different
protocol.

The `burst` cases are the exception, and they are the reason this file grew a
thread pool: how many TLS connections a carrier uses is only observable when
several flows are open **at the same moment**, and a sequence of fetches that
happen to be fast is not that. The origin holds every request until all of
them have arrived, so the overlap is a property of the harness rather than of
how quickly this machine happens to run.
"""

import argparse
import hashlib
import socket
import struct
import sys
import threading

BLOB_PATH = "/blob.bin"
HOLD_PATH = "/hold"

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


def http_case(socks, host, port, as_name, timeout, path=BLOB_PATH):
    """Fetches [path] and returns (reply code, sha256 of the body, note)."""
    try:
        sock, code, _ = _command(socks, COMMAND_CONNECT, host, port, as_name, timeout)
    except OSError as error:
        return REPLY_GENERAL_FAILURE, "", "socks: %s" % error
    if code != REPLY_SUCCEEDED:
        sock.close()
        return code, "", "socks reply %d" % code

    try:
        request = (
            "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" % (path, host)
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


def burst_case(socks, host, port, width, timeout):
    """[width] flows opened at once and held open together.

    The origin at [port] answers nothing until all [width] requests have
    reached it, so every flow is provably open at the same instant. What the
    carrier did with them is counted at the Portal by `oracle-diff.sh`; this
    half's job is only to prove that all [width] flows completed and carried
    the same bytes, because a count of connections means nothing if some of
    the flows it was supposed to carry never happened.
    """
    outcomes = [None] * width

    def one(index):
        outcomes[index] = http_case(socks, host, port, False, timeout, path=HOLD_PATH)

    threads = [threading.Thread(target=one, args=(index,)) for index in range(width)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    failed = [outcome for outcome in outcomes if outcome[0] != REPLY_SUCCEEDED]
    if failed:
        return failed[0][0], "", "%d of %d flows failed: %s" % (
            len(failed),
            width,
            failed[0][2],
        )

    digests = {outcome[1] for outcome in outcomes}
    if len(digests) != 1:
        return REPLY_GENERAL_FAILURE, "", "%d flows returned %d different payloads" % (
            width,
            len(digests),
        )
    return REPLY_SUCCEEDED, digests.pop(), "%d of %d flows held open together" % (width, width)


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


def endpoint(text):
    host, _, port = text.rpartition(":")
    return host, int(port)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    # Named rather than positional: there are twelve endpoints now, and a
    # twelve-deep positional list is a defect waiting for somebody to insert an
    # argument in the middle of it.
    parser.add_argument("--socks", required=True, help="oracle SOCKS listener, mux=0")
    parser.add_argument("--socks-wrong-key", required=True)
    parser.add_argument("--socks-mux", required=True, help="oracle SOCKS listener, mux=1")
    parser.add_argument("--socks-mux-wrong-key", required=True)
    parser.add_argument("--target", required=True, help="blob origin, as an address")
    parser.add_argument("--target-name", required=True, help="the same origin, as a name")
    parser.add_argument("--udp", required=True)
    parser.add_argument("--closed", required=True, help="a port with nothing behind it")
    parser.add_argument("--hold-dedicated", required=True, help="burst origin for mux=0")
    parser.add_argument("--hold-mux", required=True, help="burst origin for mux=1")
    parser.add_argument("--width", type=int, default=16, help="flows opened at once")
    parser.add_argument("--out", required=True)
    options = parser.parse_args()

    socks = endpoint(options.socks)
    wrong_key_socks = endpoint(options.socks_wrong_key)
    mux_socks = endpoint(options.socks_mux)
    mux_wrong_key_socks = endpoint(options.socks_mux_wrong_key)
    target_host, target_port = endpoint(options.target)
    name_host, name_port = endpoint(options.target_name)
    udp_host, udp_port = endpoint(options.udp)
    closed_host, closed_port = endpoint(options.closed)
    hold_dedicated_host, hold_dedicated_port = endpoint(options.hold_dedicated)
    hold_mux_host, hold_mux_port = endpoint(options.hold_mux)

    # Longer than the oracle's own five-second setup deadline, so a silent
    # Portal is observed as the oracle's answer rather than as our impatience.
    timeout = 30

    def case_set(listener, wrong_key_listener, prefix):
        return [
            (prefix + "tcp_ip_payload", http_case(listener, target_host, target_port, False, timeout)),
            (prefix + "tcp_domain_payload", http_case(listener, name_host, name_port, True, timeout)),
            (prefix + "dial_failed", http_case(listener, closed_host, closed_port, False, timeout)),
            (prefix + "wrong_key", http_case(wrong_key_listener, target_host, target_port, False, timeout)),
            (prefix + "uot_round_trip", udp_case(listener, udp_host, udp_port, timeout)),
        ]

    verdicts = case_set(socks, wrong_key_socks, "")
    verdicts += case_set(mux_socks, mux_wrong_key_socks, "mux_")

    # Each burst has an origin of its own, and the port is what labels it. The
    # Portal logs the address a flow was dialled to, so counting connections
    # per origin port attributes every one of them to a carrier and a client
    # without the harness having to reason about when each phase ran.
    verdicts.append(
        (
            "dedicated_burst",
            burst_case(socks, hold_dedicated_host, hold_dedicated_port, options.width, timeout),
        )
    )
    verdicts.append(
        (
            "mux_burst",
            burst_case(mux_socks, hold_mux_host, hold_mux_port, options.width, timeout),
        )
    )

    # "-" rather than an empty column. `read` with a whitespace IFS collapses
    # consecutive tabs, so an empty field shifts every column after it and the
    # comparison silently reads a note as a digest.
    with open(options.out, "w") as handle:
        for case, (code, digest, note) in verdicts:
            handle.write("%s\t%d\t%s\t%s\n" % (case, code, digest or "-", note or "-"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
