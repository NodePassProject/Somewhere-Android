# Somewhere for Android

A native Android client for the [Nowhere](https://github.com/NodePassProject/Nowhere)
protocol, written in Kotlin.

**Nowhere protocol only.** No VLESS, Trojan, Shadowsocks or Naive. The protocol
engine is hand-written in Kotlin rather than wrapping an existing core.

## Status

Pre-release, and **not yet usable as a VPN**: there is no `VpnService` and no TUN
layer, so nothing routes device traffic. What exists:

| | |
|---|---|
| Protocol, L1 | Authentication, flow setup, the dedicated TLS lane, the seven setup outcomes. Verified against a live upstream Portal, not only against fixtures |
| Transport | TLS dialing with all three certificate modes — skipped, `sni`, `pin` — each tested against a live Portal |
| Nodes | Stored on device, imported from a `nowhere://` link, probed for reachability |
| Subscription | Fetching and parsing, including the quota semantics upstream actually has |
| UI | All eight screens, both themes, three locales |
| Not yet | TUN / lwIP, `VpnService`, Mux (L2), QUIC (L3) |

Delivery is layered: L1 TLS/TCP, then L2 Mux, then L3 QUIC. Each layer ships to
internal testing before the next one starts.

## Protocol baseline

Pinned in [`conformance/PROTOCOL_BASELINE`](conformance/PROTOCOL_BASELINE):
Nowhere **v1.8.0**. Every protocol implementation detail is traceable to a
specific section of the upstream `docs/protocol.md`.

## Conformance suite

[`conformance/`](conformance/) holds byte-level test vectors, a self-verifying
checker, an end-to-end smoke test and upstream drift detection. It is published
deliberately: third-party implementations need it to align, dashboard and Portal
interop checks need it, and community bug reports need it to be meaningful.

```sh
python3 conformance/scripts/verify-vectors.py      # 43 known-answer checks
NOWHERE_CLONE=/path/to/Nowhere conformance/scripts/drift-check.sh
```

The suite makes no assumption about where an upstream clone lives; point
`NOWHERE_CLONE` at one.

### Tests that need a live Portal

Sixteen tests dial a real Portal rather than a fake, because the failures that
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

[`docs/architecture.md`](docs/architecture.md) covers the layers, which way they
depend, and the rules that keep them apart.
[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the gates and what a change is
expected to carry.

Every contrast figure in those documents is asserted by a test rather than
claimed, because measuring has already caught defects the eye did not.

## Quality gates

```sh
./gradlew ktlintCheck testDebugUnitTest koverVerifyDebug lintDebug \
          checkClasspathConsistency assembleDebug
python3 conformance/scripts/verify-vectors.py
```

The protocol layer carries a 90% line-coverage gate. `checkClasspathConsistency`
fails when a module resolves to different versions on the compile and runtime
classpaths — added after exactly that shipped a crash with every other gate
green. CI runs all of the above on every pull request, and checks upstream for
normative protocol changes daily.

## Build

Requires JDK 21 and the Android SDK with platform 36. NDK and CMake become
requirements once the native TUN layer is inherited.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

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

Bundled fonts are OFL-1.1 and icon path geometry is ISC; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Copyright (C) 2026 The Somewhere Authors. Portions inherited from
[Anywhere-Android](https://github.com/NodePassProject/Anywhere-Android) (GPL-3.0)
will carry their original attribution when they land.
