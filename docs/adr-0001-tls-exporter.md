# ADR-0001 · How the TLS exporter is obtained

**Status:** accepted, 2026-08-24 · **Decides:** D-03 (`minSdk`)

## Context

Nowhere binds its authentication tag to a TLS exporter. From
`Nowhere/docs/protocol.md` section 2:

```text
tag = first 16 bytes of
      HMAC-SHA256(auth_key, transport || exporter[32] || session_id[16])
```

The exporter uses label `EXPORTER-Nowhere-Auth` with an empty context and is 32
bytes long. This is not optional: without it the client cannot produce a tag the
Portal accepts. Upstream implements it in `vector/tls.rs` and
`portal/conn/auth.rs` via rustls' `export_keying_material`.

On Android there are three ways to get one. All three were checked on the
ground rather than from documentation:

| Path | Reachable `minSdk` | Verified how |
|---|---|---|
| `android.net.ssl.SSLSockets.exportKeyingMaterial()` | 31 | `javap` on platform jars: absent in API 28, present-but-without-the-method in API 29, method appears in API 31 |
| `Conscrypt.exportKeyingMaterial()` | 21 | `javap` on `org.conscrypt:conscrypt-android:2.6.3`; the AAR declares `minSdkVersion 21` |
| Port the donor's hand-written TLS | 26 | Searched `Anywhere-Android` for `exporter` / `exportKeyingMaterial`: **zero hits** |

## Decision

**`minSdk = 26`.** The exporter is obtained through a single-method interface:

```kotlin
interface KeyingMaterialExporter {
    /** RFC 8446 §7.5 exporter. Label and context per the Nowhere spec. */
    fun export(socket: SSLSocket, label: String, context: ByteArray, length: Int): ByteArray
}
```

- **API 31+** — `PlatformExporter`, delegating to `SSLSockets.exportKeyingMaterial`.
  No dependency, no bundled crypto.
- **API 26–30** — `ConscryptExporter`, delegating to
  `Conscrypt.exportKeyingMaterial`.

Both paths must produce byte-identical output for the same connection; the
conformance suite covers this as a behavioural case.

## Why not `minSdk = 31`

It would give up API 26–30 to avoid one 32-byte call. This is a client for a
compatibility-oriented protocol; reach is part of the point. The platform API
is still the preferred path — it is simply not the only one.

## Why not port the donor's TLS

The donor's `vpn/protocol/tls/` package is 4,159 lines across 7 files, and it
contains **no exporter implementation at all** — it exists for custom ClientHello
construction, not for RFC 8446 §7.5. Porting it would therefore mean inheriting
4,159 untested lines *and then* hand-writing a cryptographic derivation on top,
inserted into `TlsClient.finishHandshake()` before `clearHandshakeState()` nulls
the transcript and the handshake secret. Conscrypt buys the same capability for
roughly 2.9 MB per ABI, maintained by Google on top of BoringSSL. Rejected.

## Costs accepted

- **Size**: Conscrypt's native library is ~2.9 MB (arm64-v8a) / ~3.1 MB (x86_64).
  App Bundle splits by ABI, so a user downloads one of them, not both.
- **Test matrix**: one extra rung. Both exporter paths need coverage, and the
  device matrix gains an API 26–30 entry.
- **Dependency**: a second TLS stack on the authentication path, below API 31.

The maintenance burden of supporting 26 is smaller than it looks: the donor
project supports 26 through 36 across 123 Kotlin files with **five**
`Build.VERSION.SDK_INT` checks in total.

## Not affected by this decision

Modern platform features remain available — they are gated at runtime and
degrade, which is ordinary Android practice. Material You dynamic colour (31+),
predictive back (33+) and similar are used where present and fall back where not.
`minSdk` sets the floor, not the ceiling; `targetSdk` stays at 36.

**L3 QUIC is out of scope here.** ngtcp2 needs a TLS backend that supplies
QUIC crypto callbacks at the C level, which neither the platform API nor
Conscrypt's Java surface provides. That backend is a separate, still-unexplored
decision; the fact that Conscrypt is BoringSSL underneath is a coincidence, not
a plan.
