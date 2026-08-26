#!/usr/bin/env python3
"""Independently recompute every fixed vector in protocol-vectors.json.

This script references no Nowhere code. It re-implements the encodings from the
prose in docs/protocol.md and compares against the values transcribed into the
JSON. A pass means three sources agree: the spec document, this script, and the
upstream Rust tests.

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


# --- Section 2: connection authentication ----------------------------------

def derive_auth_key(shared_key: bytes) -> bytes:
    salt = hashlib.sha256(b"nowhere/now/1/auth-root").digest()
    auth_root = hmac256(salt, shared_key)              # HKDF-Extract(salt, IKM)
    return hmac256(auth_root, b"authentication" + b"\x01")  # HKDF-Expand, 1 block


def auth_frame(shared_key: bytes, transport: int, exporter: bytes, session_id: bytes) -> bytes:
    tag = hmac256(derive_auth_key(shared_key), bytes([transport]) + exporter + session_id)[:16]
    return session_id + tag


def verify_auth(section: dict) -> None:
    transports = {"tlsTcp": 0x01, "quic": 0x02}
    for case in section["cases"]:
        key = case["sharedKeyUtf8"].encode()
        if "expectedAuthKeyHex" in case:
            check(f"auth / {case['name']}", derive_auth_key(key).hex(), case["expectedAuthKeyHex"])
        if "expectedFrameHex" in case:
            frame = auth_frame(
                key,
                transports[case["transport"]],
                bytes.fromhex(case["exporterHex"]),
                bytes.fromhex(case["sessionIdHex"]),
            )
            check(f"auth / {case['name']}", frame.hex(), case["expectedFrameHex"])


# --- Section 4: FlowHeader --------------------------------------------------

ROLES = {"DUPLEX": 0, "OPEN": 1, "ATTACH": 2}
KINDS = {"TCP": 0, "UDP": 1}
CARRIERS = {"tls": 0, "quic": 1}


def flow_header(role: str, kind: str, up: str, down: str, hops: int, flow_id: int) -> bytes:
    flags = ROLES[role] | (KINDS[kind] << 2) | (CARRIERS[up] << 3) | (CARRIERS[down] << 4) | (hops << 5)
    return bytes([flags]) + flow_id.to_bytes(4, "big")


def verify_flow_header(section: dict) -> None:
    for case in section["cases"]:
        got = flow_header(case["role"], case["kind"], case["up"], case["down"], case["hops"], case["flowId"])
        check(f"flowHeader / {case['name']}", got.hex(), case["expectedHex"])
    # Spec constraint: the role bits of 0xff must land on the reserved value 0b11,
    # which is what keeps the Mux marker from colliding with a FlowHeader.
    check("flowHeader / 0xff role bits are reserved", 0xFF & 0b11, 0b11)


# --- Section 5: Target -----------------------------------------------------

def verify_target(section: dict) -> None:
    import ipaddress

    def ip_target(addr: str, port: int) -> bytes:
        ip = ipaddress.ip_address(addr)
        atyp = 0x01 if ip.version == 4 else 0x04
        return bytes([atyp]) + ip.packed + port.to_bytes(2, "big")

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


# --- Section 6: SetupResult ------------------------------------------------

def verify_setup_result(section: dict) -> None:
    expected = ["READY", "INVALID_REQUEST", "METADATA_CONFLICT", "PAIR_TIMEOUT",
                "FLOW_LIMIT", "DIAL_FAILED", "SESSION_REPLACED", "INTERNAL_ERROR"]
    check("setupResult / number of values", len(section["cases"]), 8)
    for case in section["cases"]:
        check(f"setupResult / byte {case['byte']}", case["name"], expected[case["byte"]])


# --- Section 8: UDP over stream (UoT) --------------------------------------

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


# --- Section 9: QUIC DATAGRAM ----------------------------------------------

def verify_datagram(section: dict) -> None:
    for case in section["cases"]:
        name = case["name"]
        if "fragIndex" in case:
            got = (bytes([0x01])
                   + case["flowId"].to_bytes(4, "big")
                   + case["packetId"].to_bytes(4, "big")
                   + bytes([case["fragIndex"], case["fragCount"]])
                   + case["totalLen"].to_bytes(2, "big"))
            check(f"datagram / {name}", got.hex(), case["expectedHeaderHex"])
            check(f"datagram / {name} header length", len(got), case["headerLen"])
            # Fragment planning: fragmentPayloadMax = maxDatagram - 13
            max_datagram = 1200
            per = max_datagram - 13
            count = -(-case["totalLen"] // per)
            check(f"datagram / {name} fragment count", count, case["fragCount"])
        elif name.startswith("CLOSE"):
            got = bytes([0x02]) + case["flowId"].to_bytes(4, "big")
            check(f"datagram / {name}", got.hex(), case["expectedHex"])
            check(f"datagram / {name} header length", len(got), case["headerLen"])
        elif "payloadHex" in case:
            payload = bytes.fromhex(case["payloadHex"])
            got = bytes([0x00]) + case["flowId"].to_bytes(4, "big") + payload
            check(f"datagram / {name}", got.hex(), case["expectedHex"])
        elif "payloadLens" in case:
            # Never fragment when the whole packet fits
            for length in case["payloadLens"]:
                frames = 1 if 5 + length <= case["maxDatagram"] else 2
                check(f"datagram / {name} len={length}", frames, case["expectedFrames"])


# --- Section 3: TLS Mux ----------------------------------------------------

def verify_mux(section: dict) -> None:
    check("mux / mode marker", section["marker"], "ff")
    check("mux / MuxHeader length", section["muxHeader"]["headerLen"], 8)
    flags = section["muxHeader"]["streamFlags"]
    check("mux / SYN", flags["01"], "SYN")
    check("mux / FIN", flags["02"], "FIN")
    check("mux / RST", flags["04"], "RST")
    bounds = section["bounds"]
    check("mux / max 32 KiB per STREAM frame", bounds["maxPayloadPerStreamFrame"], 32 * 1024)
    check("mux / 512 KiB per-stream credit", bounds["perStreamReceiveCredit"], 512 * 1024)
    check("mux / 512 KiB connection credit", bounds["connectionReceiveCredit"], 512 * 1024)
    # Shard density is the one number in this file upstream has actually moved
    # (12 -> 4 at v1.8.2). It is runtime placement rather than wire format, so
    # no vector catches a stale transcription - this literal does.
    check("mux / 4-flow shard density", bounds["shardFlowThreshold"], 4)
    check("mux / 30 s idle shard retirement", bounds["shardIdleCloseSeconds"], 30)


def main() -> int:
    path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    data = json.loads(path.read_text(encoding="utf-8"))

    verify_auth(data["auth"])
    verify_flow_header(data["flowHeader"])
    verify_target(data["target"])
    verify_setup_result(data["setupResult"])
    verify_uot(data["uot"])
    verify_datagram(data["quicDatagram"])
    verify_mux(data["tlsMux"])

    base = data["baseline"]
    print(f"Baseline {base['upstreamRepo']} @ {base['branch']} {base['commit'][:8]}")
    if failures:
        print(f"\n{len(failures)} of {checks} checks FAILED:\n")
        for failure in failures:
            print("  FAIL " + failure)
        return 1
    print(f"PASS: all {checks} checks agree (spec document / this script / upstream Rust tests)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
