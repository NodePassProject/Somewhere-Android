# Somewhere for Android

A native Android client for the [Nowhere](https://github.com/NodePassProject/Nowhere)
protocol, written in Kotlin.

**Nowhere protocol only.** No VLESS, Trojan, Shadowsocks or Naive. The protocol
engine is hand-written in Kotlin rather than wrapping an existing core.

## Status

Pre-release. The M0 skeleton is in place; the protocol engine is not written yet.
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

## Build

Requires JDK 21, Android SDK with platform 36, and — from L1 onward, once the
native TUN layer is inherited — NDK and CMake.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

## Security

Somewhere carries user traffic; security defects are the highest-priority class
of issue. **Do not report them in a public issue** — see [SECURITY.md](SECURITY.md)
for the private reporting channel and what is in scope.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).

Copyright (C) 2026 The Somewhere Authors. Portions inherited from
[Anywhere-Android](https://github.com/NodePassProject/Anywhere-Android) (GPL-3.0)
will carry their original attribution when they land.
