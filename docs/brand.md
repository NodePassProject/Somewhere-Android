# Brand

The app's own colour, separate from the colours that mean something.

## The problem this solves

[`design-system.md`](design-system.md) gives five hues a job: `upstream`,
`downstream`, `good`, `warn`, `critical`. Each of them is protocol or system
state rendered as colour, and the whole design is organised around the first
two never being confused.

The app also needs an accent — the tab that is selected, the button that is
primary, the border on the node you picked. Until now that was `upstream`, and
on the node list the two meanings appear in one viewport: a teal `UP TCP` chip
beside a teal selected border. A reader can fairly conclude the border means
upstream. It doesn't.

So the brand is a sixth hue that deliberately means **nothing**.

## Deriving the hue

The hue was not chosen. It is what was left.

Requiring 60° of separation from every hue that already carries meaning — the
same threshold the two directions are held to — leaves exactly one window:

| Hue | Carries |
|---|---|
| 6° | `critical` |
| 30° | `downstream` |
| 41° | `warn` |
| 144° | `good` |
| 185° | `upstream` |
| **245°–306°** | **free** |

`NowhereDash` owns blue at **217°**, so the bottom of that window would read as
the dashboard's colour on a shelf beside it. **280°** clears everything:

| Against | Separation |
|---|---|
| `upstream` | 95° |
| `critical` | 86° |
| `downstream` | 110° |
| `warn` | 121° |
| `good` | 136° |
| NowhereDash blue | 63° |

`ColorContrastTest` asserts every one of these, so a later nudge to the brand
that walks it toward teal fails the build rather than shipping.

## The values

Two lightnesses of one hue, as everywhere else in this system: **hue is the
identity, lightness belongs to the theme.**

| Token | Light | | Dark | |
|---|---|---|---|---|
| `brand` | `#991FD6` | 5.54 | `#B477D2` | 5.85 |
| `brand-tint` | `#F4E9F9` | — | `#22112B` | — |
| `brand-line` | `#D4B4E4` | — | `#48235A` | — |
| `on-brand` | `#FFFFFF` | 5.96 | `#E7EFEF` | 10.81 |

Ratios are against each theme's own ground and are asserted, not claimed.

## The brand never outshouts the protocol state

This is a rule, not a preference, and it is enforced by a test.

Light theme puts the brand at **5.54:1** — `upstream`'s figure exactly. Dark
theme puts it at **5.85:1** where `upstream` sits at **9.15:1**.

A purple bright enough to match teal in the dark, or dark enough to beat it on
white, pulls the eye away from the one thing a screen showing protocol state
exists to show. On the icon and the website the brand is the loudest thing
there is. On the node list it is the quietest accent on screen.

## The ramp

For artwork, the icon, and anything outside the app where AA against a known
ground does not apply. All at 280°.

| Step | Hex | On white | On `#0C1214` |
|---|---|---|---|
| `brand-100` | `#F0E3F7` | 1.23 | 15.32 |
| `brand-200` | `#D5B1E7` | 1.86 | 10.13 |
| `brand-300` | `#B477D2` | 3.23 | 5.85 |
| `brand-400` | `#9836C9` | 5.67 | 3.33 |
| `brand-500` | `#991FD6` | 5.96 | 3.11 |
| `brand-600` | `#461060` | 13.97 | 1.35 |
| `brand-700` | `#290A38` | 17.58 | 1.07 |

`brand-300` is the dark-theme token and `brand-500` the light-theme one; the
rest exist for artwork and have no in-app role.

## What the brand is not

- **Not a state.** Nothing in the protocol is ever drawn in it. If a screen
  needs to say something is good, busy or broken, those colours already exist.
- **Not a replacement for the direction hues.** `upstream` and `downstream`
  keep every job they have.
- **Not subject to dynamic colour.** Same rule as the directions: Material You
  may tint neutrals, and must not touch this.

## Settled: the in-app accent is the brand

**D-13, decided 2026-08-25.** The accent moved from `upstream` to `brand`: the
active tab, the primary action, the add button, a selected row, a checked
switch, the text cursor, and the routing modes' radio.

Applying it turned out to be a larger job than D-13 estimated, and the reason is
worth recording. The decision was written as "four call sites, roughly a dozen
lines". It was **twenty-two**, because `upstream` had quietly become the token
for anything that needed emphasis — the active tab, the add button, a reachable
node's border, the tunnel action, a subscription usage meter, and the upstream
direction. One token, six meanings, of which exactly one was the direction it is
named after.

So the move was not a substitution. Each site had to be read and sorted:

| Was `upstream` because it meant | Now |
|---|---|
| the direction traffic travels | `direction(upstream = true)` — the only sanctioned reader |
| the app is emphasising this | `brand` |
| the node answers / the URL parsed | `good`, and a new `good-line` |
| this is a warning | `warn`, and a new `warn-tint` |

The last two rows are the part that would have been missed by a
search-and-replace: two states had no token of their own, so they had borrowed a
direction's. A warning banner was drawn in `downstream-tint` — the *other* amber
— on both screens that have one.

`DirectionHueIsNotAnAccentTest` now fails the build if any file outside the
palette names a direction hue directly. That is the durable half of this change;
the colour swap is the visible half.

**One thing got worse and is kept on purpose.** The selected border against the
unselected one measured 1.11:1 in light and 1.35:1 in dark; with `brand-line` it
is 1.39:1 and **1.05:1**. Dark selection is now essentially invisible as a
border — which is exactly why the rule that selection always carries a second
cue is a test rather than a note.
