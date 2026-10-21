# Privacy

> Applies to Somewhere for Android, package `eu.nodepass.somewhere`.
> Last reviewed 2026-10-21, against the source at that date.

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
| Which node you chose, or that you left it automatic | So Home, the tile and shortcuts connect to it | `NodeRepository` |
| Your subscription URLs, their titles, and the quota, expiry and fetch time each last reported | To refresh the node list and show what the dashboard said | `SubscriptionStore` |
| Routing rules you imported | To decide what goes through the tunnel | `RuleStore` |
| Which applications the tunnel carries | Applied when the tunnel is built | `AppSelectionStore` |
| How the tunnel is built: excluded ranges, MTU, DNS servers you named | Applied when the tunnel is built | `NetworkSettingsStore` |
| Settings | Preferences | `RoutingPreferences`, `AppSettingsStore`, `RefreshPreferences` |
| A count of automatic connects that did not survive | So a crash on connect cannot lock you out | `LaunchGuard` |

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
*direct* rather than through the tunnel, and for ranges you keep out of the
tunnel in Network settings. That traffic does not reach the Portal at all,
which is the point, and it is not otherwise treated differently.

**To a DNS server you named**, if you named one in Network settings, for the
queries the tunnel does not answer itself (PTR, SRV, TXT and the like). They
travel through the Portal to it. Without one, they go to the resolver your
network gave the device, as before.

**To a file you choose**, if you export a backup. The app writes it only
through the system's file picker, to the place you pick. The file is not
encrypted and holds everything in the table above except the crash count: every
node's key, every subscription URL, your rules, settings and per-app list. The
app says so before it writes one. On Android 9 and older, shared folders such as
Downloads can be read by other apps.

**Nowhere else.** No third party receives anything. The app does not look up
your outbound IP address or test which services are reachable, which some
clients do through third-party services.

## What is not collected

Named individually, because for an app of this kind the absences are the part
worth reading:

- no advertising identifier, and no advertising
- no installation id, device id, IMEI, MAC address or serial number
- no location, coarse or fine
- no contacts, calendar or files
- the camera is used only while the QR scanner is open, and frames are read on
  the device and discarded; a picture you choose to scan is read the same way
- the clipboard is read only when you tap something that pastes (a link, an
  app list), and written only when you tap something that copies (a node link,
  an app list); a copied node link is marked sensitive so the system's
  clipboard preview does not show it
- no list of installed applications **leaves the device** — the per-app screen
  reads one to draw itself and it is never transmitted. The packages you chose
  leave only if you copy them or export a backup
- no browsing history on disk. The Activity screen shows what the tunnel is
  doing — see below — from memory only, and nothing of it is written to storage
  or sent anywhere unless you export it
- no crash reports, no usage statistics, no "improve the product" data

## The Activity screen

The Logs tab shows three things, all held in memory and never written to
storage:

- **Connections**: what the tunnel is carrying now — destination as host and
  port, the app that opened it (asked of Android, which answers only the active
  VPN app), whether it went through the Portal or direct, and its byte counts.
  Each disappears when its connection closes, and all of them when the tunnel
  stops.
- **DNS**: the names the device looked up while the tunnel was up, with the
  record type and what became of the query. At most 300, and cleared when a new
  session starts.
- **Portal answers**: what the Portal answered for recent flows — the result,
  the time, a destination as host and port, a flow id, the carrier, and how long
  the answer took. At most 200, gone when the app's process ends.

Together these are a record of where the device went while you watched. That is
what makes them useful for diagnosis, and it is why they stay in memory and why
**Export**, which turns them into text you can share, says what it contains
before it runs.

They are **built so that a credential cannot appear in them**. No field can hold
a shared key, a subscription URL or a token — there is no room for one — and a
test asserts the Portal log's field list so an entry that grew one fails rather
than shipping. Destinations and names are reduced to the characters a host name
can have before they are stored. This matters because people paste logs into
public issue trackers, and helpfulness is how credentials get published.

## Permissions, and what each is for

| Permission | Why |
|---|---|
| `INTERNET` | To reach the Portal and the subscription URL |
| `ACCESS_NETWORK_STATE` | To notice when the network changes under a live tunnel |
| `BIND_VPN_SERVICE` | To establish the tunnel. Android shows its own consent dialog and the tunnel cannot start without it |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | A tunnel must survive the app being backgrounded, which is the normal case: nobody watches a VPN |
| `POST_NOTIFICATIONS` | To show the ongoing notification that says a tunnel is running, with its rates and a Disconnect button. Without it the tunnel runs with nothing on screen saying so; it is asked for once a tunnel is up |
| `CAMERA` | To scan a node's QR code. Asked for when the scanner opens, not at install; refusing it leaves scanning a picture |
| `RECEIVE_BOOT_COMPLETED` | So a scheduled subscription refresh survives a reboot. Nothing starts at boot; Android's always-on VPN does that |

Other apps can start or stop the tunnel only if you turn on **Allow other apps
to connect and disconnect** in Settings. It is off by default: when on, any
installed app can turn the tunnel off without asking you, and the switch says
so.

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
