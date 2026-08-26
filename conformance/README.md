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
scripts/oracle-diff.sh        the same cases through both implementations, outcomes compared
scripts/oracle-cases.py       the oracle's half of that comparison, over raw SOCKS5
scripts/portal-lifecycle.sh   the two cases that need the Portal to stop being there
cases/conformance-matrix.md   test matrix derived from the spec, mapped to PRD IDs
cases/l1-coverage.md          every L1 row and the test that covers it, or why it has none
```

## What you can run right now

```bash
# 1) Self-verifying vectors: spec document / this suite / upstream Rust tests
python3 scripts/verify-vectors.py

# 2) End-to-end smoke test (build the binary first: cargo build --release --locked)
scripts/smoke-local.sh

# 3) Upstream drift detection
scripts/drift-check.sh

# 4) This implementation against the reference one, case by case
scripts/oracle-diff.sh

# 5) Remote resolution on a device (needs Docker and one Android device)
scripts/e2e-fakeip.sh
```

**Verified on 2026-08-26, at the baseline this file pins:**

- `verify-vectors.py` — 45 checks pass, baseline v1.8.2 @ 8807960c.
- `smoke-local.sh` — passes. Traffic really traverses
  SOCKS5 → Vector → Nowhere (tcp/tcp) → Portal → target.
- `drift-check.sh` — passes. It has caught two real upstream events so far: the
  disappearance of the `feat/mux` branch on the day v1.8.0 was released, and the
  normative change that moved this pin to v1.8.2.
- `cargo build --release --locked` succeeds on macOS/aarch64, which confirms the
  plan of building the Rust implementation only as a host-side oracle.
- `oracle-diff.sh` — passes. Five cases, both implementations, identical
  outcomes: payload by address, payload by name, `DIAL_FAILED`, a wrong key, and
  a UDP-over-stream round trip. Verified to fail: encoding a domain target with
  the IPv4 ATYP diverges on one case and the script exits non-zero.
- `e2e-fakeip.sh` — passes on a local emulator (API 32, arm64-v8a). Verified to
  fail: bypassing the DNS interceptor turns it red with an unknown host.
- `portal-lifecycle.sh` — passes. The Portal reclaimed a connection that
  authenticated and then said nothing after **40004 ms**, which is NW-P-11's
  forty seconds observed rather than transcribed; and a session kept working
  across a Portal kill and restart with nothing asked of the user.

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
| API level | **≥ 31** to exercise the platform exporter path | `android.net.ssl.SSLSockets.exportKeyingMaterial()` is public API only from API 31 |
| ABI | must match the client's `abiFilters` | local devices are commonly arm64-v8a; x86 CI runners need x86_64 — keep both, for different reasons |
| `/dev/tun` | must exist | prerequisite for `VpnService`; present on most images, missing on some minimal ones |
| VPN consent | must be pre-granted | `adb shell appops set <pkg> ACTIVATE_VPN allow`, otherwise instrumentation hangs on the consent dialog |

### Host reachability

The scripts reach the host-side Portal at `10.0.2.2`, the standard convention for
QEMU-based emulators including the AOSP emulator. With a physical device, or an
emulator using bridged networking, use the host's LAN address instead — the
scripts accept an override through the environment.

`e2e-fakeip.sh` has been run end to end and passes; it installs the app, brings
up a real TUN and moves 20 MB through it. `e2e-android.sh` predates the client
project and has still not been run whole — the two now overlap, and the
device-side path that is actually exercised is `e2e-fakeip.sh`.

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

This matters most for authentication: the `authKey` derivation is a three-step
HMAC chain, the document describes it in HKDF terms while the implementation is
plain HMAC, and it is easy to get subtly wrong. All three currently agree
byte for byte.

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

- Values such as `sharedKeyUtf8: "secret"` come from upstream tests. They are
  public test data, not credentials. **No real key, token, or node address may
  ever enter this directory.**
- `oracle-diff.sh` compares a SOCKS5 reply code, because that is the only view
  the oracle has of a rejection: its front end maps the Portal's `SetupResult`
  onto one. This implementation is put through the same mapping so that both
  speak one alphabet, and each side's untranslated reason is printed beside the
  verdict — which is where a difference the alphabet cannot express shows up.
  Both reach reply 1 for a wrong key, one by observing silence and one by timing
  out after five seconds, and only those two lines say so.
- `smoke-local.sh` deliberately omits `sni` and `pin`. That is exactly the shape
  NowhereDash currently generates, i.e. the configuration for which upstream skips
  certificate verification entirely. The omission is an intentional reference
  case, not an oversight — see decision D-11.
