# Somewhere for Android

A native Android client for the [Nowhere](https://github.com/NodePassProject/Nowhere)
protocol, written in Kotlin.

**Nowhere protocol only.** No VLESS, Trojan, Shadowsocks or Naive. The protocol
engine is hand-written in Kotlin rather than wrapping an existing core.

## Status

Pre-release, and **not published anywhere**. The client speaks **Nowhere v3**
(`nw3`) and nothing older: a share link `nowhere://key@host:port` — TLS, Morph
on, the v3 defaults — or the same with `?net=udp` for QUIC, connects and carries
traffic.

| | |
|---|---|
| Protocol | v3 authentication with role keys, the one-byte FlowStart, the five setup outcomes, UDP over a stream, stream-derived QUIC flow ids, UDP over DATAGRAM with fragmentation |
| Morph | The v3 wire mask, on by default: ChaCha20 under TLS with the `fade` prelude, and per datagram under QUIC |
| Carriers | TLS (one connection per flow) and QUIC (one connection for all of them), each with Morph on and off, compared case by case against the reference implementation, which agrees on every one |
| Certificates | Verified on both carriers, always: a pin, or this device's trust store and the hostname. v3 has no unverified mode |
| Tunnel | `VpnService` + lwIP, **both address families**, TCP with back-pressure, UDP, DNS interception with fake-IP over IPv4 and IPv6, live throughput |
| Routing | A rule matcher, an import path, a bundled network-structural rule set for both families, a direct path, a reject path |
| Per-app | A real installed list, a persisted selection, and this client structurally outside its own tunnel |
| Nodes | Stored, imported from a `nowhere://` link, probed for reachability, and failed over when one cannot be reached |
| Subscription | Fetching and parsing with upstream's actual quota semantics, and a periodic refresh that is off until asked for |
| UI | Nine screens, both themes, three locales, and a quick-settings tile |

**What is not done**, stated as plainly as the rest:

- **Nothing has ever run on physical hardware.** Every device result is from an
  emulator. Private DNS over DoT, IPv6-only and NAT64 networks, a real Wi-Fi to
  cellular change, Doze and a path MTU below 1500 are all things an emulator
  cannot represent, and all things a VPN meets on the first real device.
- **Start on boot is deliberately absent.** Android's always-on VPN starts the
  tunnel before applications run and can block traffic while it is down; an
  in-app boot receiver would be a worse version of it. Settings links to the
  system switch instead.
- **Verification by name has only been seen to refuse on a device.** Accepting a
  chain this device trusts has been exercised on a JVM and against a live Portal
  with a test anchor, not on Android, because that needs a CA whose key a test
  can sign with. See [ADR-0002](docs/adr-0002-quic-certificate-verification.md).
- **The dashboard does not emit v3 links yet.** NowhereDash targets Nowhere
  2.1.1; its `up`/`down` links are read as `net` where the two agree, and its
  carrier-path form is refused with a reason.
- **No release has been published**, and no signing key exists.

## Roadmap

Delivery is layered, and each layer is a working client rather than a stage of
one: it ships to internal testing and leaves a window to fix what testing finds.
There is no big-bang release.

| Stage | What it delivers | State |
|---|---|---|
| **M0** · foundations | Public repository, CI, the quality gates, and the conformance suite the later layers are judged by | **Done**, 2026-08-24 |
| **L1** · TLS over TCP | Authentication, flow setup, Target, the setup outcomes, the dedicated lane, UDP over a stream — and, pulled forward because a client nobody can feed nodes to cannot be tested, the TUN, DNS with fake-IP, node storage and subscription import | **Done**, 2026-08-27, against v1.8 |
| **L2** · TLS Mux | The Mux carrier at upstream's own shard density | **Done**, 2026-08-27 — and **removed** with v3, which deleted Mux |
| **L3** · QUIC | ngtcp2 over aws-lc, authentication, TCP over reliable streams, UDP over DATAGRAM, keep-alive, every carrier compared against the reference implementation | **Done**, 2026-08-29, against v1.8 |
| **Release tail** | A bundled rule set, a connection log that shows what the Portal actually said, a launcher icon, a release build that has been run rather than only produced, and the documents a VPN-class listing requires | **Done**, 2026-08-29 |
| **L4** · product surface | IPv6 end to end, `pin` on the QUIC carrier, node health and failover, a scheduled subscription refresh and a quick-settings tile | **Done**, 2026-08-29 |
| **v3** · following upstream | Nowhere v3.0.1: the new authentication, FlowStart and setup outcomes; Mux and split carriers removed; stream-derived QUIC flow ids; Morph on both carriers; chain verification by name on QUIC; every conformance claim re-established against the v3 oracle and on an emulator | **Done**, 2026-10-21 |
| **Device pass** | The first run on physical hardware: Private DNS, a real per-app list, a Wi-Fi to cellular change, Doze, a path MTU below 1500 | **Not started.** No physical device has ever been attached to this project |
| **L5** · what the device pass asks for | Whatever a real phone turns out to need | **Not started**, deliberately: scope fixed after a device pass, not before |
| **Publication** | A signing key, a distribution channel, a first release | **Waiting on decisions rather than on code** |

### How much of it is done

[`conformance/cases/coverage.md`](conformance/cases/coverage.md) is the
measure. It carries one row per specification obligation, and every row either
names the test that covers it or says why it has none — so these counts can be
re-derived by reading it rather than taken on trust. They were wrong once,
carried forward through two runs by hand, and that is why they are now counted
from the table.

| Rows | Covered by a test | Manual, with the reason | Partly, with the gap named | Blocked |
|---|---|---|---|---|
| 89 | 88 | 1 | 2 | 0 |

The two partial rows are each half of a pair: this client's half is tested, and
the other half needs something no repository should contain — a dashboard that
emits v3 links, and a CA key a test could sign with.

**The protocol is finished and the product is not.** Between this repository and
a release stand a physical device and a signing key that will never live here.

## Protocol baseline

Pinned in [`conformance/PROTOCOL_BASELINE`](conformance/PROTOCOL_BASELINE):
Nowhere **v3.0.1** (`00000e0`). Every protocol implementation detail is
traceable to a specific section of the upstream `docs/protocol.md`. v3 is not
wire-compatible with v1: a v3 client cannot reach a v1 Portal, or the reverse.

## Conformance suite

[`conformance/`](conformance/) holds byte-level test vectors, a self-verifying
checker, an end-to-end smoke test and upstream drift detection. It is published
deliberately: third-party implementations need it to align, dashboard and Portal
interop checks need it, and community bug reports need it to be meaningful.

```sh
python3 conformance/scripts/verify-vectors.py      # 86 known-answer checks
NOWHERE_CLONE=/path/to/Nowhere conformance/scripts/drift-check.sh
conformance/scripts/oracle-diff.sh                 # this client against upstream's own
```

`oracle-diff.sh` is the one worth running. It puts the same case list through
this implementation and the reference client, over both carriers with Morph on
and off, and diffs the outcomes. Against v1 it found a rejection that one
carrier reported in a form a caller could not read; on its first v3 run it found
a refused AuthFrame over QUIC reported as a transport failure, where the oracle
and the TLS carrier both read it as no answer.

The suite makes no assumption about where an upstream clone lives; point
`NOWHERE_CLONE` at one.

### Tests that need a live Portal

Many tests dial a real Portal rather than a fake, because the failures that
matter most are the ones no fixture predicts. The first run against v3 found the
clearest example: a Portal ends a flow whose first payload arrives before
READY, which no vector and no fake would have shown. They skip cleanly when no
Portal is running, so an environment that cannot run them reports honestly
instead of passing vacuously.

```sh
eval "$(conformance/scripts/portal-for-tests.sh)"
./gradlew testDebugUnitTest
conformance/scripts/portal-for-tests.sh --stop
```

## Design

| Document | Carries |
|---|---|
| [`docs/design-system.md`](docs/design-system.md) | Colour tokens for both themes with measured contrast, the type scale, and the four rules the UI has to keep |
| [`docs/brand.md`](docs/brand.md) | The brand hue, which carries no protocol meaning and is derived rather than picked |
| [`docs/i18n.md`](docs/i18n.md) | The three shipping locales, and the rule that machine identifiers stay English while sentences translate |
| [`docs/adr-0001-tls-exporter.md`](docs/adr-0001-tls-exporter.md) | Where the TLS exporter comes from, and why `minSdk` is 26 |
| [`docs/adr-0002-quic-certificate-verification.md`](docs/adr-0002-quic-certificate-verification.md) | How certificates are verified on the QUIC carrier: `pin` in the handshake, a name against the platform's trust store after it |
| [`docs/adr-0003-morph.md`](docs/adr-0003-morph.md) | How Morph is applied under each carrier, and why TLS is now Conscrypt in engine mode everywhere |
| [`docs/privacy.md`](docs/privacy.md) | What is stored, what leaves the device, and the longer list of what is not collected |
| [`docs/store-policy.md`](docs/store-policy.md) | What a VPN-class listing requires, what is already satisfied, and what is still someone's decision |

[`docs/architecture.md`](docs/architecture.md) covers the layers, which way they
depend, and the rules that keep them apart.
[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the gates and what a change is
expected to carry.

Every contrast figure in those documents is asserted by a test rather than
claimed, because measuring has already caught defects the eye did not.

## Quality gates

```sh
./gradlew ktlintCheck testDebugUnitTest koverVerifyDebug lintDebug \
          checkClasspathConsistency assembleDebug \
          checkNativeLibraries checkNativeBridge checkReleaseArtifact
python3 conformance/scripts/verify-vectors.py
```

The protocol layer carries a 90% line-coverage gate. Every other gate here was
added after something got through: `checkClasspathConsistency` after a module
resolved to different versions on the compile and runtime classpaths and crashed
a screen with everything else green; `checkNativeLibraries` after linking aws-lc
exported 1,684 of the crypto library's own symbols into a process that already
runs a second BoringSSL; `checkNativeBridge` because the QUIC bridge must have no
way to open a socket that escapes `VpnService.protect()`; `checkReleaseArtifact`
because R8 renames the JNI callbacks C resolves by name, and the failure is not a
crash but a tunnel that comes up and answers nothing.

Each was verified to fail before it was trusted. CI runs all of the above on
every pull request, and checks upstream for normative protocol changes daily.

## Build

Requires JDK 21, the Android SDK with platform 36, the NDK pinned in
`app/build.gradle.kts`, and CMake 3.22.1. The QUIC stack additionally needs Go
and perl on the build host — aws-lc generates assembly with perl and its build
tooling with Go — and it is fetched at pinned commits rather than vendored, so a
first build has a network step. It takes about half a minute per ABI and nothing
thereafter.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

A release build works without a keystore and produces an unsigned APK; with one
configured in `local.properties` it is signed. Signing material lives nowhere in
this repository.

```sh
./gradlew assembleRelease
tools/quic/build-deps.sh --bundle-source corresponding-source.tar.gz
```

The second command produces the corresponding source for the statically linked
ngtcp2 and aws-lc. A GPL-3.0 binary that links them must be accompanied by it.

## Security

Somewhere carries user traffic; security defects are the highest-priority class
of issue. **Do not report them in a public issue** — see [SECURITY.md](SECURITY.md)
for the private reporting channel and what is in scope.

Two properties worth stating plainly:

- **Every node's certificate is verified**, by a pin or by this device's trust
  store and the hostname. v3 removed the mode that skipped it; a Portal on its
  generated certificate needs a pin.
- **Morph masks the wire; it does not protect anything.** Upstream says so —
  "wire masking, not authentication or integrity" — and makes no claim that
  Nowhere resists censorship. This client must not imply otherwise.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).

Bundled fonts are OFL-1.1, icon path geometry is ISC, lwIP is BSD-3-Clause,
ngtcp2 is MIT and aws-lc is Apache-2.0 OR ISC; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), which also records what was
deliberately not taken from each.

Copyright (C) 2026 The Somewhere Authors. The lwIP layer and its bridge are
inherited from
[Anywhere-Android](https://github.com/NodePassProject/Anywhere-Android)
(GPL-3.0) and carry their original attribution.
