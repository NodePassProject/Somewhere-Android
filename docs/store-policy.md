# Store policy for a VPN-class app

> Checked 2026-08-29, before any submission. **Nothing has been submitted**, and
> this is a reading of published policy rather than advice or an approval.

A VPN app is one of the most heavily conditioned categories a store has, and
every condition below is one that gets an app removed rather than merely
delayed. They are recorded now because the expensive ones are architectural: a
policy discovered at submission that requires a different design is a rewrite,
and a policy discovered after publication is a removal.

## What this app already satisfies

| Requirement | Where it stands |
|---|---|
| Declare `VpnService` and use the platform's own consent flow | `SomewhereVpnService` extends `VpnService`; Android shows its dialog and the tunnel cannot start without it |
| A foreground service with a visible ongoing notification | Present, with `specialUse` and its subtype declared — Android has no VPN foreground type, which is why |
| Do not use `VpnService` to collect, monitor or redirect user traffic for undisclosed purposes | The app is a transport to a server the user configured. It has no upload path of its own |
| Do not use the VPN to circumvent store policy or bill outside it | No purchases, no billing, no ads |
| A privacy policy, linked from the listing | [`privacy.md`](privacy.md) |
| Prominent disclosure of what a sensitive permission is used for | The consent dialog is the platform's; the notification says a tunnel is running; the privacy policy names every permission |
| No undisclosed reading of installed applications | `<queries>` for launcher-visible apps only, never `QUERY_ALL_PACKAGES`, and the screen says the list is partial |
| Data safety declaration matching behaviour | Answerable directly from `privacy.md`: nothing is collected, nothing is shared |

## What is still open, and who owns it

**The package identifier — the requester's, and the only one that cannot be
undone.** `eu.nodepass.somewhere` uses the organisation's domain rather than one
this project controls. It is baked into the signing identity, the listing and
the user data directory, and changing it after publication ships a different app
with installs and ratings at zero. D-02b says it is free to change until the
first signed, published build; the first signed build now exists and nothing has
been published, so this is the moment.

**A signing key, and where it lives.** There is none. Whoever creates it holds
the app's identity for its lifetime: a lost key means no further updates, ever,
under that identifier.

**Encryption export compliance.** The app uses TLS and QUIC and statically links
aws-lc. Most jurisdictions exempt an application whose cryptography is limited to
protecting the user's own communications, and store declarations usually ask a
single yes/no. It is listed here because "we forgot to answer the export
question" is a common cause of a rejected first submission, not because it is
expected to be difficult.

**A distribution channel (D-05).** Store track, direct APK, or both. Not decided,
and the answer changes what else is needed: a direct APK needs an update path of
its own, and a store track needs a developer account, a listing and screenshots.

**Whether `QUERY_ALL_PACKAGES` is ever wanted (D-16).** Currently not requested.
If the launcher-visible list turns out to be materially incomplete on real
hardware, the fallback is a build flavour split, and a store build would be the
one without it.

## What is deliberately not claimed

**This app does not claim to resist censorship, and its listing must not.**
Nowhere is not an anti-censorship protocol and does not present itself as one:
the words obfuscation, censorship, active probing and DPI appear zero times in
upstream's own `docs/protocol.md` and `docs/security.md`. A listing that implied
otherwise would be both a false claim and, in several jurisdictions, a
materially riskier one to make.

**It is not a "free VPN".** It carries no service. A user brings their own
Portal, and without one the app does nothing at all. That is worth saying
plainly on any listing, because the category's reputation is built on apps that
work the other way round.
