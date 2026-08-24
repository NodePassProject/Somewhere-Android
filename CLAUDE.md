# CLAUDE.md

Somewhere for Android — a native Kotlin client for the Nowhere protocol.

## Language policy

This is a global collaboration project. **All project output is in English**:
documentation, code comments, commit messages, script output, `.gitignore`
comments, everything. Do not emit Chinese in project files.

## Scope

- **Nowhere protocol only.** No VLESS, Trojan, Shadowsocks, Naive, and so on.
- **Engine hand-written in Kotlin.** The upstream Rust implementation is built
  only on a development host as a conformance oracle; it does not ship and is not
  cross-compiled for Android.
- **Delivered in layers**: L1 TLS/TCP → L2 Mux → L3 QUIC. Each layer ships to
  internal testing and leaves a fix-back window. No big-bang release.

## Layout

| Path | Purpose |
|---|---|
| `app/` | The client. Kotlin, Compose, `minSdk` 26, `compileSdk` 36 |
| `conformance/` | Byte-level vectors, self-verifying checker, end-to-end smoke test, drift detection, test matrix |
| `docs/` | Architecture, the design system, i18n, brand, and the TLS exporter ADR |

The lwIP / TUN / routing layer is inherited from `NodePassProject/Anywhere-Android`
(GPL-3.0) at L1; it is not present yet.

## Conventions

- Every protocol implementation detail must be traceable to a specific section of
  the upstream `docs/protocol.md`.
- New protocol code lands with a matching known-answer or behavioural case.
  Protocol-layer coverage gate: 90%.
- Keep `conformance/PROTOCOL_BASELINE` current: upstream commit plus the KAT
  snapshot it corresponds to. A scheduled CI job diffs upstream and opens an issue
  on normative change.
- Run `conformance/scripts/drift-check.sh` before touching protocol code.
- **Never hardcode deployment-specific protocol parameters** — ALPN values,
  padding curves, jitter parameters, key derivation labels or salts. The client
  implements mechanisms and embeds no values; parameters are delivered by the
  server at runtime.

## Quality gates

They land in M0, before any protocol code, so correctness is enforced by
automation from the first protocol commit rather than retrofitted later.

```sh
./gradlew ktlintCheck testDebugUnitTest koverVerifyDebug lintDebug \
          checkClasspathConsistency assembleDebug
python3 conformance/scripts/verify-vectors.py
```

| Gate | What it enforces |
|---|---|
| `ktlintCheck` | Style. `@Composable` PascalCase is exempted through ktlint's own `.editorconfig` switch, not suppressed |
| `koverVerifyDebug` | **90% line coverage on `eu.nodepass.somewhere.protocol.*` and `.subscription.*`.** Kover 0.9 filters only at report level, so the report *is* the protocol layer — deliberately, because one number applied to the whole app just gets gamed with trivial tests |
| `lintDebug` | Android lint |
| `checkClasspathConsistency` | **Compile and runtime resolving different versions of the same module.** Added after `FlowRow` compiled against `foundation-layout` 1.7.2 and shipped against 1.9.2: the signature differed, so the screen crashed with `NoSuchMethodError` the first time it was opened, with the build, ktlint, lint and 253 tests all green. Re-running the gate against that BOM reports **29** skewed modules, not one — the crash we hit was one of many latent ones |
| `verify-vectors.py` | 43 known-answer checks, recomputed from the spec prose. References no upstream code and needs no clone |
| `drift-check.sh` | Upstream normative change. Runs daily in CI and opens an issue; see `conformance/PROTOCOL_BASELINE` for why the baseline is held rather than advanced |

Both gates that can silently pass were verified to actually fail. The coverage
gate: a protocol class with no tests produces `lines covered percentage is
0.000000, but expected minimum is 90`. The classpath gate: reverting the Compose
BOM to `2024.09.00` reports 29 modules skewed, including the
`foundation-layout: compiled against 1.7.2, packaged 1.9.2` line that names the
crash.

New protocol code lands with a matching known-answer or behavioural case.

## Settled positions that shape the code

- **Shared key, no per-user identity** (D-07). Per-user identity is a *Portal*
  decision, not a client one: the Portal holds one `auth_key`, and the 32-byte
  authentication frame is full — 16 bytes of session id, 16 of tag, no room for a
  user identifier. When it is eventually wanted, the cheap implementation gives
  each user a distinct shared key and has the Portal hold a key set; that changes
  nothing on the wire and nothing in this client.
- **Faithful downstream** (D-08). This client follows upstream and stays
  byte-compatible with it. The L1 acceptance criterion is "byte-identical to the
  oracle". Following does not mean tracking automatically: the baseline is pinned
  and drift is flagged for a human.
- **No obfuscation layer.** It requires controlling both ends, and this project
  does not run Portals. Nowhere does not claim to resist censorship either, so the
  client must not imply that it does.
- **Capability negotiation is sent from day one** (`type`, `ver`, `caps` on the
  subscription request). It cannot be retrofitted onto installed clients, costs
  nothing today because dashboards ignore unknown parameters, and is the intended
  channel for server-delivered parameters (V-05).

## Design and language

Two documents in `docs/` carry the UI decisions. Both exist because nothing
crashes when they are violated:

- [`docs/design-system.md`](docs/design-system.md) — colour tokens for both
  themes with measured contrast, type, controls, and the four rules the UI has to
  keep. Copy values from there rather than from a mockup.
- [`docs/i18n.md`](docs/i18n.md) — English, Simplified and Traditional Chinese,
  matched by script (`values-b+zh+Hans`) rather than by region.
- [`docs/brand.md`](docs/brand.md) — the brand hue, which carries no protocol
  meaning and is derived rather than chosen: 280° is what remains once the five
  meaning-bearing hues each claim 60° of separation. Nothing in the app reads it
  yet; see D-13.

Two rules from those documents that reach into protocol code:

- **Machine identifiers stay English; sentences translate.** The seven
  `SetupResult` names, the configuration parameters (`up`, `down`, `mux`, `alpn`,
  `sni`, `pin`) and carrier names are values, not prose. A user pasting
  `DIAL_FAILED` into an issue must match the specification, the source, and
  someone else's log from another locale.
- **Direction colour is not decoration.** Upstream and downstream are never the
  same colour anywhere in the app, and Material You dynamic colour must not touch
  them — a wallpaper that made both channels the same hue would erase the one
  thing the home screen exists to show.

## Facts that are easy to get wrong (all verified on the ground)

1. **The client import scheme is `nowhere://`, not `vector://`.**
   Form: `nowhere://<pct-encoded shared key>@<host>:<port>?up=..&down=..[&mux=1][&alpn=..]#<name>`
   `vector://` is the command URL used to start the upstream Rust process — a
   different thing.
2. **The `pool` parameter was removed in 1.8**, replaced by `mux=0|1` (default 0).
   NowhereDash still emits 1.7-era URLs and appends `pool=5` for `tcp/tcp`. The
   client must ignore unknown and deprecated parameters.
3. **The TLS exporter has two sources, and `minSdk` is 26.**
   `android.net.ssl.SSLSockets.exportKeyingMaterial()` is **public API from API 31**
   (verified by `javap`: absent at 28, class-without-method at 29). Below 31,
   Conscrypt provides the same call. Both sit behind `KeyingMaterialExporter`;
   see `docs/adr-0001-tls-exporter.md`. The class `SSLSessions` does not exist.
   **Neither helps for QUIC** — ngtcp2 needs a C-level TLS backend supplying
   crypto callbacks.
4. **The donor's hand-written TLS has no exporter.** Its
   `vpn/protocol/tls/` package is 4,159 lines across 7 files and was written for
   custom ClientHello construction; searching it for `exporter` /
   `exportKeyingMaterial` returns zero hits. Porting it does not give you the one
   thing Nowhere's authentication needs.
5. **`Anywhere-Android/app/src/main/jni/blake3/CTLSKeyDerivation.c` and `CX25519.c`
   are dead Apple code** — they `#include <CommonCrypto/...>` and are not in the
   `CMakeLists.txt` source list. Do not port them.
6. **If porting the donor's TLS layer**: an exporter must go inside
   `TlsClient.finishHandshake()`, before `clearHandshakeState()`. That method
   nulls `handshakeTranscript`, `handshakeSecret` and `keyDerivation`, and
   `deriveApplicationKeys` discards the master secret as soon as it is computed.
7. **Authentication and FlowHeader are bit-identical between 1.7 and 1.8.**
   The fixed vector `derive_auth_key("secret")` →
   `1076221669fa28bcf70aa8545bddd6f760dcefbe279c3f38a5ff5d925708f867` holds, and
   is transcribed into the conformance suite.
8. **Neither `sni` nor `pin` means upstream skips certificate verification
   entirely.** With `pin` alone, fingerprint verification still happens and takes
   priority over the sni chain path. NowhereDash currently emits neither.
9. **NowhereDash meters at Portal granularity, not per user.** It observes the
   Portal's total traffic counter and attributes the delta to every subscription
   linked to that Portal. The protocol has no user identity.
10. **Nowhere is not an anti-censorship protocol, and does not claim to be.** The
   words obfuscation, censorship, active probing and DPI appear zero times in the
   upstream `docs/security.md` and `docs/protocol.md`.
11. **`no_application_protocol` is not a string you can match on.** A Portal
    refusing an ALPN aborts the handshake, and Conscrypt surfaces that as
    `SSLProtocolException: Read error: ... Failure in SSL library, usually a
    protocol error` — no alert code, no mention of ALPN. The phrase `TLS alert,
    no application protocol` is what **`openssl s_client`** prints for the same
    event. A client that matched on it would name the failure correctly at a
    shell prompt and never on a device. `NowhereDialer` therefore attaches the
    requested ALPN to the generic handshake failure rather than claiming to
    have identified it, and reserves `AlpnRejected` for the case that *is*
    observable: a handshake that completed carrying the wrong protocol.
12. **Keep both ABIs, for different reasons**: physical devices and local
    emulators commonly need `arm64-v8a`; AVDs on x86 CI runners need `x86_64`.
    The donor project ships only the former.

## Already verified — do not redo

- The donor project `Anywhere-Android` **builds clean** on this toolchain
  (JDK 21 / android-36 / NDK 28.2 / CMake 3.22.1), 1m50s cold, one harmless lwIP
  macro warning. Inheriting its shell is not a risk.
- `cargo build --release --locked` succeeds on macOS/aarch64 at Nowhere v1.8.0.
- **End-to-end works**: SOCKS5 → Vector → Nowhere (tcp/tcp) → Portal → target,
  payload returned intact. Re-run `conformance/scripts/smoke-local.sh`.
- **Authentication derivation agrees three ways** — spec prose, an independent
  implementation, and upstream Rust fixed vectors, byte for byte.
  Re-run `python3 conformance/scripts/verify-vectors.py` (43 checks).
- Sending a TLS handshake to a Portal that ends at `TLS alert, no application
  protocol` is **correct** — the Portal rejecting an ALPN other than `now/1` —
  and is usable as a connectivity check. That wording is `openssl s_client`'s;
  see fact 11 for what the same event looks like from Android, which is not the
  same thing at all.
- **The dialer's certificate handling is verified against a live Portal**, pins
  included: `NowhereDialerAgainstPortalTest` reads the Portal's real leaf
  fingerprint over an independent connection and hands it back as `pin=`, so a
  passing pin test cannot be the dialer agreeing with itself. Re-run with a
  Portal from `conformance/scripts/portal-for-tests.sh`.
