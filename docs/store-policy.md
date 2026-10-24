# Store policy for a VPN-class app

> Checked 2026-08-29 and reviewed 2026-10-24, before any submission. **Nothing has been submitted**, and
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
| Do not use `VpnService` to collect, monitor or redirect user traffic for undisclosed purposes | The app is a transport to a server the user configured. It has no upload path of its own; the only request it makes on its own behalf is the update check, a `GET` named in `privacy.md` |
| Do not use the VPN to circumvent store policy or bill outside it | No purchases, no billing, no ads |
| A privacy policy, linked from the listing | [`privacy.md`](privacy.md) |
| Prominent disclosure of what a sensitive permission is used for | The consent dialog is the platform's; the notification says a tunnel is running; the privacy policy names every permission |
| No undisclosed reading of installed applications | `<queries>` for launcher-visible apps only, never `QUERY_ALL_PACKAGES`, and the screen says the list is partial |
| A runtime permission asked for in context | `CAMERA` is requested when the QR scanner opens, not at install, and refusing it leaves scanning a picture |
| No exported component another app can use to control the tunnel without the user's say | START/STOP/TOGGLE intents for automation apps are refused unless the user turns on a switch that is off by default and says what it allows |
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

**The update check on a store build.** Google Play does not allow an app it
distributes to update itself by any other route. This app never downloads or
installs an update — it opens a GitHub release page — but a build distributed
through Play should not point its users elsewhere for one. If D-05 includes a
store track, that build turns the check off (one setting default and one
row); the direct-APK build keeps it, because there it is the update path.

**Whether `QUERY_ALL_PACKAGES` is ever wanted (D-16).** Currently not requested.
If the launcher-visible list turns out to be materially incomplete on real
hardware, the fallback is a build flavour split, and a store build would be the
one without it.

## What is deliberately not claimed

**This app does not claim to resist censorship, and its listing must not.**
Nowhere is not an anti-censorship protocol and does not present itself as one:
the words obfuscation, censorship, active probing and DPI appear zero times in
upstream's Core specification (`Nowhere-Protocol` `PROTOCOL.md`) and in its
`docs/security.md` and `docs/prelude.md` at v3.1.0. The Prelude Extension lets
a Portal refuse a recorded connection played back to it; that is replay
protection for the Portal, and it is described as nothing more. A listing that implied
otherwise would be both a false claim and, in several jurisdictions, a
materially riskier one to make.

**The mainland-China rule set is routing, not a claim.** It sends mainland
destinations around the tunnel when the user turns it on in Rules mode, so
that they stay fast and local; a listing may say that, and must not present it
as a way to reach or avoid anything.

**It is not a "free VPN".** It carries no service. A user brings their own
Portal, and without one the app does nothing at all. That is worth saying
plainly on any listing, because the category's reputation is built on apps that
work the other way round.
