# ADR-0003 · Morph

**Status:** implemented and **verified against the v3.0.1 oracle and on an
emulator**, 2026-10-21 · **Follows from:** D-21 (internal), D-08 (faithful
downstream)

## Context

Nowhere v3 added Morph, `docs/protocol.md` § Morph: a keyed transform applied
*below* TLS and QUIC, on by default (`morph=1`), with no negotiation — "peers
must agree on Shared Key and whether Morph is enabled; no in-band switch or
detection is performed".

- **Under TLS**: the client opens the TCP connection with a 64-byte prelude and
  a 12-byte nonce, unmasked, then XORs every TLS byte it sends with IETF
  ChaCha20 under the `tcp c2s` key and that nonce, counter zero, continuously
  across records. The server sends no bootstrap and masks with `tcp s2c` under
  the same nonce.
- **Under QUIC**: every UDP datagram is a fresh 12-byte nonce followed by the
  QUIC packet XORed with ChaCha20 under `udp c2s` (or `udp s2c` the other way).
  A datagram of 12 bytes or fewer is discarded.
- **Keys**: `root = HMAC-SHA256("nowhere/nw3/morph", shared_key)` — the label is
  the HMAC key, not hashed into a salt the way authentication's is — then one
  HKDF-Expand block per direction.

Upstream is explicit about what it is: "wire masking, not authentication or
integrity. TLS/QUIC still provides authenticated encryption, certificate
validation and replay protection."

This client's earlier position was "no obfuscation layer", on the reasoning
that one needs both ends and this project does not run Portals. Morph is not
that: it is upstream's, specified, and on by default, so a v3 client that does
not implement it cannot reach a default v3 Portal. Implementing it is what
faithful downstream means here, and it adds no claim the client may make about
censorship — upstream makes none.

## Decisions

### ChaCha20 is written out, not taken from a provider

Morph needs the bare stream cipher — no Poly1305 — continued across arbitrary
call boundaries, starting at block zero. The JDK has `ChaCha20`; Android's
platform provider does not promise it at every API level from 26; Conscrypt's
continuation across partial blocks is an implementation detail rather than a
contract. A mask that differs on one provider is a handshake that never
completes on some phones, with nothing in any log to say why. `ChaCha20.kt` is
sixty lines checked against RFC 8439 § 2.4.2, upstream's block-zero vector,
and an independent implementation in `verify-vectors.py`. It stops one block
short of the 32-bit counter's end (`2^38 - 64` bytes), as upstream does, rather
than wrapping into keystream it has already used.

### Under TLS: a masking `Socket`, and Conscrypt in engine mode everywhere

`MorphSocket` wraps the connected TCP socket, writes the bootstrap as one write,
and masks its streams; TLS is layered over it. Everything except the streams —
timeouts, shutdown, close, addresses — delegates to the real socket, and
`getChannel()` returns null, because a channel would be a second, unmasked way
to the bytes.

**This forced the TLS stack.** Conscrypt's default layered socket, given a
socket with a file descriptor, uses the descriptor directly and never calls the
wrapper's streams: the handshake would leave unmasked. Its engine-based socket
goes through the streams, and `Conscrypt.setUseEngineSocket(factory, true)`
selects it. The platform provider cannot be told to, so the TLS carrier is
Conscrypt on every API level now (ADR-0001's amendment). `minSdk` is unaffected.

The prelude is `fade`, upstream's default: printable ASCII up to a boundary A
in [8,12], 7-bit up to B in [16,32], full 8-bit after, both boundaries and the
printable bytes rejection-sampled from one byte each, redrawing on the biased
tail. The procedure is reproduced byte for byte — given the same random source
it yields the same prelude as upstream's — because nothing on the server checks
it and only a test can.

### Under QUIC: at the socket boundary, in Kotlin

The QUIC bridge never owns a socket (`checkNativeBridge` enforces it): ngtcp2
produces datagrams into a buffer and `QuicConnection` sends them. That boundary
is the only place a whole UDP datagram exists, so Morph is applied there —
`MorphDatagram.seal` on the way out, `.open` on the way in — and ngtcp2 sees
exactly the QUIC packet and nothing around it.

The one C change: the bridge now takes the largest UDP payload ngtcp2 may emit
and advertise, and sets both `max_tx_udp_payload_size` and the
`max_udp_payload_size` transport parameter to it — 1452, or 1440 under Morph, so
that a packet and its nonce still fit a 1500-byte path. The receive buffer grew
to 2048 for the same reason in the other direction.

Twelve bytes of overhead and a ChaCha20 pass per datagram are the cost. What has
been measured is that 20 MiB over QUIC with Morph arrives intact on an x86_64
emulator. Its cost against the same transfer without Morph has not been
measured, on an emulator or on a phone.

## Verification

| Claim | Where |
|---|---|
| Keys match upstream's fixture | `MorphVectorTest.theDirectionalKeysAreUpstreams`, `verify-vectors.py` |
| ChaCha20 is RFC 8439's | `MorphVectorTest.chaCha20MatchesRfc8439`, `.chaCha20StartsAtBlockZero` |
| TCP and UDP wire images | `MorphVectorTest.theTcpWireIsUpstreamsLayout`, `.theUdpWireIsUpstreamsLayout`, against vectors computed by a third implementation (Python `cryptography`) |
| The socket behaves as a socket | `MorphSocketTest.*`, over real loopback sockets with a hand-written Portal at the other end |
| `fade` | `MorphPreludeTest.*` |
| Interoperates | `oracle-diff.sh`: every case with Morph on and off, both carriers; `smoke-local.sh`; on a device, `e2e-fakeip.sh` and `e2e-tunnel-fetch.sh` with Morph on |
| A mismatch fails | `NowhereDialerAgainstPortalTest.aMorphMismatchFailsTheHandshake`; `oracle-diff.sh` `wrong_key`, where a wrong key fails the handshake because it is also a wrong mask |

## Consequences

- **A wrong key fails earlier, and differently.** With Morph on, a wrong shared
  key is also a wrong mask, so the TLS or QUIC handshake never completes and
  nothing is ever authenticated. The client reports a handshake failure that
  names Morph as a thing to check, and the failover logic treats it as the
  Portal answering — not as unreachability — exactly as before.
- **Morph cannot be detected.** A node whose `morph` disagrees with its Portal
  fails the same way, and there is no in-band signal to say so. The node editor
  shows Morph as a switch with the instruction that it must match the Portal.
- **What Morph is not.** It is not authentication or integrity, and it is not a
  claim of censorship resistance. Nothing in the app may say otherwise.
