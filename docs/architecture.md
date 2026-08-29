# Architecture

What the layers are, which way they depend, and the rules that keep them apart.

## The shape

```
ui/            screens, theme, icons, presentation state
  ↓
vpn/           VpnService · TUN configuration · lwIP bridge · flow handler · tile
  ↓
data/          NodeStore · SubscriptionStore · NodeRepository
nodes/         NodeHealth · Failover — which node carries the traffic
routing/       rules, the direct path, the reject path
dns/           interception, the fake-IP pool, both families
apps/          which applications the tunnel carries
  ↓
net/           NowhereDialer — TLS, ALPN, certificate verification
  ↓
protocol/      the wire. auth · frame · mux · quic · session · target · tls · url
subscription/  the dashboard's HTTP surface, and its scheduled refresh
```

Six of those packages carry the 90% coverage gate rather than one:
`protocol/`, `subscription/`, `dns/`, `apps/`, `routing/` and `nodes/`. They are
not all the wire, but they are all the same *kind* of code — a wrong answer is
wrong silently, and in `routing/` and `nodes/` it is wrong in the worst
direction, with traffic leaving the device somewhere the user did not ask.

**Dependencies point down and never up.** `protocol/` knows nothing about
Android — no `Context`, no resource, no `Log` — which is why every protocol test
runs on the JVM in milliseconds and can be pointed at a real Portal without a
device. If something in `protocol/` ever needs a string for a person to read,
that is the signal that it belongs a layer up.

## What each layer owns

### `protocol/`

The wire format and the session. This is the part where being wrong means being
wrong on the wire, and it carries the 90% coverage gate for that reason.

Two conventions run through all of it:

- **Sealed results, never exceptions.** Every fallible operation returns
  `DecodeResult<T>`, which is either `Ok` or an `Invalid` carrying a named
  reason. Under fuzzing, "correctly rejected malformed input" and "crashed" are
  the same observation if failures are exceptions — and telling those apart is
  the entire value of fuzzing a parser.
- **Each rejection is its own type.** `SetupResult`'s seven refusals are an
  enum with per-value meaning rather than an error carrying a number, so that
  collapsing them is not the path of least resistance. NW-P-06 requires them to
  stay distinguishable all the way to the screen.

### `subscription/`

The dashboard's HTTP surface: preparing a request, reading a feed, parsing the
quota header. Separate from `protocol/` because it speaks a different protocol
to a different party — a dashboard is not a Portal, and the two fail in
unrelated ways.

`SubscriptionEndpoint` also owns redaction: the subscription URL is a bearer
credential, and it is the only thing that knows how to mention one safely.

### `net/`

`NowhereDialer` — the one place that opens a TLS connection. It exists to make
a specific mistake impossible: `PlatformExporter` cannot read a Conscrypt
socket's key schedule and `ConscryptExporter` cannot read a platform one, so the
socket factory and the exporter are **one decision**. Choosing them at two call
sites gives a client that authenticates on some API levels and not others.

It is also where the three certificate modes live, because verification is a
property of how the connection is opened and cannot be retrofitted afterwards —
a pin checked after the first byte is written is not a pin.

### `data/`

Storage and the observable state over it.

- `NodeStore` persists a node **as the URL text it arrived as**, re-parsed by
  the same parser. Keeping the text means `NowhereUrl.parse` stays the only
  definition of a valid node; a serialised struct would be a second one, and
  the two drift.
- `SubscriptionStore` keeps the credential, in its own file.
- `NodeRepository` is the seam the UI talks to. It owns an
  application-lifetime scope, because a subscription fetch must outlive the
  screen that started it.

### `ui/`

Compose. Three rules that are not obvious from the code:

- **Screens read state and render it.** Anything that reaches the network goes
  through the repository, on the repository's scope.
- **Every screen has a data-free half** — `Home`, `NodeList`, `NodeEditor`,
  `DiagnosticsScreen` all take plain values — so previews and the design-rule
  instrumentation suite can render the design without a repository behind them.
- **Presentation of a failure lives here, not below.** `DecodeReason.detail` is
  a diagnostic string: English, type names, written for a log. Mapping a reason
  to a sentence a person reads is `ui/state/DialReasonText.kt`'s job, and
  shipping `detail` to a screen is a defect that has already happened once.

## What is not here yet

`VpnService` and the TUN / lwIP layer. Until they exist nothing routes device
traffic, and the home screen reports a session that is honestly disconnected.
The layer will sit beside `net/` and feed `protocol/`; nothing above it should
need to change to accommodate it, which is the test of whether these boundaries
were drawn in the right places.

## Testing, by layer

| Layer | How it is tested |
|---|---|
| `protocol/` | Known-answer vectors, fuzzing, and **sixteen tests against a live Portal** — the failures that matter are the ones no fixture predicts |
| `subscription/` | A real local `HttpServer`, so the tests see the bytes actually put on the wire |
| `net/` | Against a live Portal, including pins verified against a fingerprint read over an independent connection |
| `data/` | Temporary directories and a real HTTP server; the round trip is fuzzed, because storage inherits every rounding error the round trip has |
| `ui/` | Contrast and i18n rules as unit tests; the four design rules as instrumentation tests on a device |
