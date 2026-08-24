# Security policy

Somewhere carries user traffic. A defect here can expose what a person is doing
on the network, so security reports are treated as the highest-priority class of
issue.

## Status

**Pre-release.** No version has shipped to users yet. Only `main` is supported.

## Reporting a vulnerability

**Do not open a public issue for a security defect.** Use either:

- **GitHub private vulnerability reporting** — the *Security* tab of this
  repository, "Report a vulnerability". Preferred: it keeps the report, the fix
  and the advisory in one place.
- **Email** — `deckow@mail.nodepass.eu`.

Please include what you need to make the problem reproducible: the version or
commit, the platform and Android version, the configuration shape (**with any
real key, token or node address removed**), and what you observed versus what
you expected.

A subscription URL is a bearer credential. Never paste a real one into a report,
an issue, or a log excerpt.

## What is in scope

This project is a protocol client, so the defects that matter most are the ones
that break the guarantees a user assumes while it is running:

- **Traffic leaking outside the tunnel** — traffic that should be proxied
  reaching the network directly, including during connect, disconnect, network
  change, or after a crash.
- **DNS leaking** — name resolution escaping the tunnel.
- **Certificate verification** that is weaker than the configuration asks for,
  or a pinned fingerprint that is not actually enforced.
- **Authentication or key-derivation defects** — anything that lets a tag be
  replayed on another connection, or that weakens the exporter binding.
- **Credential exposure** — a shared key, subscription token or node address
  reaching a log, a crash report, a screenshot flow, or a share sheet.
- **Memory-safety defects in the native layer** reachable from network input.

Also in scope: any deviation from the Nowhere protocol specification that has
security consequences. If a deviation is a plain interoperability bug with no
security impact, a normal issue is fine.

## What is not in scope

- Findings that require an already-compromised device, or physical access with
  the screen unlocked.
- The absence of obfuscation. Nowhere is not an anti-censorship protocol and
  does not claim to be; traffic being identifiable as Nowhere is a known property
  of the protocol, not a defect in this client.
- Reports from automated scanners with no demonstrated impact.

## What to expect

This is a small project. Acknowledgement within **7 days**, an initial assessment
within **14 days**. If a report is valid, the fix and the advisory are published
together, and you are credited unless you would rather not be.

Please give a reasonable window before public disclosure. If a defect is being
actively exploited, say so — that changes the schedule.
