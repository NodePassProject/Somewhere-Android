#!/usr/bin/env python3
"""Independently recompute every fixed vector in protocol-vectors.json.

This script references no Nowhere code. It re-implements the encodings from the
prose in docs/protocol.md (nw3) and compares against the values transcribed into
the JSON. A pass means three sources agree: the spec document, this script, and
the upstream Rust tests the values were transcribed from.

It uses the standard library only, ChaCha20 included, so that it runs anywhere
Python does and so that its ChaCha20 is a third implementation rather than a
dependency shared with whatever produced the fixture.

Purpose:
  - CI gate: fails immediately if the fixtures are edited by mistake or the
    upstream expectations change;
  - an executable explanation of the protocol for anyone new to it.

Usage: python3 scripts/verify-vectors.py [vectors/protocol-vectors.json]
Exit code 0 means everything passed.
"""
import hashlib
import hmac
import json
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT = ROOT / "vectors" / "protocol-vectors.json"

failures: list[str] = []
checks = 0


def check(name: str, got, want) -> None:
    global checks
    checks += 1
    if got != want:
        failures.append(f"{name}\n    got  {got}\n    want {want}")


def hmac256(key: bytes, msg: bytes) -> bytes:
    return hmac.new(key, msg, hashlib.sha256).digest()


# --- Authentication -----------------------------------------------------------

ROLE_INFO = {"vector": b"vector-authentication", "egress": b"egress-authentication"}
TRANSPORT = {"tlsTcp": 0x01, "quic": 0x02}


def role_key(shared_key: bytes, role: str) -> bytes:
    salt = hashlib.sha256(b"nowhere/nw3/auth-root").digest()
    root = hmac256(salt, shared_key)                 # HKDF-Extract(salt, IKM)
    return hmac256(root, ROLE_INFO[role] + b"\x01")  # HKDF-Expand, one block


def auth_frame(key: bytes, transport: int, exporter: bytes) -> bytes:
    # The whole AuthFrame is the tag: no session id, no role byte.
    return hmac256(key, bytes([transport]) + exporter)[:16]


def is_service_key(text: str) -> bool:
    return len(text) == 32 and all(c in "0123456789abcdef" for c in text)


def verify_auth(section: dict) -> None:
    key = section["sharedKeyUtf8"].encode()
    exporter = bytes.fromhex(section["exporterHex"])
    check("auth / exporter label", section["derivation"]["exporterLabel"], "EXPORTER-Nowhere-nw3-Auth")
    check("auth / salt", hashlib.sha256(b"nowhere/nw3/auth-root").hexdigest(), section["saltHex"])
    check("auth / root", hmac256(hashlib.sha256(b"nowhere/nw3/auth-root").digest(), key).hex(), section["rootHex"])
    check("auth / the vectors' key is in service form", is_service_key(section["sharedKeyUtf8"]), True)
    for case in section["cases"]:
        rk = role_key(key, case["role"])
        check(f"auth / {case['name']} role key", rk.hex(), case["expectedRoleKeyHex"])
        frame = auth_frame(rk, TRANSPORT[case["transport"]], exporter)
        check(f"auth / {case['name']}", frame.hex(), case["expectedFrameHex"])
        check(f"auth / {case['name']} length", len(frame), 16)
    for reject in section["rejects"]:
        if "sharedKeyUtf8" in reject:
            check(f"auth reject / {reject['name']}", is_service_key(reject["sharedKeyUtf8"]), False)


# --- FlowStart -----------------------------------------------------------------

KINDS = {"TCP": 0, "UDP": 1}


def ip_target(addr: str, port: int) -> bytes:
    import ipaddress

    ip = ipaddress.ip_address(addr)
    return bytes([0x01 if ip.version == 4 else 0x04]) + ip.packed + port.to_bytes(2, "big")


def verify_flow_start(section: dict) -> None:
    for case in section["cases"]:
        name = case["name"]
        if "decodeHex" in case:
            # Bits 7..1 are reserved: sent as zero, ignored on receipt.
            kind = bytes.fromhex(case["decodeHex"])[0] & 0x01
            check(f"flowStart / {name}", kind, KINDS[case["expectedKind"]])
            continue
        encoded = bytes([KINDS[case["kind"]]])
        if "target" in case:
            host, port = case["target"].rsplit(":", 1)
            encoded += ip_target(host, int(port))
        check(f"flowStart / {name}", encoded.hex(), case["expectedHex"])


# --- Target (unchanged since v1) ----------------------------------------------

def verify_target(section: dict) -> None:
    def domain_target(name: str, port: int) -> bytes:
        raw = name.encode("ascii")
        return bytes([0x03, len(raw)]) + raw + port.to_bytes(2, "big")

    built = {
        "IPv4 192.0.2.1:443": ip_target("192.0.2.1", 443),
        "IPv6 [2001:db8::1]:53": ip_target("2001:db8::1", 53),
        "domain xn--bcher-kva.example:8080 (IDNA)": domain_target("xn--bcher-kva.example", 8080),
    }
    for case in section["cases"]:
        encoded = built[case["name"]]
        check(f"target / {case['name']}", encoded.hex(), case["expectedHex"])
        check(f"target / {case['name']} length", len(encoded), case["totalLen"])


# --- SetupResult ---------------------------------------------------------------

def verify_setup_result(section: dict) -> None:
    expected = ["READY", "INVALID_REQUEST", "FLOW_LIMIT", "DIAL_FAILED", "INTERNAL_ERROR"]
    check("setupResult / number of values", len(section["cases"]), 5)
    for case in section["cases"]:
        check(f"setupResult / byte {case['byte']}", case["name"], expected[case["byte"]])
    for reject in section["rejects"]:
        for value in reject["bytes"]:
            check(f"setupResult / {value} is outside the table", value < len(expected), False)


# --- UDP over TLS (UoT, unchanged since v1) ------------------------------------

def verify_uot(section: dict) -> None:
    for case in section["cases"]:
        if "payloadHex" in case or "payloadUtf8" in case:
            payload = (bytes.fromhex(case["payloadHex"]) if "payloadHex" in case
                       else case["payloadUtf8"].encode())
            got = len(payload).to_bytes(2, "big") + payload
            check(f"uot / {case['name']}", got.hex(), case["expectedHex"])
        elif "declaredLen" in case:
            got = case["declaredLen"].to_bytes(2, "big")
            check(f"uot / {case['name']}", got.hex(), case["expectedHeaderHex"])


# --- QUIC datagrams ------------------------------------------------------------

MAX_FLOW_ID = (1 << 30) - 1


def base_word(frame_type: int, flow_id: int) -> bytes:
    assert 1 <= flow_id <= MAX_FLOW_ID
    return ((frame_type << 30) | flow_id).to_bytes(4, "big")


def decode_base(raw: bytes):
    """(type, flow_id) or None for anything the specification says to drop."""
    if len(raw) < 4:
        return None
    word = int.from_bytes(raw[:4], "big")
    frame_type, flow_id = word >> 30, word & MAX_FLOW_ID
    if frame_type == 3 or flow_id == 0:
        return None
    if frame_type == 2 and len(raw) != 4:
        return None
    return frame_type, flow_id


def verify_datagram(section: dict) -> None:
    for case in section["cases"]:
        name = case["name"]
        if "fragIndex" in case:
            got = (base_word(1, case["flowId"])
                   + case["packetId"].to_bytes(4, "big")
                   + bytes([case["fragIndex"], case["fragCount"]])
                   + case["totalLen"].to_bytes(2, "big"))
            check(f"datagram / {name}", got.hex(), case["expectedHeaderHex"])
            check(f"datagram / {name} header length", len(got), case["headerLen"])
            per = case["maxDatagram"] - 12
            count = -(-case["totalLen"] // per)
            check(f"datagram / {name} fragment count", count, case["fragCount"])
        elif name.startswith("CLOSE"):
            got = base_word(2, case["flowId"])
            check(f"datagram / {name}", got.hex(), case["expectedHex"])
            check(f"datagram / {name} header length", len(got), case["headerLen"])
        elif "payloadHex" in case:
            got = base_word(0, case["flowId"]) + bytes.fromhex(case["payloadHex"])
            check(f"datagram / {name}", got.hex(), case["expectedHex"])
        elif "payloadLens" in case:
            for length in case["payloadLens"]:
                frames = 1 if 4 + length <= case["maxDatagram"] else 2
                check(f"datagram / {name} len={length}", frames, case["expectedFrames"])
    for reject in section["rejects"]:
        if "hex" in reject:
            check(f"datagram reject / {reject['name']}", decode_base(bytes.fromhex(reject["hex"])), None)


# --- Stream-derived UDP flow ids ------------------------------------------------

def derive_flow_id(stream_id: int, initiator: str):
    if stream_id & 0x2:  # unidirectional
        return None
    if (stream_id & 0x1) != (1 if initiator == "server" else 0):
        return None
    flow_id = (stream_id >> 2) + 1
    return flow_id if 1 <= flow_id <= MAX_FLOW_ID else None


def verify_flow_ids(section: dict) -> None:
    for case in section["cases"]:
        got = derive_flow_id(case["streamId"], case["initiator"])
        check(f"udpFlowId / {case['initiator']} stream {case['streamId']}", got, case["flowId"])
    for reject in section["rejects"]:
        stream_id = reject["streamId"]
        check(f"udpFlowId reject / stream {stream_id}",
              (derive_flow_id(stream_id, "client"), derive_flow_id(stream_id, "server")), (None, None))


# --- Morph -----------------------------------------------------------------------

def _rotl(value: int, bits: int) -> int:
    return ((value << bits) & 0xFFFFFFFF) | (value >> (32 - bits))


def chacha20_block(key: bytes, counter: int, nonce: bytes) -> bytes:
    """RFC 8439 section 2.3."""
    state = list(struct.unpack("<4I", b"expand 32-byte k")) + list(struct.unpack("<8I", key)) \
        + [counter] + list(struct.unpack("<3I", nonce))
    x = state[:]

    def quarter(a, b, c, d):
        x[a] = (x[a] + x[b]) & 0xFFFFFFFF; x[d] = _rotl(x[d] ^ x[a], 16)
        x[c] = (x[c] + x[d]) & 0xFFFFFFFF; x[b] = _rotl(x[b] ^ x[c], 12)
        x[a] = (x[a] + x[b]) & 0xFFFFFFFF; x[d] = _rotl(x[d] ^ x[a], 8)
        x[c] = (x[c] + x[d]) & 0xFFFFFFFF; x[b] = _rotl(x[b] ^ x[c], 7)

    for _ in range(10):
        quarter(0, 4, 8, 12); quarter(1, 5, 9, 13); quarter(2, 6, 10, 14); quarter(3, 7, 11, 15)
        quarter(0, 5, 10, 15); quarter(1, 6, 11, 12); quarter(2, 7, 8, 13); quarter(3, 4, 9, 14)
    return struct.pack("<16I", *((x[i] + state[i]) & 0xFFFFFFFF for i in range(16)))


def chacha20_xor(key: bytes, nonce: bytes, data: bytes) -> bytes:
    out = bytearray()
    for block in range(0, len(data), 64):
        stream = chacha20_block(key, block // 64, nonce)
        out += bytes(a ^ b for a, b in zip(data[block:block + 64], stream))
    return bytes(out)


def morph_keys(shared_key: bytes) -> dict:
    root = hmac256(b"nowhere/nw3/morph", shared_key)  # the label is the key itself
    return {name: hmac256(root, name.encode() + b"\x01")
            for name in ("tcp c2s", "tcp s2c", "udp c2s", "udp s2c")}


def verify_morph(section: dict) -> None:
    # RFC 8439 section 2.4.2, so that this ChaCha20 is checked against the RFC
    # and not only against the fixture it is about to judge.
    rfc_key = bytes(range(32))
    rfc_nonce = bytes.fromhex("000000000000004a00000000")
    rfc_plain = (b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for "
                 b"the future, sunscreen would be it.")
    rfc_first = bytes(a ^ b for a, b in zip(rfc_plain[:64], chacha20_block(rfc_key, 1, rfc_nonce)))
    check("morph / ChaCha20 RFC 8439 2.4.2 first block", rfc_first[:16].hex(), "6e2e359a2568f98041ba0728dd0d6981")

    for case in section["chacha20"]:
        got = chacha20_block(bytes.fromhex(case["keyHex"]), 0, bytes.fromhex(case["nonceHex"]))
        check(f"morph / {case['name']}", got.hex(), case["keystreamHex"])

    for case in section["cases"]:
        keys = morph_keys(case["sharedKeyUtf8"].encode())
        for field, name in (("tcpC2sHex", "tcp c2s"), ("tcpS2cHex", "tcp s2c"),
                            ("udpC2sHex", "udp c2s"), ("udpS2cHex", "udp s2c")):
            check(f"morph / key {name}", keys[name].hex(), case[field])

    tcp = section["tcp"]
    keys = morph_keys(tcp["sharedKeyUtf8"].encode())
    prelude, nonce = bytes.fromhex(tcp["preludeHex"]), bytes.fromhex(tcp["nonceHex"])
    check("morph / tcp prelude length", len(prelude), 64)
    check("morph / tcp nonce length", len(nonce), 12)
    wire = prelude + nonce + chacha20_xor(keys["tcp c2s"], nonce, tcp["clientPlainUtf8"].encode())
    check("morph / tcp client wire", wire.hex(), tcp["expectedClientWireHex"])
    down = chacha20_xor(keys["tcp s2c"], nonce, tcp["serverPlainUtf8"].encode())
    check("morph / tcp server wire (no bootstrap, same nonce)", down.hex(), tcp["expectedServerWireHex"])
    bounds = tcp["fadeBoundaries"]
    check("morph / fade A range", bounds["a"], [8, 12])
    check("morph / fade B range", bounds["b"], [16, 32])
    check("morph / fade printable range", bounds["printable"], [0x20, 0x7E])

    udp = section["udp"]
    keys = morph_keys(udp["sharedKeyUtf8"].encode())
    nonce = bytes.fromhex(udp["nonceHex"])
    wire = nonce + chacha20_xor(keys["udp " + udp["direction"]], nonce, bytes.fromhex(udp["plainHex"]))
    check("morph / udp wire", wire.hex(), udp["expectedWireHex"])
    check("morph / udp overhead", udp["overhead"], 12)


def prelude_tail(keys: dict, prefix: bytes, nonce: bytes, high: bytes, identity: bytes, counter: bytes):
    """The Authenticated Prelude, written bit by bit from docs/prelude.md.

    Deliberately not the client's accumulator loop: each of the 224 payload
    bits is placed by its own index, so a shared off-by-one cannot hide.
    """
    header = nonce + prefix + high
    tag = hmac256(keys["tcp prelude auth"], header + identity + counter)[:14]
    mask = hmac256(keys["tcp prelude mask"], header)[:28]
    payload = bytes(a ^ b for a, b in zip(identity + counter + tag, mask))
    tail = bytearray()
    for index in range(32):
        high_bit = (high[index // 8] >> (7 - index % 8)) & 1
        low = 0
        for bit in range(7):
            offset = index * 7 + bit
            low = (low << 1) | ((payload[offset // 8] >> (7 - offset % 8)) & 1)
        tail.append((high_bit << 7) | low)
    return tag, bytes(tail)


def verify_prelude(section: dict) -> None:
    root = hmac256(b"nowhere/nw3/morph", section["sharedKeyUtf8"].encode())
    keys = {label: hmac256(root, label.encode() + b"\x01")
            for label in (section["authLabel"], section["maskLabel"])}
    check("prelude / counter is 48 bits", int(section["maxCounterHex"], 16), (1 << 48) - 1)
    for case in section["cases"]:
        tag, tail = prelude_tail(keys, *(bytes.fromhex(case[f]) for f in
                                         ("prefixHex", "nonceHex", "highBitsHex", "identityHex", "counterHex")))
        check(f"prelude / {case['name']} tag", tag.hex(), case["tagHex"])
        check(f"prelude / {case['name']} tail", tail.hex(), case["tailHex"])
        check(f"prelude / {case['name']} keeps every high bit",
              bytes(b & 0x80 for b in tail).hex(),
              bytes(((bytes.fromhex(case["highBitsHex"])[i // 8] >> (7 - i % 8)) & 1) << 7 for i in range(32)).hex())


def main() -> int:
    path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    data = json.loads(path.read_text(encoding="utf-8"))

    check("baseline / ALPN", data["baseline"]["alpn"], "nw3")
    verify_auth(data["auth"])
    verify_flow_start(data["flowStart"])
    verify_target(data["target"])
    verify_setup_result(data["setupResult"])
    verify_uot(data["uot"])
    verify_datagram(data["quicDatagram"])
    verify_flow_ids(data["udpFlowId"])
    verify_morph(data["morph"])
    verify_prelude(data["morphPrelude"])

    base = data["baseline"]
    print(f"Baseline {base['upstreamRepo']} @ {base['tag']} {base['commit'][:8]}")
    if failures:
        print(f"\n{len(failures)} of {checks} checks FAILED:\n")
        for failure in failures:
            print("  FAIL " + failure)
        return 1
    print(f"PASS: all {checks} checks agree (spec document / this script / upstream Rust tests)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
