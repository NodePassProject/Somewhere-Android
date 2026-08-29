# ADR-0002 · Certificate verification on the QUIC carrier

**Status:** `pin` **implemented and verified against a live Portal**, 2026-08-29.
`sni` **assessed, not implemented** — a decision point, not an assumption ·
**Decides:** D-20

## Context

A `nowhere://` node names one of three certificate policies, and upstream's
precedence is `pin` over `sni` over nothing:

| Parameter | What upstream does |
|---|---|
| `pin=<64 hex>` | The leaf's SHA-256 must match. The chain is not consulted |
| `sni=<name>` | Chain verification against that name |
| neither | **No verification at all**, which is what every URL current dashboards emit produces |

The TLS carrier has implemented all three since L1. The QUIC carrier
implemented none, and a node asking for either was refused rather than carried
without it — honest, and unusable for anyone whose dashboard emits a pin.

The two halves are not the same size, which is why D-20 splits them.

## `pin` — decided and done

The comparison happens in the bridge, inside a BoringSSL custom verify callback
(`SSL_set_custom_verify`), against the SHA-256 of the leaf's DER encoding. That
is the same value `pin=` carries and the same one the TLS carrier compares, and
the conversion from hex to bytes is written once so the two carriers cannot
disagree about what a pin is.

Three properties are worth stating because each was a choice:

- **The chain is deliberately not consulted.** A pinned certificate is commonly
  self-signed. Checking the chain first would reject the very certificate the
  pin names, and pinning would work only for certificates that did not need it.
  The TLS carrier makes the same choice. So does the Apple client, whose
  `CertificatePolicy.verify` short-circuits on a pin match with the comment
  "the pin is the user's full trust decision".
- **The comparison is constant time** (`CRYPTO_memcmp`). A short-circuiting
  comparison leaks how many leading bytes matched. That an attacker would need a
  fresh handshake per byte makes it slow rather than impossible.
- **A pin of the wrong length is refused at both ends**, neither padded nor
  truncated. Either would produce a connection verifying against something
  nobody asked for, which is worse than not connecting.

Verified against a live Portal by reading the pin off the Portal's **own leaf
over an independent TLS connection** and handing it back, so a pass cannot be
the client agreeing with itself; and verified to fail with the callback not
installed.

## `sni` — the assessment

### What it would need

ngtcp2 drives a TLS 1.3 handshake through aws-lc. The certificate arrives in the
verify callback as a chain of `CRYPTO_BUFFER`s — DER, leaf first. Turning that
into a trust decision needs three things Android has and aws-lc does not:

1. **Anchors.** The system store lives at `/system/etc/security/cacerts/`, with
   user- and admin-added anchors elsewhere, and the set changes with OS updates
   and with the user's own configuration.
2. **Path building and policy.** Expiry, key usage, basic constraints, name
   constraints, and the blocklists Android ships against certificates that have
   been distrusted since.
3. **Hostname matching.** SAN and wildcard rules against the `sni` name.

### Three ways to get them

| Option | Cost | What it inherits |
|---|---|---|
| **A. Ask the platform.** Keep the custom verify callback, hand the DER chain up through JNI, and run Android's own `X509TrustManager` plus `HostnameVerifier` | Small — an upcall, a chain marshalled as byte arrays, and a thread-safety rule | Everything: the current store, user-added anchors, admin policy, blocklists, and every future update, for free |
| **B. Feed anchors into aws-lc.** Load the system store into an `X509_STORE` and let the library verify | Large, and permanently so — the store has to be re-read when it changes, user-added anchors have to be found, and Android's blocklist has no equivalent in BoringSSL | Only what is copied in, at the moment it is copied |
| **C. Do not implement.** Keep refusing `sni` on QUIC | None | Nothing. A node that a dashboard emits with `sni` cannot use the QUIC carrier at all |

**Option A is the recommendation**, and the Apple client is the precedent: its
QUIC path does not feed anchors into its crypto library either — it evaluates
`SecTrust` with an SSL policy for the server name, one level up from the
handshake, exactly the shape option A has.

The estimate that made this look expensive — "feed a trust store into aws-lc" —
was option B. Option B is expensive. Option A is roughly the size of the `pin`
work, plus care about which thread the upcall happens on.

### What option A costs that is not code

- **A JNI upcall from the QUIC owner thread.** The bridge caches the `JavaVM*`
  in `JNI_OnLoad` and already reaches Kotlin from C on the lwIP side, so the
  mechanism exists. The verify callback runs on the connection's owner thread,
  which is the thread that pumps; a slow trust evaluation stalls it. Android's
  evaluation is local and fast, but this is the property to watch.
- **A second place that can say no.** Two verification paths — the platform's
  for `sni`, the bridge's for `pin` — must not be able to disagree about
  precedence. Upstream's precedence is `pin` first, which the client already
  applies when it parses the URL, so only one of the two is ever installed.
- **`checkNativeBridge` stays satisfied**: an upcall opens no socket, and the
  rule that no address may be resolved or dialled inside the bridge is
  untouched.

### Failure modes to design against

- **A trust decision that fails open.** If the upcall throws — a detached
  thread, a missing class after R8 renames it — the callback must refuse, not
  accept. R8 is the realistic one: the JNI callback resolved by name is exactly
  what `checkReleaseArtifact` exists to catch, and a new one needs a new rule.
- **A hostname that is not the connection's.** The `sni` name is the user's, and
  it is what the certificate must match; matching against the Portal's address
  instead would accept a certificate for the wrong host.
- **Captive portals and clock skew**, which make chain verification fail for
  reasons that are not the Portal's fault and need a message that says so.

## Decision

**`pin`: accepted and implemented.**

**`sni`: recommended as option A, and not implemented in this run.** The reason
is scope rather than cost — L4's scope was fixed before this assessment existed,
and the assessment's own finding is that it belongs in the next one rather than
being squeezed into this. What must not survive is the state this replaced,
where the cost was unknown and the app refused the node with no estimate behind
the refusal.

Until it lands, a QUIC node carrying `sni` is refused, and the message now says
that a pin works on both carriers rather than that verification is unsupported.

**Decided by:** the requester, on this assessment. **By:** before any release a
third party installs.
