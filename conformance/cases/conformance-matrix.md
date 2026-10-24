# Conformance test matrix

Derived case by case from Nowhere `docs/protocol.md`, `docs/configuration.md`,
`docs/security.md` and `docs/interoperability.md` at **v3.1.0 @ 0000065**, with
the Core sections now in `Nowhere-Protocol` `PROTOCOL.md` @ 0000001 and the
Prelude Extension in Nowhere `docs/prelude.md`. Section references are to the
Core specification unless stated. The **Req** column
references PRD requirement IDs where one still applies; v3 cases with no v1
counterpart carry `nw3`.

**Which of these are actually covered is in [`coverage.md`](coverage.md)**, row
by row. This file says what ought to be tested; that one says what is, and
names the reason wherever the answer is "not automatically".

Three case types:

- **KAT** — byte-level fixed vector, pure unit test, no network needed. Fixtures
  live in `vectors/protocol-vectors.json` and are self-verified by
  `scripts/verify-vectors.py`.
- **Property / fuzz** — must not crash, must not OOM, must not allocate without
  bound, for arbitrary input.
- **E2E** — needs a real Portal: the oracle on this host
  (`scripts/portal-for-tests.sh`, `scripts/oracle-diff.sh`), or a device
  (`scripts/e2e-fakeip.sh`, `scripts/e2e-tunnel-fetch.sh`).

v1.8.2's matrix had sections for TLS Mux and for split carriers. v3 removed
both, and their rows went with them; the v1 matrix is in git history.

---

## 1. Authentication (§ Authentication)

| Case | Type | Req |
|---|---|---|
| Salt, root and both role keys match upstream's vectors | KAT | NW-P-01 |
| Vector AuthFrame over TLS (transport `0x01`) matches | KAT | NW-P-01 |
| Vector AuthFrame over QUIC (transport `0x02`) matches | KAT | NW-P-01 |
| The AuthFrame is exactly the 16-byte tag; no session id, no role byte | KAT | nw3 |
| Exporter is 32 bytes, label `EXPORTER-Nowhere-nw3-Auth`, empty context | E2E | NW-P-01 |
| An Egress tag verifies over QUIC only | Unit | nw3 |
| Share-link keys are 32 lowercase hex characters, used as text bytes | KAT | nw3 |
| URL with a password component rejected | KAT | NW-P-23 |
| Malformed percent escapes rejected | KAT | NW-P-23 |
| Literal `+` is not decoded as a space | KAT | NW-P-23 |
| Tag comparison is constant time | Code review | NW-P-01 |
| A wrong key is never answered: Morph on, the handshake fails; Morph off, the Portal closes | E2E | NW-P-01 |

## 2. FlowStart (§ FlowStart and Target)

| Case | Type | Req |
|---|---|---|
| TCP is `00`, UDP is `01` | KAT | nw3 |
| `00 01 7f000001 01bb` is TCP to `127.0.0.1:443` | KAT | nw3 |
| Reserved bits 7..1 are sent as zero and ignored on receipt | Property | nw3 |

## 3. Target (§ FlowStart and Target; unchanged since v1)

| Case | Type | Req |
|---|---|---|
| IPv4 `192.0.2.1:443` → `01c000020101bb` | KAT | NW-P-05 |
| IPv6 `[2001:db8::1]:53` → 19 bytes | KAT | NW-P-05 |
| Domain, IDNA, 21 bytes plus port | KAT | NW-P-05 |
| Port 0 rejected before dialling | Property | NW-P-05 |
| Empty domain / over 253 bytes / non-ASCII / bad labels rejected | Property | NW-P-05 |
| Unknown ATYP and truncated address rejected | Property | NW-P-05 |

## 4. SetupResult (§ Setup results)

| Case | Type | Req |
|---|---|---|
| All five values have their v3 wire byte | KAT | NW-P-06 |
| Values above 4 are protocol errors | Property | NW-P-06 |
| v1's numbering is gone: 4 is INTERNAL_ERROR, 5 is invalid | KAT | nw3 |
| **All four rejections produce distinct messages and log lines** | Unit | NW-P-06 / NW-A-06 |
| DIAL_FAILED for an unreachable target, over both carriers | E2E | NW-P-06 |
| A missing flow request is answered INVALID_REQUEST at the handshake deadline | E2E | NW-P-11 |

## 5. The TLS carrier (§ Connection establishment)

| Case | Type | Req |
|---|---|---|
| Opening is AuthFrame ‖ FlowStart ‖ Target in one write | Unit | NW-P-10 |
| **Target payload waits for READY**; a rejected flow never sends it | Unit + E2E | nw3 |
| One connection per flow; a connection carries exactly one flow | Unit + E2E | nw3 |
| After READY the connection carries raw bytes; the setup deadline comes off | Unit | NW-P-10 |
| Large transfer is lossless (digest comparison) | E2E | NW-P-10 |

## 6. UDP over TLS (§ TLS UDP framing; unchanged since v1)

| Case | Type | Req |
|---|---|---|
| Empty packet encodes to `0000` and is valid | KAT | NW-P-07 |
| `abc` → `0003616263` | KAT | NW-P-07 |
| Back-to-back packets split correctly | Unit | NW-P-07 |
| EOF inside a length or a payload is truncation, EOF between packets ends the flow | Property | NW-P-07 |
| A real UDP round trip | E2E | NW-P-07 |

## 7. The QUIC carrier and DATAGRAMs (§ Vector establishment, § UDP flows, § QUIC datagrams)

| Case | Type | Req |
|---|---|---|
| AuthFrame on client Stream 0 only; later streams carry FlowStart first | Unit + E2E | NW-P-18 |
| A rebuilt connection authenticates again on its own Stream 0 | Unit | NW-P-18 |
| Unidirectional streams are never used | Unit | NW-P-18 |
| Only one bidirectional stream is credited before auth | E2E | NW-P-19 |
| UDP flow id is `(stream_id >> 2) + 1`, type bits checked first, `1..2^30-1` | KAT | nw3 |
| Base word: type in bits 31..30, flow id in 29..0 | KAT | nw3 |
| DATA and CLOSE are a 4-byte header, FRAGMENT a 12-byte one | KAT | nw3 |
| Type 3, flow id 0, CLOSE with payload, truncated word rejected | Property | NW-P-20 |
| No DATA before READY; the control stream carries no business bytes | Unit | NW-P-20 |
| Never fragment when the whole packet fits; `max - 12` per fragment, 2..255 | Unit | NW-P-21 |
| A fragment leaves at least one byte for every other fragment | Property | nw3 |
| Reassembly keyed by `(flowId, packetId)`; conflicts discard; bounded | Property | NW-P-21 |
| Both carriers carry TCP and UDP and interoperate with the oracle | E2E | NW-P-18..22 |
| Keep-alive outlives the idle timeout | E2E | NW-P-22 |
| A stream beyond the id space is refused without a write | Unit | nw3 |

## 8. Morph (§ Morph)

| Case | Type | Req |
|---|---|---|
| Four directional keys match upstream's fixture; the root label is the HMAC key | KAT | nw3 |
| ChaCha20 matches RFC 8439 and upstream's block-zero vector | KAT | nw3 |
| TCP: 64-byte prelude ‖ 12-byte nonce, then c2s keystream; s2c with no bootstrap | KAT | nw3 |
| TCP keystream continues across reads, writes and records | Unit | nw3 |
| `fade` prelude: printable `[0,A)`, low7 `[A,B)`, full8 after; A∈[8,12], B∈[16,32] | Unit | nw3 |
| UDP: fresh 12-byte nonce per datagram, counter zero; ≤ 12 bytes discarded | KAT | nw3 |
| QUIC payload size is reduced by the nonce | Unit | nw3 |
| Morph on and off both interoperate; a mismatch fails the handshake (3.1: silence, then a reset) | E2E | nw3 |
| Authenticated Prelude (`docs/prelude.md`): Tag, mask and 7-bit tail match upstream's vectors; prefix and high bits kept | KAT | nw3 |
| A 3.1 Portal verifies the Tag: one Counter twice is refused, the next accepted; Counters never repeat or wrap | E2E | nw3 |

## 9. Configuration and certificates (`docs/configuration.md`, `docs/security.md`)

| Case | Type | Req |
|---|---|---|
| Parse `nowhere://KEY@HOST:PORT[?net=..&morph=..&sni=..&pin=..]#NAME` | KAT | NW-P-23 |
| `net` defaults to `tcp`, `morph` to `1`, `sni` to the endpoint host | KAT | nw3 |
| First duplicate wins; an invalid first value is an error | KAT | nw3 |
| Unknown parameters ignored; `mux`, `alpn`, `pool` ignored | KAT | NW-P-24 |
| A 2.x dashboard's agreeing `up`/`down` are read as `net` | KAT | nw3 |
| Carrier paths refused with a reason | KAT | nw3 |
| Generated share links round-trip | KAT | NW-P-23 |
| `pin` takes priority over `sni`; case and length validated | Unit | NW-P-09 |
| **No unverified mode**: system trust and hostname, or a pin | Unit + E2E | nw3 |
| Hostname matching: SAN only, single-label wildcard, IP by bytes, no CN | Unit | nw3 |
| A wrong `pin` fails, and names the presented fingerprint | E2E | NW-P-09 |
| A generated certificate is refused by name on both carriers, on a device | E2E | nw3 |
| ALPN is `nw3`; another selection is refused with a readable message | Unit | NW-P-08 |

## 10. Dashboard integration (unchanged since v1)

| Case | Type | Req |
|---|---|---|
| Fetch `/sub/portal?token=` and parse multiple `nowhere://` lines | E2E | NW-D-01 |
| An http subscription URL produces an explicit warning | Unit | NW-D-01 |
| Token never appears in logs or crash reports | Code review | NW-D-01 |
| Parse and display all four `subscription-userinfo` fields | Unit | NW-D-02 |
| `total=-1` means unlimited; the UI must not show "0 of -1" | Unit | NW-D-02 |
| `upload` is always 0; do not present a misleading upload figure | Unit | NW-D-02 |
| Parse `profile-title` (base64) and the icon headers | Unit | NW-D-02 |
| Accept the `add-proxy` deep link form | E2E | NW-D-03 |
| Empty subscription or vanished nodes presented as "expired or over quota" | E2E | NW-D-04 |
| Requests carry `type` / `ver` / `caps` | Unit | NW-D-06 |

## 11. Cross-cutting resource and robustness

| Case | Type | Req |
|---|---|---|
| Every decoder survives arbitrary bytes without crash, OOM, or unbounded allocation | Fuzz | NW-Q-03 |
| The smallest enclosing header is validated before variable-length data is read | Code review | NW-Q-03 |
| Client recovers automatically after a Portal restart | E2E | — |
| Kotlin and Rust behave identically against one Portal on the same case set | E2E | NW-Q-04 |
| Remote resolution: a name only the Portal can resolve is fetched on a device | E2E | — |
| Protocol-layer line coverage ≥ 90% | Gate | NW-Q-06 |
