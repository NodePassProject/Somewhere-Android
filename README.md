# Somewhere for Android

A native Android client for the [Nowhere](https://github.com/NodePassProject/Nowhere)
protocol, written in Kotlin.

**Nowhere protocol only.** No VLESS, Trojan, Shadowsocks or Naive. The protocol
engine is hand-written in Kotlin rather than wrapping an existing core.

## Status

Pre-release, and **not published anywhere**. The protocol is complete: L1, L2 and
L3 all work against a live upstream Portal, and a node URL with no parameters —
which is a QUIC node, because the specification's default for both directions is
`udp` — connects and carries traffic.

| | |
|---|---|
| Protocol, L1 | Authentication, flow setup, the dedicated TLS lane, the seven setup outcomes |
| Protocol, L2 | TLS Mux. Sixteen concurrent flows measured over four connections, at upstream's stated shard density |
| Protocol, L3 | QUIC: authentication, TCP flows over reliable streams, UDP over DATAGRAM with fragmentation, split flows across two carriers |
| Carriers | All four combinations — `tcp/tcp`, `udp/udp`, `udp/tcp`, `tcp/udp` — compared case by case against the reference implementation, which agrees on every one |
| Tunnel | `VpnService` + lwIP, TCP with back-pressure, UDP, DNS interception with fake-IP, live throughput |
| Routing | A rule matcher, an import path, a bundled network-structural rule set, a direct path, a reject path |
| Per-app | A real installed list, a persisted selection, and this client structurally outside its own tunnel |
| Nodes | Stored, imported from a `nowhere://` link, probed for reachability |
| Subscription | Fetching and parsing, with upstream's actual quota semantics |
| UI | Nine screens, both themes, three locales |

**What is not done**, stated as plainly as the rest:

- **Nothing has ever run on physical hardware.** Every device result is from an
  emulator. Private DNS over DoT, IPv6-only and NAT64 networks, a real Wi-Fi to
  cellular change, Doze and a path MTU below 1500 are all things an emulator
  cannot represent, and all things a VPN meets on the first real device.
- **The QUIC carrier does no certificate verification.** A node carrying `sni`
  or `pin` is refused rather than carried without it. The TLS carrier implements
  both.
- **No release has been published**, and no signing key exists.

## Roadmap

Delivery is layered, and each layer is a working client rather than a stage of
one: it ships to internal testing and leaves a window to fix what testing finds.
There is no big-bang release.

| Stage | What it delivers | State |
|---|---|---|
| **M0** · foundations | Public repository, CI, the quality gates, and the conformance suite the later layers are judged by | **Done**, 2026-08-24 |
| **L1** · TLS over TCP | Authentication, FlowHeader, Target, the seven setup outcomes, the dedicated lane, UDP over a stream — and, pulled forward because a client nobody can feed nodes to cannot be tested, the TUN, DNS with fake-IP, node storage and subscription import | **Done**, 2026-08-27 |
| **L2** · TLS Mux | The Mux carrier at upstream's own shard density, and concurrency measured over it | **Done**, 2026-08-27 |
| **L3** · QUIC | ngtcp2 over aws-lc, authentication, TCP over reliable streams, UDP over DATAGRAM, split flows across two carriers, keep-alive, and every carrier combination compared against the reference implementation | **Done**, 2026-08-29 |
| **Release tail** | A bundled rule set, a connection log that shows what the Portal actually said, a launcher icon, a release build that has been run rather than only produced, and the documents a VPN-class listing requires | **Done**, 2026-08-29 |
| **Device pass** | The first run on physical hardware: Private DNS, a real per-app list, a Wi-Fi to cellular change, Doze, a path MTU below 1500 | **Not started.** No physical device has ever been attached to this project |
| **L4** · product surface | Subscription refresh, latency testing, encrypted DNS. Scope deliberately not fixed before a device pass says what the product actually needs | **Not started** |
| **Publication** | A signing key, a distribution channel, a first release | **Waiting on decisions rather than on code** |

### How much of it is done

[`conformance/cases/l1-coverage.md`](conformance/cases/l1-coverage.md) is the
measure. It carries one row per specification obligation, and every row either
names the test that covers it or says what it is waiting for — so these counts
can be re-derived by reading it rather than taken on trust. They were wrong once,
carried forward through two runs by hand, and that is why they are now counted
from the table.

| Layer | Rows | Covered by a test | Partly, with the gap named | Blocked |
|---|---|---|---|---|
| L1 | 63 | 62 | 1 | 0 |
| L2 | 21 | 20 | 1 | 0 |
| L3 | 27 | 25 | 2 | 0 |
| **Total** | **111** | **107** | **4** | **0** |

The four partial rows are each half of a pair: this client's half is tested, and
the other half needs something no repository can contain — a running dashboard, a
Portal that refuses stream credit, a phone.

**The protocol is finished and the product is not.** Between this repository and
a release stand a physical device, a decision about certificate verification on
the QUIC carrier, and a signing key that will never live here.

## Protocol baseline

Pinned in [`conformance/PROTOCOL_BASELINE`](conformance/PROTOCOL_BASELINE):
Nowhere **v1.8.2**. Every protocol implementation detail is traceable to a
specific section of the upstream `docs/protocol.md`.

## Conformance suite

[`conformance/`](conformance/) holds byte-level test vectors, a self-verifying
checker, an end-to-end smoke test and upstream drift detection. It is published
deliberately: third-party implementations need it to align, dashboard and Portal
interop checks need it, and community bug reports need it to be meaningful.

```sh
python3 conformance/scripts/verify-vectors.py      # 45 known-answer checks
NOWHERE_CLONE=/path/to/Nowhere conformance/scripts/drift-check.sh
conformance/scripts/oracle-diff.sh                 # this client against upstream's own
```

`oracle-diff.sh` is the one worth running. It puts the same case list through
this implementation and the reference client, over every carrier combination
both support, and diffs the outcomes. Its only finding to date was a protocol
fact that had grown a second shape the moment a second carrier landed — invisible
to every test that asserted on one carrier's own types, and about to degrade
seven distinct rejection messages to a generic failure on a user's screen.

The suite makes no assumption about where an upstream clone lives; point
`NOWHERE_CLONE` at one.

### Tests that need a live Portal

Many tests dial a real Portal rather than a fake, because the failures that
matter most are the ones no fixture predicts — a rejected authentication frame
is met with **silence** rather than a close, which a fake returning EOF will
never teach you. They skip cleanly when no Portal is running, so an environment
that cannot run them reports honestly instead of passing vacuously.

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
| [`docs/adr-0001-tls-exporter.md`](docs/adr-0001-tls-exporter.md) | Why the TLS exporter has two sources, and why `minSdk` is 26 |
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

- **A node with neither `sni` nor `pin` gets no certificate verification**, which
  is what upstream does and what every URL current dashboards emit produces. The
  client states this persistently rather than hiding or refusing it.
- **Nowhere is not an anti-censorship protocol and does not claim to be.** This
  client must not imply otherwise.

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
