# Design system

The values the UI is built from, and the rules it has to keep. Copy from the
tables here rather than from a screen mockup: the screens are applications of
this system, not the system itself.

## The organising idea

**Upstream and downstream are never the same colour, anywhere in the app.**

Nowhere's distinguishing property is that the two directions can ride different
transports — `up=tcp&down=udp` is a normal configuration, not an edge case. Every
other proxy client shows one throughput number, because for every other protocol
there is only one path. Showing a single number here would discard the one thing
this protocol does that the others do not.

The split is structural, not decorative. It holds on the home screen, in the node
editor, on node cards and in logs.

## Colour

Two themes, following the system setting. Contrast ratios are measured against
each theme's own ground; AA is 4.5:1 for text.

| Token | Light | | Dark | |
|---|---|---|---|---|
| `ground` | `#F4F7F7` | — | `#0C1214` | — |
| `surface` | `#FFFFFF` | — | `#131C1E` | — |
| `surface-2` | `#EDF2F2` | — | `#182326` | — |
| `line` | `#D8E2E2` | — | `#253236` | — |
| `ink` | `#0F1618` | 16.98 | `#E7EFEF` | 16.18 |
| `ink-2` | `#33454A` | 9.32 | `#C0D0D1` | 11.85 |
| `muted` | `#5E7076` | 4.81 | `#8FA3A7` | 7.16 |
| `faint` | `#5E6D72` | 4.99 | `#7C8E94` | 5.53 |
| **`upstream`** | **`#0C6E78`** | 5.54 | **`#55C4CE`** | 9.15 |
| **`downstream`** | **`#A65814`** | 4.84 | **`#E09B55`** | 8.08 |
| `good` | `#2C6E49` | 5.68 | `#6BBF8C` | 8.50 |
| `warn` | `#8A6410` | 4.98 | `#D9AC4A` | 8.95 |
| `critical` | `#A33228` | 6.40 | `#E2857A` | 7.08 |

**The light theme is not an inversion.** Dark `#55C4CE` on white measures 1.9:1
and is unreadable. Each theme picks its own lightness for the same hue: *hue is
the identity, lightness belongs to the theme.*

**Direction colour never comes from dynamic colour.** Material You (API 31+) may
tint the neutrals from the wallpaper. It must not touch `upstream` or
`downstream`: those encode which way traffic goes, not decoration. A wallpaper
that made both channels the same hue would erase the one thing the home screen
exists to show.

**Surface detail.** Three grounds, not two: a card sits *on* the ground, a panel
is cut *into* it. The direction reverses between themes; the role does not.

| Token | Light | Dark | Role |
|---|---|---|---|
| `panel` | `#FFFFFF` | `#0F1719` | a grouped list — settings rows, rule sets |
| `panel-line` | `#E2EAEA` | `#1B2528` | the hairline between two rows of a panel |
| `upstream-line` | `#B6DCDF` | `#1E4A50` | the selected upstream option |
| `downstream-line` | `#E8CBA5` | `#4A3418` | the selected downstream option |
| `critical-line` | `#EFC9C3` | `#4A2A26` | the border of a panel making a critical statement |
| `inactive` | `#B4C4C4` | `#3A4A4E` | a dot or switch knob that is off. Never carries text |

**A selection border is never the only cue.** Measured, the selected border
against the unselected one is **1.11:1** in light and **1.35:1** in dark — a 1 px
hairline at that separation is not perceivable. Every selected state therefore
carries something else as well: the radio on the routing modes, the status dot
and latency figure on a node card. `ColorContrastTest` pins the measurement so
that a screen cannot later be "simplified" down to the border alone.

**The four filled actions differ by theme, and each carries its own foreground.**
A tinted fill is the loudest thing on a dark screen; the same treatment on white
reads as *disabled*. So dark tints and light goes solid — which means these are
pairs, and are measured as pairs.

| Token | Light | Dark | Contrast |
|---|---|---|---|
| `primary-action` / on | `#0C6E78` / `#FFFFFF` | `#12292C` / `#55C4CE` | 5.97 / 7.38 |
| `warn-action` / on | `#8A6410` / `#FFFFFF` | `#3A2E18` / `#D9AC4A` | 5.37 / 6.28 |
| `critical-action` / on | `#A33228` / `#FFFFFF` | `#4A2A26` / `#E7EFEF` | 6.90 / 10.90 |

Tinted backgrounds for chips and panels:

| | Light | Dark |
|---|---|---|
| upstream tint | `#E0F0F1` | `#12292C` |
| downstream tint | `#F7EADD` | `#2A1F14` |
| good tint | `#E4F0E9` | `#16261D` |
| critical tint | `#F8E7E4` | `#2E1A18` |

## Type

| Role | Face | Size / weight |
|---|---|---|
| Screen title | Archivo | 26 / 700, `-0.02em` |
| Row heading | Archivo | 15 / 600 |
| Section label | Archivo | 11 / 600, `0.14em`, uppercase |
| Body | IBM Plex Sans | 14 / 400 |
| Measured value | IBM Plex Mono | 33 / 500, tabular |
| Log line | IBM Plex Mono | 11 / 400 |

The faces ship with the app: Archivo and IBM Plex Sans as **variable** fonts —
one file each covers every weight the design uses, where three static cuts would
cost more and still not cover a fourth. Variable axes need API 26, which is
exactly this app's `minSdk`. No CJK face is bundled: every Android device
already carries one, and bundling adds several megabytes for glyphs the system
has.

**Anything measured is monospaced with `tabular-nums`** — throughput, latency,
flow ids, timestamps, byte counts. A value updating in place must not shift the
layout under the reader's eyes. Prose is never monospaced.

## Controls

- Every tappable target is **at least 44 px** tall.
- Radii by role: **8** chips · **10** fields and rows · **12** cards and buttons ·
  **14** the primary action. Nothing is fully rounded — pill shapes read as
  consumer-app friendliness, and this is closer to an instrument.
- **The primary action differs by theme on purpose**: a tinted fill on dark,
  solid on light. A tinted fill is the loudest thing on a dark screen, but the
  same treatment on white reads as *disabled*.
- Lay out sibling groups with flex or grid and `gap`, never per-element margins.

## Rules the UI has to keep

Nothing crashes when these are violated, which is why they are written down.

### 1. The unverified marker is persistent

A node with neither `sni` nor `pin` gets no certificate verification at all, and
every URL NowhereDash currently generates is exactly that. The marker appears on
the **home screen**, on the **node card**, and as a **confirmation at import**. It
is never a dialog that can be dismissed, because the condition persists for as
long as the node does.

### 2. Seven rejections, seven messages

`SetupResult` carries seven distinct rejections (NW-P-06). Each reaches the user
as a different message. Never "connection failed" — that hides the difference
between a Portal at its flow limit and a session taken over on another device,
which is exactly what someone needs in order to act.

### 3. Never rewrite a pasted node

Upstream defaults both directions to `udp`, so a default configuration needs QUIC
(NW-P-25). A node in that state says so and offers **Switch to TCP** / **Keep as
is**. Two buttons, because rewriting on the user's behalf is what the requirement
forbids.

### 4. Nothing is shown as a measurement that is not one

- **Upload quota is not displayed.** Upstream does not meter it, so `upload` is
  always 0; rendering "0 B uploaded" presents a non-measurement as a measurement.
- **Quota reads "counted", not "used"**, with a line saying why: metering is per
  Portal, so two subscriptions sharing one are both charged the full amount.
- **Quota exhaustion is named**: "subscription expired or out of quota", never
  "network error" or an empty list.

## Language

See [`i18n.md`](i18n.md). The rule that shapes the UI: **machine identifiers stay
English, human sentences translate.**
