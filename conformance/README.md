# nowhere-conformance

Conformance suite for the Nowhere Android client.

Everything here is **independent of the pending project decisions** (package name,
`minSdk`, repository layout), so it can exist before the client repository does.
It is meant to be moved into the client repository as-is.

Its purpose is the PRD requirement made concrete: *correctness is guaranteed by
automation, not by manual testing*.

## Layout

```
PROTOCOL_BASELINE             pinned upstream snapshot: repo, tag, commit, known state
vectors/protocol-vectors.json byte-level fixed vectors (language-agnostic), plus
                              rejection cases and boundary values
scripts/verify-vectors.py     recomputes every vector independently; no upstream code
scripts/smoke-local.sh        local end-to-end: Portal + Vector + target service
scripts/drift-check.sh        upstream drift detection; only normative changes block
scripts/device-connect.sh     best-effort discovery of one usable Android device
scripts/emulator-setup.sh     one-time AVD preparation (only if you want an AVD)
scripts/e2e-android.sh        host-side Portal + on-device connectedAndroidTest
scripts/e2e-fakeip.sh         remote resolution on a device: a name only the Portal knows
scripts/oracle-diff.sh        the same cases through both implementations, both carriers, Morph on and off
scripts/oracle-cases.py       the oracle's half of that comparison, over raw SOCKS5
scripts/portal-lifecycle.sh   the two cases that need the Portal to stop being there
scripts/portal-for-tests.sh   a Portal for the JVM live tests; prints key, address and pin
scripts/e2e-tunnel-fetch.sh   a fetch from inside the tunnel, proved by the Portal's own counters
scripts/quic-probe.sh         D-15: does a QUIC stack give us the exporter authentication needs
quic-probe/probe.c            the spike that answers it, derived from ngtcp2's simpleclient (MIT)
cases/conformance-matrix.md   test matrix derived from the spec, mapped to PRD IDs
cases/coverage.md             every row and the test that covers it, or why it has none
```

## What you can run right now

```bash
# 1) Self-verifying vectors: spec document / this suite / upstream Rust tests
python3 scripts/verify-vectors.py

# 2) End-to-end smoke test (build the binary first: cargo build --release --locked)
scripts/smoke-local.sh

# 3) Upstream drift detection, against both upstream repositories
scripts/drift-check.sh /path/to/Nowhere /path/to/Nowhere-Protocol

# 4) This implementation against the reference one, case by case
scripts/oracle-diff.sh

# 5) Remote resolution on a device (needs Docker and one Android device)
scripts/e2e-fakeip.sh

# 6) The whole instrumentation suite on a device, with its Portal and origins
scripts/e2e-android.sh /path/to/Somewhere-Android
```

**Verified on 2026-10-24, at the baseline this file pins (v3.1.0), on Linux
x86_64 and an API 35 x86_64 emulator:**

- `verify-vectors.py` — 99 checks pass, among them upstream's four
  Authenticated Prelude vectors recomputed bit by bit. It carries its own
  ChaCha20, checked against RFC 8439 before it judges the Morph vectors.
- `smoke-local.sh` — passes over TLS and QUIC, Morph on: SOCKS5 → the oracle's
  Vector → Portal → target.
- `drift-check.sh` — passes, watching both Nowhere and the standalone
  Nowhere-Protocol specification. It caught v3.1.0, which this baseline is the
  answer to; pointed at the previous pin it still reports the five changed files.
- `oracle-diff.sh` — passes. Five cases — payload by address, payload by name,
  `DIAL_FAILED`, a wrong key, a UDP round trip — over four combinations (TLS
  and QUIC, Morph on and off), plus a burst of sixteen held flows per carrier:
  sixteen TCP connections over TLS and none over QUIC, from each
  implementation, counted by `ss` at the instant the origin releases them.
  This client now sends the Authenticated Prelude, and still agrees.
- `portal-lifecycle.sh` — passes. A connection that authenticates and sends no
  flow is answered `INVALID_REQUEST` after **5002 ms**, the handshake deadline.
- `quic-probe.sh` — passes. ngtcp2 v1.17.0 over aws-lc v1.68.0 builds for both
  ABIs, exports 32 bytes under `EXPORTER-Nowhere-nw3-Auth`, and a 3.1.0 Portal
  answers READY; a wrong key gets no answer.
- `e2e-fakeip.sh` — passes over TLS and QUIC: 20 MiB by a name only the
  Portal's network can resolve, over IPv4 and a synthetic IPv6 address, and the
  origin hears only from the Portal.
- `e2e-android.sh` — the whole instrumentation suite, **68 tests, none
  failing**, three runs in a row. It installs, pre-grants VPN consent and runs
  with `am instrument` (`connectedAndroidTest` reinstalls and drops the
  grant), beside `quic-origin.py`, the origin and UDP echo the QUIC cases dial.
- `e2e-tunnel-fetch.sh` — not re-run at v3.1.0; it last passed on 2026-10-21 at
  v3.0.1, over TLS and QUIC.

## Device side

End-to-end instrumentation tests need an Android device. **Which device is the
developer's own choice** — a physical phone, an AVD, or any third-party emulator.
This suite does not prescribe one.

`scripts/device-connect.sh` discovers a device on a best-effort basis, in this
order: already-connected device → a running local emulator → an AVD. If nothing
is found it prints the available options.

```bash
DEVICE="$(scripts/device-connect.sh)"
adb -s "$DEVICE" shell ...
```

`scripts/emulator-setup.sh` is only needed if you want to use an AVD; it downloads
roughly 1–2 GB of system image. Skip it if you already have a working device.

### Requirements the device must satisfy

| Item | Requirement | Why |
|---|---|---|
| API level | ≥ 26 | `minSdk`. The TLS exporter is Conscrypt's on every level since v3 (ADR-0003) |
| ABI | must match the client's `abiFilters` | local devices are commonly arm64-v8a; x86 CI runners need x86_64 — keep both, for different reasons |
| `/dev/tun` | must exist | prerequisite for `VpnService`; present on most images, missing on some minimal ones |
| VPN consent | must be pre-granted | `adb shell appops set <pkg> ACTIVATE_VPN allow`, otherwise instrumentation hangs on the consent dialog |

### Host reachability

The scripts reach the host-side Portal at `10.0.2.2`, the standard convention for
QEMU-based emulators including the AOSP emulator. With a physical device, or an
emulator using bridged networking, use the host's LAN address instead — the
scripts accept an override through the environment.

`e2e-fakeip.sh` has been run end to end and passes; it installs the app, brings
up a real TUN and moves 20 MB through it over each carrier. `e2e-android.sh`
runs the instrumentation suite against a host Portal.

### Instrumentation arguments

A Portal on a generated certificate needs its pin, and v3 needs both ends to
agree on Morph:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.nowhereE2ePortal=10.0.2.2:22095 \
  -Pandroid.testInstrumentationRunnerArguments.nowhereE2eKey=<32 hex> \
  -Pandroid.testInstrumentationRunnerArguments.nowhereE2ePin=<the Portal's fingerprint> \
  -Pandroid.testInstrumentationRunnerArguments.nowhereE2eMorph=1
```

The Portal logs its fingerprint on its first line. The QUIC cases expect an
HTTP origin at `127.0.0.1:28091` and a UDP echo at `127.0.0.1:28092` *as the
Portal sees them*.

### Why `e2e-fakeip.sh` uses Docker

It has to prove that a **name** reached the Portal, and that is only provable
with a name the device cannot resolve for itself: a name both ends know would
still fetch with the whole fake-IP layer removed. Docker gives the Portal and
the origin server a private namespace, so the name resolves where the Portal
runs and nowhere else. The alternative was editing `/etc/hosts` on somebody's
machine or depending on a public wildcard DNS service.

## Why the vector file verifies itself

`verify-vectors.py` does not reference any Nowhere code. It re-implements the
encodings from the prose in `docs/protocol.md` and compares against the expected
values transcribed into the JSON. So it checks three things at once: whether the
spec says what we think it says, whether we understood it, and whether the
transcription has a typo.

This matters most for the derivations. Authentication is a three-step HMAC
chain with a hashed salt, and Morph is a two-step chain whose root label is the
HMAC key itself, unhashed — described side by side, and easy to transcribe one
from the other. All three sources currently agree byte for byte.

## Mapping to the PRD

| Suite content | PRD requirement |
|---|---|
| `verify-vectors.py` + vector file | NW-Q-02 byte-level KAT |
| the "property/fuzz" rows in the matrix | NW-Q-03 decoder fuzzing |
| `smoke-local.sh` | NW-Q-04 the reference implementation talking to itself, as a health check |
| `oracle-diff.sh` + `oracle-cases.py` | NW-Q-04 the dual-implementation comparison itself |
| `e2e-android.sh`, `e2e-fakeip.sh` | NW-Q-05 end-to-end instrumentation tests |
| `drift-check.sh` + `PROTOCOL_BASELINE` | NW-Q-08 drift detection, architecture decision D4 |

## Notes

- Values such as `sharedKeyUtf8: "0123456789abcdef0123456789abcdef"` come from
  upstream tests. They are public test data, not credentials. **No real key,
  token, or node address may ever enter this directory.**
- `oracle-diff.sh` compares a SOCKS5 reply code, because that is the only view
  the oracle has of a rejection: its front end maps the Portal's `SetupResult`
  onto one. This implementation is put through the same mapping so that both
  speak one alphabet, and each side's untranslated reason is printed beside the
  verdict — which is where a difference the alphabet cannot express shows up.
- Every node in this suite is pinned to the fingerprint its Portal logged. v3
  has no unverified mode, so a test Portal on a generated certificate is
  reachable no other way — and a pin neither side produced is the strongest
  verification a test can ask for.
