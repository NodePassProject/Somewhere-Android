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
- **All three layers are delivered** (L1 and L2 on 2026-08-27, L3 on 2026-08-29),
  together with the release tail. A bare `nowhere://key@host:port` — a QUIC node,
  because the specification defaults both directions to `udp` — connects and
  carries traffic, and the oracle differential agrees on every case over every
  carrier combination. What has *not* happened is a physical device: every device
  result in this repository is from an emulator. The README's roadmap carries the
  stage view and the conformance row counts behind it.

## Layout

| Path | Purpose |
|---|---|
| `app/` | The client. Kotlin, Compose, `minSdk` 26, `compileSdk` 36. Nine screens, three locales, and the protocol, TUN, DNS, routing and per-app layers |
| `app/src/main/jni/` | Vendored lwIP and its JNI bridge, inherited from the donor, plus the QUIC bridge and the library's export map |
| `tools/quic/` | The QUIC stack's pinned commits and the script that fetches and builds them. Not vendored (D-17); the conformance probe reads the same pin |
| `conformance/` | Byte-level vectors, self-verifying checker, end-to-end smoke test, drift detection, test matrix |
| `app/src/main/assets/rules/` | The bundled rule set (D-14), network-structural tier only, carrying a provenance header |
| `docs/` | Architecture, the design system, i18n, brand, privacy, store policy, and the TLS exporter ADR |

The lwIP layer is inherited from `NodePassProject/Anywhere-Android` (GPL-3.0) at
**e9a9274, 2026-04-28** — recorded because the donor is under active development
and fixes landing there will not arrive on their own. The TUN, routing and
per-app layers are present, and follow the donor's shape rather than copying its
code beyond the lwIP bridge.

**BLAKE3 and libyaml were deliberately not inherited.** The donor's
`CMakeLists.txt` builds both; BLAKE3 serves a protocol this client does not
speak and libyaml parses Clash configuration this client does not accept.
Vendored C is the most expensive code here to review and the least visible when
it goes wrong.

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
          checkClasspathConsistency assembleDebug \
          checkNativeLibraries checkNativeBridge checkReleaseArtifact
python3 conformance/scripts/verify-vectors.py
```

| Gate | What it enforces |
|---|---|
| `ktlintCheck` | Style. `@Composable` PascalCase is exempted through ktlint's own `.editorconfig` switch, not suppressed |
| `koverVerifyDebug` | **90% line coverage on `eu.nodepass.somewhere.protocol.*`, `.subscription.*`, `.dns.*`, `.apps.*` and `.routing.*`** — the last three joined the protocol layer's gate because they are the same kind of code: a wrong answer is wrong silently, and in `routing` it is wrong in the worst direction, with traffic leaving the device somewhere the user did not ask it to. Kover 0.9 filters only at report level, so the report *is* the protocol layer — deliberately, because one number applied to the whole app just gets gamed with trivial tests |
| `lintDebug` | Android lint |
| `checkClasspathConsistency` | **Compile and runtime resolving different versions of the same module.** Added after `FlowRow` compiled against `foundation-layout` 1.7.2 and shipped against 1.9.2: the signature differed, so the screen crashed with `NoSuchMethodError` the first time it was opened, with the build, ktlint, lint and 253 tests all green. Re-running the gate against that BOM reports **29** skewed modules, not one — the crash we hit was one of many latent ones |
| `verify-vectors.py` | 45 known-answer checks, recomputed from the spec prose. References no upstream code and needs no clone |
| `checkNativeLibraries` | **What the shipped `.so` files export, how they are aligned, and what they need.** Reads the APK rather than a build intermediate. Verified to fail: removing the version script reports 1,684 non-JNI exports on arm64-v8a, starting with `AES_CMAC` |
| `checkNativeBridge` | **That the QUIC bridge cannot open a socket.** A source rule over `app/src/main/jni/quic/`: no `socket`, `bind`, `connect`, `sendto` or `recvfrom`. Addresses and packets cross the JNI boundary as bytes, so there is no path by which a datagram escapes `VpnService.protect()`, and no way for one to grow later without the gate saying so |
| `checkReleaseArtifact` | **What R8 left in the APK that ships.** R8 renames the JNI callbacks C resolves by name, and the failure is not a crash but a tunnel that comes up and answers nothing. The gate reads the artifact rather than running instrumentation against a second R8 output — two R8 runs produce two name sets, so that test would be checking a build nobody ships |
| `drift-check.sh` | Upstream normative change. Runs daily in CI and opens an issue; see `conformance/PROTOCOL_BASELINE` for when the pin is held and when it moves — it fired for the first time on 2026-08-26 and the pin moved to v1.8.2 |

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
12. **lwIP does not call the accept callback on a SYN.** `tcp_listen_input`
    answers the SYN with a SYN-ACK and leaves the new pcb in `SYN_RCVD`;
    `TCP_EVENT_ACCEPT` fires from `tcp_process` only when the final ACK
    arrives. A test that sends a SYN and waits for `onTcpAccept` times out
    against a working stack, and the timeout is indistinguishable from a
    library that failed to load. `LwipStackIsAliveTest` therefore completes the
    whole handshake.
13. **The donor's patch inventory is incomplete, and its recovery instructions
    do not work.** `ANYWHERE_PATCHES.md` documents two modifications and says
    the full set can be found with `grep -rn "Anywhere Patch"`. There are
    **seven** patch sites using two different markers — `Anywhere patch` with a
    lowercase p, and `tun2socks patch` — and the documented grep finds three of
    them. The undocumented four are the ones that make a TUN deployment work at
    all: the catch-all netif accept in `ip4.c` and `ip6.c`, the wildcard TCP
    listener in `tcp_in.c`, and the wildcard UDP pcb in `udp.c`. Use
    `grep -rniE "anywhere patch|tun2socks patch"` instead.
14. **`nativeTcpWrite` refuses when lwIP's send buffer is full**, and the
    refusal must be respected. A pump that writes unconditionally loses every
    refused chunk silently: a 20 MB download arrived **8.8% complete at
    15 KB/s**, and the tunnel looked like it was working the whole time.
    Writing only as much as `nativeTcpSndbuf` reports, and waiting on
    `onTcpSent` when it reports none, takes the same transfer to 20 MB/s with
    a matching SHA-256. Pinned by
    `LwipStackIsAliveTest.theSendBufferIsFiniteAndAFullWriteIsRefused`.
15. **`Socket()` has no file descriptor until it is bound or connected**, so
    `VpnService.protect()` on a fresh socket protects nothing and returns
    false. Bind to an ephemeral local address first, protect, then connect —
    in that order, because after `connect` the routing decision is already
    made. A JVM test cannot see this: there is no VpnService, `protect` is the
    default no-op, and the socket connects perfectly well unbound.
16. **Two threads must never drive lwIP.** It is built `NO_SYS=1`: no locks, no
    complaint, and the damage shows up somewhere else much later. Starting the
    tunnel a second time without tearing the first down does exactly that —
    the old pump keeps its 100 ms timer running against the same native stack.
    The symptom was a device that ignored our SYN-ACK and retransmitted its SYN
    forever, on and off, with correct checksums and a correct IP length field.
17. **The setup deadline must come off once a flow is open.** Before READY the
    read timeout is the only thing between a wrong shared key and a hang,
    because a Portal answers a rejected AuthFrame with silence rather than a
    close (fact above). Afterwards the same deadline closes any connection that
    goes quiet — which an idle SSH session, a websocket and a long poll all do
    by design. Verified both ways on a device: with the deadline left in place
    a target that says nothing for 25 seconds returns `HTTP=000` after 60 and
    logs `Read timed out`; with it lifted the same request returns `HTTP=200`
    in 25.0 seconds. `DedicatedTlsLane` calls `transport.setReadTimeout(0)`
    after a successful setup and never after a rejected one.
18. **Keep both ABIs, for different reasons**: physical devices and local
    emulators commonly need `arm64-v8a`; AVDs on x86 CI runners need `x86_64`.
    The donor project ships only the former.

19. **A statically linked BoringSSL exports its entire symbol table**, and this
    process already runs a second one. Linking aws-lc without a version script
    produced **1,700 exported symbols, 1,684 of them the crypto library's
    internals** — 304 `EVP_*`, 97 `EC_*`, 94 `BIO_*`, 80 `RSA_*`. Conscrypt is
    the other BoringSSL in this process and is what the L1 TLS path uses for
    its exporter and for ALPN; two of them exporting the same names globally is
    how a symbol resolves into the wrong one, inside TLS, silently. The library
    now exports `Java_*` and nothing else
    (`app/src/main/jni/exports.map`), which also made it 118 KB smaller.
20. **The NDK links with `-Wl,--no-undefined-version`**, so a version script
    naming a symbol the library does not define is a link error, not a warning.
    Listing `JNI_OnLoad` and `JNI_OnUnload` "in case" fails the build. That is
    the desirable direction: an export map cannot quietly drift into describing
    a library it no longer matches.
21. **`ANDROID_STL=c++_static` costs lwIP nothing**, measured rather than
    assumed. `project(... C)` declared C only, lwIP is pure C, and after the
    change both ABIs produced a byte-identical `.so`: same size, same 282
    dynamic symbols, same `NEEDED` list. The flag was raised from `none`
    because aws-lc's `ssl/` is C++, and the fallback of building the QUIC stack
    under its own toolchain was not needed.
22. **A CMake target with no C++ source of its own links with the C linker.**
    `somewhere_native` is all C, so libc++ was left off a link line that needs
    it for aws-lc. `LINKER_LANGUAGE CXX` is the fix and it has to be set
    explicitly.
23. **`JNI_OnLoad` must be exported, and hiding it fails nowhere near the
    cause.** `lwip_jni_bridge.c` caches the `JavaVM*` there and every
    C-to-Kotlin callback goes through that pointer. An export map listing only
    `Java_*` links cleanly, loads cleanly, and then the tunnel simply never
    answers: `get_env: JavaVM is NULL`, and the device test reports "the stack
    wrote nothing back within 5s". The linker had said so at the time — it
    rejected `JNI_OnUnload` by name and accepted `JNI_OnLoad`, which is how one
    knows which exists.
24. **A version script is an input to the link that ninja does not know about.**
    Editing `exports.map` changes nothing until something else forces a relink,
    so a corrected map and an unchanged library look identical. `LINK_DEPENDS`
    declares it. This cost a whole debugging round: the fix was right, the
    tests failed the same way, and the library on the device predated the edit.

25. **Exactly one bidirectional stream is credited before authentication**
    (NW-P-19). Sixteen concurrent opens meet `ERR_STREAM_ID_BLOCKED`, which is
    the protocol working rather than a failure. The wait for credit has to
    happen **outside** the connection's owner thread: the owner is what pumps,
    and the peer's extension cannot arrive while it is blocked waiting for it.
26. **A closed QUIC stream must leave the write round-robin.** `writev` handed
    an id ngtcp2 no longer knows answers `ERR_STREAM_NOT_FOUND`, which reads
    like a connection failure and takes every other flow on that connection
    down with it. Streams carry a `gone` flag for exactly this.
27. **One datagram per loop pass hangs a large transfer, silently.** Twenty
    megabytes is fourteen thousand datagrams; the kernel buffer overflows, QUIC
    retransmits, the window collapses, and **nothing reports an error** — the
    transfer simply stops making progress while looking healthy. Bursts of
    sixty-four into a 4 MB receive buffer take the same transfer to six seconds.
28. **This client is forced out of its own tunnel in every mode**
    (`AppSelection.ruleFor` — a VPN inside its own tunnel is a routing loop), so
    **a fetch made from inside the app's own process never enters the TUN**.
    Three device test classes passed for two runs while proving only that their
    destination was reachable some other way, and one of them was the evidence
    for a conformance row. The decisive check is a target only the Portal can
    reach: the case fails instantly and the Portal's counters stay put.
    `TunnelHarness` now refuses a target the device can reach directly.
29. **Loopback never enters a VPN's TUN.** The kernel routes 127.0.0.0/8 to `lo`
    rather than to the default route, so the trick that works from inside the app
    — choosing an address only the Portal can reach — proves nothing when the
    fetch is driven from a shell. The evidence there is the Portal's own byte
    counters, which move **on a timer**, so a check must poll until they move
    rather than read them once. And a **truncated transfer is non-empty**: a
    retry loop that accepts any non-empty file reports a corrupt tunnel when what
    it had was a hold window expiring mid-transfer.
30. **Kotlin's `by` delegation erases every interface you did not name.**
    `class TrackedFlow(flow: Flow) : Flow by flow` wrapping a `PacketFlow` is no
    longer a `PacketFlow`, so an `is` check downstream falls through to the
    stream path and UDP quietly loses its packet boundaries. `NowhereSession`
    keeps two wrappers and picks between them.
31. **An unauthenticated QUIC connection dies at the handshake deadline**, about
    five seconds in. A keep-alive test that never opens a flow therefore measures
    nothing: the connection was going to close on its own regardless of whether
    a PING was ever sent.
32. **UDP framing belongs to a direction, not to a flow.** A split flow has two
    carriers, and section 9 carriage has to be applied per direction — the
    oracle differential caught split-UDP diverging when the framing was attached
    to the flow instead. `DatagramLane` is the shared piece both directions use.

## Already verified — do not redo

- The donor project `Anywhere-Android` **builds clean** on this toolchain
  (JDK 21 / android-36 / NDK 28.2 / CMake 3.22.1), 1m50s cold, one harmless lwIP
  macro warning. Inheriting its shell is not a risk.
- `cargo build --release --locked` succeeds on macOS/aarch64 at Nowhere v1.8.2.
- **End-to-end works**: SOCKS5 → Vector → Nowhere (tcp/tcp) → Portal → target,
  payload returned intact. Re-run `conformance/scripts/smoke-local.sh`.
- **Authentication derivation agrees three ways** — spec prose, an independent
  implementation, and upstream Rust fixed vectors, byte for byte.
  Re-run `python3 conformance/scripts/verify-vectors.py` (45 checks).
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
