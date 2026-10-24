# Architecture

What the layers are, which way they depend, and the rules that keep them apart.

## The shape

```
ui/            screens, theme, icons, presentation state, the app's language
update/        whether GitHub Releases has a newer build
  ↓
vpn/           VpnService · TUN configuration · lwIP bridge · flow handler · tile
  ↓
data/          NodeStore · SubscriptionStore · NodeRepository
nodes/         NodeHealth · Failover — which node carries the traffic
routing/       rules and their bundled tiers, the direct path (TCP and UDP), the reject path
dns/           interception, the fake-IP pool, both families
apps/          which applications the tunnel carries
  ↓
net/           NowhereDialer — TCP, Morph, TLS, certificate verification
  ↓
protocol/      the wire. auth · frame · morph · quic · session · target · tls · url
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
- **Each rejection is its own type.** `SetupResult`'s four refusals are an
  enum with per-value meaning rather than an error carrying a number, so that
  collapsing them is not the path of least resistance. They have to stay
  distinguishable all the way to the screen.

Two carriers, chosen by the node's `net`: TLS, one connection per flow
(`DedicatedTlsLane`), and QUIC, one connection for all of them (`QuicCarrier`).
`NowhereSession` picks between them and owns nothing else — v1's session id and
flow-id allocator went with the Mux and split carriers they existed for.
`morph/` is the v3 wire mask (ADR-0003) and the Authenticated Prelude that
v3.1.0's Portals use to refuse a replayed bootstrap; `tls/PeerVerification` is
the one certificate decision both carriers hand their chain to.

### `subscription/`

The dashboard's HTTP surface: preparing a request, reading a feed, parsing the
quota header. Separate from `protocol/` because it speaks a different protocol
to a different party — a dashboard is not a Portal, and the two fail in
unrelated ways.

`SubscriptionEndpoint` also owns redaction: the subscription URL is a bearer
credential, and it is the only thing that knows how to mention one safely.

### `net/`

`NowhereDialer` — the one place that opens a TLS connection: bind, protect,
connect, Morph, then TLS layered over the masked socket with Conscrypt in
engine mode, which is the only layered mode that goes through the wrapper's
streams (ADR-0003). The socket factory, the ALPN and the exporter all come from
that one stack.

It is also where verification happens for TLS, through `PeerVerification` and
`PlatformServerTrust`, before the dialer returns — a check after the first
Nowhere byte is written is a log entry, not a check. `quic/QuicDial` is the same
decision for QUIC.

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

### `vpn/`

The tunnel: `SomewhereVpnService`, the TUN configuration, the lwIP bridge and
the flow handler that turns each TCP connection and UDP association lwIP hands
up into a Portal flow or a direct one.

- **The service starts and stops tunnels on one control thread**, never the
  main thread, which is where StrictMode refuses a lookup by name — and where
  every node named by host once failed for that reason.
- **"Connected" means a Portal answered**: the QUIC handshake, or one TLS dial
  and a close. A QUIC node that does not answer is tried over TLS on the same
  port before it is called unreachable; only unreachability fails over.
- **One `Router` decides TCP and UDP alike**, once per connection or
  association. The structural rule tier is consulted in every mode for its
  DIRECT answers when local networks are kept out; the policy tier (mainland
  China) only in Rules mode, with its switch on.

### `routing/`

`RoutingRules` is the matcher: domain suffixes packed into one sorted byte
array searched once per label boundary, CIDRs in a bit trie per family.
`BundledRules` lists the shipped sets by tier and parses each once per process,
on first use; the generated policy set compiles in a single pass from its text.
`DirectDialer` opens what a rule sends direct — a protected TCP socket or UDP
socket, dialled by name, never by a synthetic address.

## Testing, by layer

| Layer | How it is tested |
|---|---|
| `protocol/` | Known-answer vectors, fuzzing, and **nineteen tests against a live Portal**, the replay refusal of the Authenticated Prelude among them — the failures that matter are the ones no fixture predicts |
| `subscription/` | A real local `HttpServer`, so the tests see the bytes actually put on the wire |
| `net/` | Against a live Portal, including pins verified against a fingerprint read over an independent connection |
| `data/` | Temporary directories and a real HTTP server; the round trip is fuzzed, because storage inherits every rounding error the round trip has |
| `routing/` | The bundled sets against their own claims (provenance, DIRECT only, counts, no country in the structural tier), the one-pass compiler against the general path, and load time on a device |
| `vpn/` | Instrumentation on a device with a Portal on the host, through `conformance/scripts/e2e-android.sh` |
| `ui/` | Contrast and i18n rules as unit tests; the design rules and the update rows as instrumentation tests on a device |
