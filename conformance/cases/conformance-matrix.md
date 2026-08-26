# Conformance test matrix

Derived case by case from Nowhere `docs/protocol.md` (v1.8.2 @ 8807960c).
The **Req** column references PRD requirement IDs. The **Layer** column is the
earliest delivery layer that should cover the case.

Three case types:

- **KAT** — byte-level fixed vector, pure unit test, no network needed. Fixtures
  live in `vectors/protocol-vectors.json` and are self-verified by
  `scripts/verify-vectors.py`.
- **Property / fuzz** — must not crash, must not OOM, must not allocate without
  bound, for arbitrary input.
- **E2E** — needs a real Portal (`scripts/smoke-local.sh` or
  `scripts/e2e-android.sh`).

---

## 1. Connection authentication (spec 2)

| Case | Type | Req | Layer |
|---|---|---|---|
| `authKey` derivation matches the fixed vector | KAT | NW-P-01 | L1 |
| AuthFrame over TLS/TCP (transport 0x01) matches | KAT | NW-P-01 | L1 |
| AuthFrame over QUIC (transport 0x02) matches | KAT | NW-P-01 | L3 |
| Exporter is 32 bytes, label `EXPORTER-Nowhere-Auth`, empty context | E2E | NW-P-01 | L1 |
| Shared key of length 0 and 256 rejected | Property | NW-P-01 | L1 |
| URL with a password component rejected | KAT | NW-P-23 | L1 |
| Malformed percent escapes rejected (`%GG`, `%`, `%1`) | KAT | NW-P-23 | L1 |
| Literal `+` is not decoded as a space | KAT | NW-P-23 | L1 |
| Tag comparison is constant time | Code review | NW-P-01 | L1 |
| Wrong key fails, with no distinguishable response difference | E2E | NW-P-01 | L1 |

## 2. FlowHeader (spec 4)

| Case | Type | Req | Layer |
|---|---|---|---|
| DUPLEX/TCP/tls/tls encodes to `0001020304` | KAT | NW-P-03 | L1 |
| OPEN/UDP/quic-up/tls-down encodes to `0d11223344` | KAT | NW-P-03 | L3 |
| ATTACH/UDP/tls-up/quic-down encodes to `1600000007` | KAT | NW-P-03 | L3 |
| Role bits `0b11` rejected | Property | NW-P-03 | L1 |
| `flowId` zero rejected | Property | NW-P-03 | L1 |
| Client-originated flows always carry `hops` 0 | Unit | NW-P-03 | L1 |
| DUPLEX requires up == down; OPEN/ATTACH require up != down | Unit | NW-P-04 | L1 |
| `flowId` unique per session, allocated monotonically, reused only after release | Property | NW-P-02 | L1 |
| No duplicate `flowId` under concurrent flow opening (stress) | Property | NW-P-02 | L2 |

## 3. Target (spec 5)

| Case | Type | Req | Layer |
|---|---|---|---|
| IPv4 `192.0.2.1:443` → `01c000020101bb` | KAT | NW-P-05 | L1 |
| IPv6 `[2001:db8::1]:53` → 19 bytes | KAT | NW-P-05 | L1 |
| Domain, IDNA, 21 bytes plus port | KAT | NW-P-05 | L1 |
| Port 0 rejected before dialling | Property | NW-P-05 | L1 |
| Empty domain / over 253 bytes / non-ASCII rejected | Property | NW-P-05 | L1 |
| Unknown ATYP and truncated address rejected | Property | NW-P-05 | L1 |
| Domain carries no port and no IPv6 brackets | Unit | NW-P-05 | L1 |

## 4. SetupResult (spec 6)

| Case | Type | Req | Layer |
|---|---|---|---|
| All eight values have their stable wire byte | KAT | NW-P-06 | L1 |
| Values outside `0..7` are protocol errors and close the flow | Property | NW-P-06 | L1 |
| **All seven rejections produce distinct messages and log lines** | E2E | NW-P-06 / NW-A-06 | L1 |
| DIAL_FAILED: unreachable target | E2E | NW-P-06 | L1 |
| PAIR_TIMEOUT: send OPEN without ATTACH | E2E | NW-P-06 | L3 |
| METADATA_CONFLICT: mismatched OPEN and ATTACH metadata | E2E | NW-P-06 | L3 |
| FLOW_LIMIT: exceed the flow cap | E2E | NW-P-06 | L2 |
| SESSION_REPLACED: new carrier for the same session | E2E | NW-P-06 | L3 |
| Only the downlink of a split flow receives the result | E2E | NW-P-06 | L3 |

## 5. TCP payload and dedicated lanes (spec 7, spec 1)

| Case | Type | Req | Layer |
|---|---|---|---|
| After READY the lane carries raw stream bytes, no per-chunk header | E2E | NW-P-10 | L1 |
| Cold lane writes AUTH + FLOW + TARGET + first payload in one write | E2E | NW-P-10 | L1 |
| A lane carries one flow and is not reused | Unit | NW-P-10 | L1 |
| No first FlowHeader byte within 40 s of auth → reclaimed by Portal | E2E | NW-P-11 | L1 |
| Clean EOF closes the sending half; state released once both directions finish | E2E | NW-P-10 | L1 |
| Large bidirectional transfer is lossless (checksum comparison) | E2E | NW-P-10 | L1 |

## 6. UDP over stream (spec 8)

| Case | Type | Req | Layer |
|---|---|---|---|
| Empty packet encodes to `0000` and is valid | KAT | NW-P-07 | L1 |
| `abc` → `0003616263` | KAT | NW-P-07 | L1 |
| Back-to-back packets split correctly | Unit | NW-P-07 | L1 |
| EOF after one length byte is a protocol error | Property | NW-P-07 | L1 |
| EOF before the declared payload completes is a protocol error | Property | NW-P-07 | L1 |
| Length above the maximum rejected | Property | NW-P-07 | L1 |
| Real UDP round trip (DNS query through the tunnel) | E2E | NW-P-07 | L1 |

## 7. TLS Mux (spec 3)

| Case | Type | Req | Layer |
|---|---|---|---|
| `0xff` marker written after AuthFrame; Portal does not echo it | E2E | NW-P-12 | L2 |
| MuxHeader 8-byte layout | KAT | NW-P-13 | L2 |
| STREAM and WINDOW encode/decode | KAT | NW-P-13 | L2 |
| A DATAGRAM kind closes the carrier as unsupported | E2E | NW-P-13 | L2 |
| SYN opens, FIN half-closes, RST resets | E2E | NW-P-14 | L2 |
| RST must be the only flag with `value=0` | Property | NW-P-14 | L2 |
| Any other flag bit set is rejected | Property | NW-P-14 | L2 |
| Late FIN and RST processing is idempotent | Property | NW-P-14 | L2 |
| Payload needs both stream and connection credit before queueing | Unit | NW-P-15 | L2 |
| WINDOW with `flowId=0` replenishes connection credit | Unit | NW-P-15 | L2 |
| Credit beyond the configured window closes the carrier | Property | NW-P-15 | L2 |
| Late WINDOW for a closed stream is ignored | Property | NW-P-15 | L2 |
| A STREAM frame never exceeds 32 KiB of payload | Unit | NW-P-16 | L2 |
| 256-stream cap and 512 outbound queue slots respected | Property | NW-P-16 | L2 |
| A new shard opens at 4 active flows; a fully idle shard closes after 30 s | Unit | NW-P-17 | L2 |
| Mux `flowId` matches the FlowHeader `flowId` | Property | NW-P-13 | L2 |
| Closing the carrier fails every logical stream on it | E2E | NW-P-14 | L2 |
| The same case set runs under both `mux=0` and `mux=1` | E2E | NW-P-12 | L2 |

## 8. QUIC carrier and DATAGRAM (spec 1, spec 9)

| Case | Type | Req | Layer |
|---|---|---|---|
| Auth on the first bidirectional stream; later streams send no AuthFrame | E2E | NW-P-18 | L3 |
| Unidirectional streams are never used | Unit | NW-P-18 | L3 |
| **Only one bidirectional stream is credited before auth; wait for expansion** | E2E | NW-P-19 | L3 |
| DATA 5-byte header | KAT | NW-P-20 | L3 |
| CLOSE 5-byte header | KAT | NW-P-20 | L3 |
| FRAGMENT 13-byte header | KAT | NW-P-20 | L3 |
| Type 3 and non-zero reserved bits rejected | Property | NW-P-20 | L3 |
| No DATA before READY | E2E | NW-P-20 | L3 |
| Never fragment when the whole packet fits | Unit | NW-P-21 | L3 |
| `fragmentPayloadMax = maxDatagram - 13`; count falls in 2..255 | Unit | NW-P-21 | L3 |
| Reassembly keyed by `(flowId, packetId)` | Unit | NW-P-21 | L3 |
| Identical duplicate fragment ignored; differing bytes discard the packet | Property | NW-P-21 | L3 |
| Conflicting count or totalLen discards the packet | Property | NW-P-21 | L3 |
| Reassembled length not equal to totalLen discards the packet | Property | NW-P-21 | L3 |
| Reassembly slots and lifetime are bounded (64 slots / 10 s) | Property | NW-P-21 | L3 |
| Replanning after maxDatagram shrinks uses a new packetId | Unit | NW-P-21 | L3 |
| packetId allocation skips zero | Unit | NW-P-21 | L3 |
| All four carrier combinations interoperate | E2E | NW-P-18..22 | L3 |
| Connection migrates or is rebuilt after a network change | Manual | NW-P-22 | L3 |
| Keep-alive stays within the idle timeout and is more power-frugal than the upstream 15 s | Manual | NW-P-22 | L3 |

## 9. Configuration and certificates

| Case | Type | Req | Layer |
|---|---|---|---|
| Parse `nowhere://key@host:port?...#name` | KAT | NW-P-23 | L1 |
| **Unknown and deprecated parameters ignored (including the 1.7 leftover `pool=5`)** | KAT | NW-P-24 | L1 |
| Generated share links round-trip with both this client and the dashboard | E2E | NW-P-23 | L1 |
| `up`/`down` default to `udp`; unsupported combinations are surfaced, never silently rewritten | E2E | NW-P-25 | L1 |
| `pin` takes priority over `sni`; `pin` case and length validated | Unit | NW-P-09 | L1 |
| **Neither `sni` nor `pin`: handled per decision D-11, with a persistent insecure marker** | E2E | NW-P-09 | L1 |
| A wrong `pin` fails the handshake | E2E | NW-P-09 | L1 |
| Mismatched `alpn` fails the handshake with a readable message | E2E | NW-P-08 | L1 |

## 10. Dashboard integration

| Case | Type | Req | Layer |
|---|---|---|---|
| Fetch `/sub/portal?token=` and parse multiple `nowhere://` lines | E2E | NW-D-01 | L1 |
| An http subscription URL produces an explicit warning | Unit | NW-D-01 | L1 |
| Token never appears in logs or crash reports | Code review | NW-D-01 | L1 |
| Parse and display all four `subscription-userinfo` fields | Unit | NW-D-02 | L1 |
| `total=-1` means unlimited; the UI must not show "0 of -1" | Unit | NW-D-02 | L1 |
| `upload` is always 0; do not present a misleading upload figure | Unit | NW-D-02 | L1 |
| Parse `profile-title` (base64) and the icon headers | Unit | NW-D-02 | L1 |
| Accept the `add-proxy` deep link form | E2E | NW-D-03 | L1 |
| Empty subscription or vanished nodes presented as "expired or over quota", not a network error | E2E | NW-D-04 | L1 |
| Requests carry `type` / `ver` / `caps` | Unit | NW-D-06 | L1 |

## 11. Cross-cutting resource and robustness

| Case | Type | Req | Layer |
|---|---|---|---|
| Every decoder survives arbitrary bytes without crash, OOM, or unbounded allocation | Fuzz | NW-Q-03 | L1 |
| The smallest enclosing header is validated before variable-length data is read | Code review | NW-Q-03 | L1 |
| Network-provided lengths are checked before memory is reserved | Code review | NW-Q-03 | L1 |
| Client recovers automatically after a Portal restart | E2E | — | L1 |
| Under queue pressure, packets are dropped rather than queued without bound | Property | — | L2 |
| Kotlin and Rust implementations behave identically against one Portal on the same case set | E2E | NW-Q-04 | L1 |
| Protocol-layer line coverage ≥ 90% | Gate | NW-Q-06 | L1 |
