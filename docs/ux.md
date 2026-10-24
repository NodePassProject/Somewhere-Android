# UX specification

How the app is organised, where each feature lives, and how screens behave.
[`design-system.md`](design-system.md) holds the values — colour tokens, type,
radii — and this document never restates them. [`brand.md`](brand.md) holds the
brand hue, [`i18n.md`](i18n.md) the language rules.

When a screen and this document disagree, the screen is the bug unless the
document is changed in the same commit.

## Principles

1. **One primary action per screen.** Home's is Connect / Disconnect. Every
   other screen has at most one filled button; the rest are text actions or rows.
2. **State before controls.** A screen first says what is true (connected to
   what, which mode routes traffic, which subscription failed), then offers what
   can be changed.
3. **Nothing pretends.** No figure that is not measured, no "connected" that has
   not reached a Portal, no placeholder card that does nothing. See rule 4 in
   `design-system.md`.
4. **Defaults are safe and work without a visit to Settings.** A new user who
   adds one node and taps Connect gets a working tunnel. Settings exist to
   change behaviour, never to finish installing it.
5. **Every rejection names its cause.** The four `SetupResult` rejections, an
   unresolvable host, a refused certificate and an expired subscription each
   reach the user as a different sentence, with the next step where one exists.

## Appearance

### Theme mode

Three modes, chosen under **Settings → Appearance → Theme**:

| Mode | Label (en) | Behaviour |
|---|---|---|
| `System` | Automatic | **Default.** Follows the system setting, and changes live when the system switches (including scheduled dark mode) |
| `Light` | Light | Always the light palette |
| `Dark` | Dark | Always the dark palette |

Requirements:

- A change applies **immediately**, without restarting the activity or the
  tunnel, and survives a process restart.
- The stored mode is applied **before the first frame**, so a user who forced
  Dark never sees a light flash at launch. The status and navigation bar icons
  follow the effective theme, not the system's.
- The quick-settings tile, notification and shortcuts use system styling; they
  are not themed by this setting.
- **Material You may tint neutrals only.** Direction, state and brand colours
  always come from `SomewhereColors`, in every mode.
- Both palettes are designed, not inverted. A new token is not done until it has
  a light and a dark value and both pass `ColorContrastTest`.

### Language

**Settings → Appearance → Language**: Device language (default), English,
Simplified Chinese, Traditional Chinese — each language named in its own
script. Applies immediately. Machine identifiers (`DIAL_FAILED`,
`net`, `morph`, carrier names) are never translated.

## Information architecture

Four tabs at the bottom, always visible on a tab destination; everything else is
pushed over them and has a Back action.

```text
Home ──────────── Settings ── Network
  │                   └────── (Backup, About)
Nodes ─────────── Add nodes ── Scan QR code
  │               Node editor
Routing ───────── Per-app
Activity (tab currently labelled "Logs")
```

| Tab | Answers | Holds |
|---|---|---|
| **Home** | Am I protected, through what, and how fast? | Connection state, the chosen node (tap to change), Connect / Disconnect, upstream and downstream rates, session figures. Settings entry in the header |
| **Nodes** | Which node, and is it working? | Automatic plus every node, grouped by subscription; latency test, sort, hide unreachable; subscription cards with quota, expiry and refresh; Add nodes |
| **Routing** | What goes through the tunnel? | Mode (Everything / Rules), bundled and imported rule sets, Per-app |
| **Activity** | What is the tunnel doing right now? | Live connections, DNS log, Portal answers, export |

Where each feature lives:

| Feature | Location | Notes |
|---|---|---|
| Add a node by link | Nodes → Add nodes | Several links, one per line, in one paste |
| Add a subscription | Nodes → Add nodes | An `https://` link; plain `http://` is refused because the link is a password |
| Scan a QR code | Nodes → Add nodes → Scan | Camera or a picture from the gallery |
| Deep link `somewhere://`, `nowhere://` | Opens Add nodes pre-filled | Never imports without the user confirming |
| Share a node | Node card → menu | Link or QR code |
| Refresh a subscription | Subscription card | Automatic refresh in Settings → Connection |
| Choose a node | Home's node row, or tap a node in Nodes | Selection persists; reconnects if the tunnel is up |
| Theme, language | Settings → Appearance | |
| Always-on, connect on launch, automation intents | Settings → Connection | Automation is off by default and says why |
| Local networks, excluded routes, MTU, DNS | Settings → Network | |
| Backup and restore | Settings → Backup | Plaintext, with the warning shown before export |
| Version, source code, protocol baseline | Settings → About | The update check belongs here when it exists |

**Rule for new features:** a feature goes on the tab whose question it answers.
If it answers none of them it belongs in Settings, and if it needs to be reached
often it earns a row on Home — not a fifth tab.

## Screen states

Every list and every screen that loads data handles all of these, and has a
preview in `DesignPreviews.kt` for each:

| State | Requirement |
|---|---|
| Empty | A title saying what is missing and one line saying how to add it, with the action if there is one. Never a blank screen |
| Loading | Content keeps its place; a refresh shows progress on the element being refreshed, not a full-screen spinner |
| Error | The cause in plain words, the retry where retrying can help, and the previous content kept visible if there was any |
| Partial | A subscription that refreshed with some lines unparsed says how many were skipped |
| Disabled | Explains why in the row's detail text, never by colour alone |

## Connection feedback

| Tunnel state | Home shows | Notification |
|---|---|---|
| Disconnected | "Not connected", the node, Connect | None |
| Connecting | "Connecting…", the action becomes Cancel | "Connecting" |
| Connected | Node, time connected, live rates | Node, rates, Disconnect |
| Failed | The cause sentence, Retry; in Automatic, which nodes were tried | The cause, once |

"Connected" means a Portal has answered. A state that has only brought the TUN
up must not be shown as Connected.

## Interaction

- **Touch targets** are at least 48 dp (Material's floor; the 44 px of
  `design-system.md` is the visual minimum).
- **Destructive actions** — remove a subscription, restore a backup, close all
  connections — ask once, naming what will be lost. Removing a single node is
  undoable from a snackbar instead of asking.
- **Long operations** never block the screen: a latency test runs per card, a
  subscription refresh per card.
- **Copy and share**: anything a user might paste into an issue (a rejection
  name, a node's fingerprint, a log line) is long-press copyable.
- **Haptics**: a single confirmation tick on Connect and on Disconnect; none
  elsewhere.
- **Motion**: transitions under 250 ms; nothing animates while the system
  "Remove animations" setting is on. Live rates update in place without
  animating digits (monospaced, tabular — see `design-system.md`).
- **Back** always goes back one level; it never disconnects or discards an edit
  without asking.

## Accessibility

- Text scales to 200 % without clipping or overlap; rows grow, they do not
  truncate the essential part (a node's name, a rejection's cause).
- Every icon-only control has a content description; decorative icons have none.
- State is never carried by colour alone: a reachable node has a dot, a figure
  and full-strength ink; a selected row has a radio or check.
- Contrast: text 4.5:1, large text and meaningful non-text 3:1, enforced by
  `ColorContrastTest`.
- TalkBack reads Home as one sentence: state, node, then rates.

## Writing

- Sentence case everywhere, including buttons and titles.
- Say what happened and what to do, in that order. "The Portal is at its flow
  limit. Try again, or choose another node." — not "Error 3".
- The words are fixed: **node** (never server or proxy), **Portal** (the
  operator's end), **subscription**, **tunnel**, **Connect / Disconnect**.
- No claim the protocol does not make. Morph is "masking", never
  "anti-censorship" or "undetectable" (`CLAUDE.md`, settled positions).

## Not decided here

- **The launcher icon** is being redesigned separately; the current one stays
  until it is replaced. `brand.md` constrains its hue.
- **Renaming the Logs tab to Activity** — the screen already is one; the label,
  the route and the strings in three locales change together.
