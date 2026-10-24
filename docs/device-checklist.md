# Device checklist

Every feature added in L5, with what to do on a phone and what should happen.
Run [`conformance/scripts/device-acceptance.sh`](../conformance/scripts/device-acceptance.sh)
first: it settles what `adb` can, and points here for the rest.

Everything below was checked on an API 35 and an API 31 emulator. None of it
has been seen on a physical device, which is what this list is for. Report a
line by its number: what you did, what happened instead, and the device's model
and Android version.

Items marked **(device)** are ones an emulator could not settle at all.

## A · Nodes

| # | Do | Expect |
|---|---|---|
| A1 | Open Nodes with two or more nodes | An **Automatic** card above the list naming the node it would use now, selected by default |
| A2 | Tap a node | It is selected (tick, tinted card); Home names it; Connect uses it |
| A3 | While connected, tap a different node | The tunnel moves to it within a few seconds; tapping the node already in use changes nothing |
| A4 | Choose a node, then make its Portal unreachable and connect | The connect fails with a reason; it does **not** move to another node |
| A5 | Choose Automatic, make the first node unreachable, connect | It moves to a node that answers |
| A6 | Tap **Edit** on a node, change Morph, save | It stays selected if it was |
| A7 | Tap the ⚡ button | Every node shows "…" then a latency or a failure; QUIC nodes are tested over QUIC |
| A8 | Sort by Latency, by Name; switch Hide unreachable | The list reorders; hidden ones are counted ("2 hidden"); a selected node is never hidden |
| A9 | Add → Scan QR code, point at a node's QR code **(device)** | Camera permission is asked once; the code is read; Import shows the node |
| A10 | Add → Scan QR code → Choose an image, pick a screenshot of a node's QR code | The node is read from the picture |
| A11 | Refuse the camera permission twice, then tap **Allow camera** | The app's settings page opens |
| A12 | Paste three node links and a subscription link at once, one per line | "Nodes: 3 · Subscriptions: 1"; a bad line is listed by number; **Add all** adds them |
| A13 | Edit → Share → Show QR code | A QR code another phone can scan **(device)**; a screenshot of the dialog is refused or black |
| A14 | Edit → Share → Copy link, then look at the clipboard preview **(device)** | Android 13+ shows the copy as sensitive (hidden), not the key |
| A15 | Add two subscriptions | Two cards, each with its own refresh, quota, expiry and failure; Remove asks first and keeps the nodes the subscription still listed |

## B · Activity

| # | Do | Expect |
|---|---|---|
| B1 | Connect, browse for a minute, open Activity → Connections | Live connections with host and port, the **app that opened each**, TCP/UDP, PORTAL/DIRECT, bytes up and down, and an age that keeps ticking **(device: app names)** |
| B2 | Close one; then Close all, during a download | Each disappears; the app sees its connection reset; the tunnel stays up; the next download works |
| B3 | Activity → DNS | Names with type and FAKE-IP / NODATA / RELAYED / SERVFAIL; cleared at the next connect |
| B4 | Activity → Portal answers | READY entries say how long the flow took in ms (not a fixed figure) |
| B5 | Turn on **Private DNS → Automatic** in Android settings, connect, browse **(device)** | Sites load; no stream of DIAL_FAILED entries to port 853 |
| B6 | Turn on **Private DNS → a hostname** (e.g. dns.google), connect **(device)** | Report what happens: this sends DNS over TLS outside the fake-IP layer, and names may then leave by address |
| B7 | Activity → Export | A dialog says what the export contains; then the share sheet with a text that uses READY / DIAL_FAILED and holds no key or subscription link |
| B8 | Home while downloading | Each direction draws its own line for the last minute; the handshake figure is a dash until traffic is measured |

## C · From outside the app

| # | Do | Expect |
|---|---|---|
| C1 | Connect for the first time on Android 13+ **(device)** | VPN consent, then the notification permission dialog; neither lost |
| C2 | Pull down the shade while downloading | The notification names the node, shows ↑ and ↓ rates updating each second, and has **Disconnect**, which works |
| C3 | Long-press the launcher icon **(device)** | Toggle, Connect, Disconnect; each one works when tapped, with no screen opening |
| C4 | Settings → turn on **Allow other apps…**; from an automation app (Tasker, MacroDroid…) send `eu.nodepass.somewhere.action.TOGGLE` **(device)** | It connects and disconnects. Report the app; from the background some need a "display over other apps" permission |
| C5 | With that switch off, send the same | Nothing happens |
| C6 | Add the quick-settings tile; tap it; long-press it **(device)** | Tap connects to the chosen node; long-press opens the app |
| C7 | Settings → **Connect when opened**, then open the app from the launcher | It connects; opening it from a link or the tile's long-press does not |
| C8 | Settings → **Hide from recent apps**, open Recents **(device)** | The app is absent, at least once it is not the top task — report what your launcher does |

## D · Settings

| # | Do | Expect |
|---|---|---|
| D1 | Theme: Light, Dark, Automatic | Applies at once; status-bar icons stay readable in each |
| D2 | Language (Android 13+): 简体中文, 繁體中文, English, device | Applies at once; the system's per-app language page shows the same choice |
| D3 | Per-app: hide system apps; sort by Newest; Select all after a search | System apps disappear (a selected one stays); newest installs first; only the found apps are selected **(device: a real app list)** |
| D4 | Per-app: Copy list, then Paste list on another phone or after clearing | The same selection comes back; pasting unrelated text changes nothing |
| D5 | Network → Keep local networks out; connect; open the router's page or a printer by address and by `.local`/`.lan` name **(device)** | Both are reached directly |
| D6 | Network → Excluded routes, e.g. your office range | That range is reached directly; `0.0.0.0/0`, `127.0.0.0/8` and `198.18.0.0/16` are refused with a reason |
| D7 | Network → MTU 1280 on a network where large downloads stall **(device)** | Downloads complete. Also try a large DNS answer (`dig TXT` something long) |
| D8 | Network → DNS: `1.1.1.1`; then look up an SRV or TXT record | It is answered; `192.168.1.1` is refused with a reason |

## E · Backup

| # | Do | Expect |
|---|---|---|
| E1 | Settings → Export to a file | A warning that the file is plaintext and holds keys; then the file picker; a dated `.json` |
| E2 | On another phone or after clearing app data: Home → ⋮ → Restore from a file | A warning that everything is replaced; nodes, subscriptions, choice, rules and settings come back; theme applies at once |
| E3 | Restore a file that is not a backup | It says so and changes nothing |

## Carried over from before L5

The device pass this project has always needed still applies: a real per-app
list, a Wi-Fi to cellular change with the tunnel up, the screen off for thirty
minutes, a path MTU below 1500, IPv6-only and NAT64 networks. See the notes the
acceptance script prints.
