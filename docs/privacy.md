# Privacy

> Applies to Somewhere for Android, package `eu.nodepass.somewhere`.
> Last reviewed 2026-08-29, against the source at that date.

## The short version

**This app sends nothing to its authors.** There is no analytics, no crash
reporting and no telemetry of any kind — not disabled by default, not
opt-out: absent. The dependency list in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) contains no
analytics or reporting library, and a reader can check that in one pass, which
is the point of saying it this way rather than promising it.

Everything the app stores stays on the device. Everything it sends goes to a
server **you configured**, and nowhere else.

## What is stored on the device

All of it in the app's private storage, which no other app can read, and all of
it removed when the app is uninstalled.

| What | Why | Where |
|---|---|---|
| Nodes, including their shared keys | A node is unusable without its key | `NodeStore` |
| A subscription URL and its title | To refresh the node list | `SubscriptionStore` |
| Routing rules you imported | To decide what goes through the tunnel | `RuleStore` |
| Which applications the tunnel carries | Applied when the tunnel is built | `AppSelectionStore` |
| Settings | Preferences | `RoutingPreferences` |

**A shared key and a subscription URL are credentials.** They are stored in
plain text in private storage, which is the same protection the platform gives
every app's own files. On a rooted device, or one with an unlocked bootloader,
that protection is weaker than it sounds — this is stated because it is true,
not because it is unusual.

## What leaves the device, and to where

**To the Portal you configured.** Your tunnelled traffic, plus a 32-byte
authentication frame per connection. The Portal necessarily sees the addresses
and names you connect to — that is what a tunnel is. What it does with them is
its operator's policy, not this app's, and this app has no way to constrain it.

**To the subscription URL you entered**, when you add or refresh a
subscription. An ordinary HTTPS `GET`, carrying three query parameters that
describe this client so a dashboard can generate a node list it can actually
use:

- `type` — that this is a Somewhere client
- `ver` — the client version
- `caps` — which protocol features this build supports

No identifier of you, your device or your installation is sent. There is none
to send: this app generates no installation id.

**To the addresses you connect to**, for traffic your routing rules send
*direct* rather than through the tunnel. That traffic does not reach the Portal
at all, which is the point of a direct rule, and it is not otherwise treated
differently.

**Nowhere else.** No third party receives anything.

## What is not collected

Named individually, because for an app of this kind the absences are the part
worth reading:

- no advertising identifier, and no advertising
- no installation id, device id, IMEI, MAC address or serial number
- no location, coarse or fine
- no contacts, calendar, photos, files or clipboard
- no list of installed applications **leaves the device** — the per-app screen
  reads one to draw itself and it is never transmitted
- no browsing history: the app does not keep one, and the connection log is
  in-memory, capped at 200 entries, and gone when the process ends
- no crash reports, no usage statistics, no "improve the product" data

## The connection log

The Diagnostics screen shows what the Portal answered for recent flows: the
result, the time, a destination as host and port, a flow id, and which carrier
carried it. It exists in memory only, holds the most recent 200 entries, and is
lost when the app's process ends.

It is **built so that a credential cannot appear in it**. An entry has five
fields and none of them can hold a shared key, a subscription URL or a token —
there is no room for one — and a test asserts that field list so a future entry
that grew one fails rather than shipping. A destination is reduced to a host and
a port before it is stored. This matters because people paste logs into public
issue trackers, and helpfulness is how credentials get published.

## Permissions, and what each is for

| Permission | Why |
|---|---|
| `INTERNET` | To reach the Portal and the subscription URL |
| `ACCESS_NETWORK_STATE` | To notice when the network changes under a live tunnel |
| `BIND_VPN_SERVICE` | To establish the tunnel. Android shows its own consent dialog and the tunnel cannot start without it |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | A tunnel must survive the app being backgrounded, which is the normal case: nobody watches a VPN |
| `POST_NOTIFICATIONS` | To show the ongoing notification that says a tunnel is running. Without it the tunnel runs with nothing on screen saying so |

The app declares a `<queries>` entry for applications with a launcher icon so
the per-app screen can list them. It does **not** request
`QUERY_ALL_PACKAGES`, and the screen says its list is partial rather than
pretending otherwise.

## Children

This app is not directed at children and collects nothing from anyone.

## Changes

This document lives in the repository and changes with it. Its history is the
change log: `git log docs/privacy.md`.

## Contact

Security reports: see [`SECURITY.md`](../SECURITY.md). Anything else: the issue
tracker of this repository.
