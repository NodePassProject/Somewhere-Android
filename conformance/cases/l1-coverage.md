# L1, L2 and L3 coverage map

Every L1, L2 and L3 row of [`conformance-matrix.md`](conformance-matrix.md),
and the test that covers it. Sixty-three L1 rows, twenty-one L2 rows and
twenty-seven L3 rows, in the matrix's own order.

A row is in one of three states, and there is no fourth:

- **covered** — a named test. Run it and it either passes or it does not.
- **manual** — no automated test, with the reason it cannot have one and what
  to do instead. A reason, never "todo".
- **unreachable at L1** — the case exists in the matrix but nothing at this
  layer can provoke it. Named here so that it is not read as an omission, and
  so that the layer that *can* reach it inherits a written case rather than a
  blank.

This file exists because a matrix with no coverage column is a list of
intentions. The distinction that matters is not covered/uncovered — it is
whether every uncovered row says why.

Test names are given as `Class.method`. Unless noted, they are JVM unit tests
run by `./gradlew testDebugUnitTest`.

---

## 1. Connection authentication (spec 2)

| # | Case | State | Covered by |
|---|---|---|---|
| 1 | `authKey` derivation matches the fixed vector | covered | `AuthVectorTest.everyPositiveVectorIsReproduced`; independently recomputed by `verify-vectors.py` |
| 2 | AuthFrame over TLS/TCP (transport 0x01) matches | covered | `AuthVectorTest.everyPositiveVectorIsReproduced`, `.theTransportByteChangesTheTag` |
| 3 | Exporter is 32 bytes, label `EXPORTER-Nowhere-Auth`, empty context | covered | `ExporterAgainstPortalTest.conscryptExportsAnExporterAndThePortalAcceptsTheResultingAuthFrame`, `.theLabelChangesTheExportedBytes`, `.anEmptyContextDiffersFromNoContext` (needs a Portal) |
| 4 | Shared key of length 0 and 256 rejected | covered | `AuthVectorTest.everyRejectionVectorIsRefused`, `.boundaryLengthsAreAccepted` |
| 5 | URL with a password component rejected | covered | `NowhereUrlTest.aPasswordComponentMakesTheUrlInvalid` |
| 6 | Malformed percent escapes rejected | covered | `AuthVectorTest.everyRejectionVectorIsRefused`, `NowhereUrlTest.malformedInputIsRejectedRatherThanCrashing` |
| 7 | Literal `+` is not decoded as a space | covered | `AuthVectorTest.plusIsALiteralPlusAndNotASpace`, `NowhereUrlTest.aLiteralPlusStaysAPlus`, `ImportLinkTest.a shared key's literal plus is not turned into a space` |
| 8 | Tag comparison is constant time | covered | `ConstantTimeComparisonTest.theAuthenticationTagIsNeverComparedWithAByteWiseEquality` — a source-level rule, because a timing-unsafe comparison is correct in every functional test |
| 9 | Wrong key fails, with no distinguishable response difference | covered | `SessionAgainstPortalTest.aWrongKeyIsRefusedWithoutAnAnswer`; and `oracle-diff.sh` case `wrong_key`, where both implementations reach the same outcome by different routes |

## 2. FlowHeader (spec 4)

| # | Case | State | Covered by |
|---|---|---|---|
| 10 | DUPLEX/TCP/tls/tls encodes to `0001020304` | covered | `FlowHeaderVectorTest.everyPositiveVectorEncodesToItsExpectedBytes` |
| 11 | Role bits `0b11` rejected | covered | `FlowHeaderVectorTest.everyRejectionVectorIsRefused` |
| 12 | `flowId` zero rejected | covered | `FlowHeaderVectorTest.everyRejectionVectorIsRefused`, `DedicatedTlsLaneTest.aZeroFlowIdIsRefusedByTheHeaderRules` |
| 13 | Client-originated flows always carry `hops` 0 | covered | `FlowHeaderVectorTest.clientOriginatedFlowsCannotCarryHops`, `.aPeerMayLegitimatelyCarryHops` |
| 14 | DUPLEX requires up == down; OPEN/ATTACH require up != down | covered | `FlowHeaderVectorTest.duplexRequiresMatchingCarriers`, `.splitFlowsRequireDifferingCarriers`, `FlowShapeTest.*` |
| 15 | `flowId` unique, monotonic, reused only after release | covered | `FlowIdAllocatorTest.idsAreUniqueWhileLive`, `.idsAreMonotonicUntilSomethingIsReleased`, `.aReleasedIdIsReusedButNotImmediatelyAfterItsFlowClosed`, `.concurrentAllocationNeverHandsOutADuplicate` |

## 3. Target (spec 5)

| # | Case | State | Covered by |
|---|---|---|---|
| 16 | IPv4 `192.0.2.1:443` → `01c000020101bb` | covered | `TargetVectorTest.theIpv4VectorCarriesTheExpectedAddressAndPort` |
| 17 | IPv6 `[2001:db8::1]:53` → 19 bytes | covered | `TargetVectorTest.theIpv6VectorCarriesTheExpectedAddressAndPort` |
| 18 | Domain, IDNA, 21 bytes plus port | covered | `TargetVectorTest.theIdnaDomainVectorSurvivesUnchanged` |
| 19 | Port 0 rejected before dialling | covered | `TargetVectorTest.everyRejectionVectorIsRefused`, `FakeIpResolverTest.port zero is refused on both paths and leaves nothing held` |
| 20 | Empty domain / over 253 bytes / non-ASCII rejected | covered | `TargetVectorTest.everyRejectionVectorIsRefused`, `.labelRulesAreEnforced` |
| 21 | Unknown ATYP and truncated address rejected | covered | `TargetVectorTest.atypTwoDoesNotExist`, `.everyTruncationPointIsRejectedRatherThanCrashing` |
| 22 | Domain carries no port and no IPv6 brackets | covered | `TargetVectorTest.legitimateDomainsAreAccepted`, `DnsInterceptorTest.every name that is answered is one the protocol will carry` |

## 4. SetupResult (spec 6)

| # | Case | State | Covered by |
|---|---|---|---|
| 23 | All eight values have their stable wire byte | covered | `SetupResultVectorTest.everyPositiveVectorDecodesToItsNamedResult`, `.theFixtureNamesMatchTheEnumOneForOne` |
| 24 | Values outside `0..7` are protocol errors and close the flow | covered | `SetupResultVectorTest.everyValueOutsideTheRangeIsAProtocolError` |
| 25 | All seven rejections produce distinct messages and log lines | covered (messages) / **five of eight values now observed from a real Portal** | `SetupResultVectorTest.allSevenRejectionReasonsAreDistinct`, `SetupResultTextTest.theSevenRejectionsReachSevenDifferentExplanations`, `StringResourceTest.theSevenRejectionsAllHaveAnExplanation`, `DesignRuleUiTest.allSevenRejectionsRenderAsSevenDistinctIdentifiers` (instrumentation). Settled 2026-08-29. `READY` and `DIAL_FAILED` were reachable at L1; `PAIR_TIMEOUT`, `METADATA_CONFLICT` and `SESSION_REPLACED` are provoked from a real Portal by `QuicSplitFlowTest` now that split flows exist. **Three remain constructed rather than observed**: `FLOW_LIMIT` (this client refuses its own 257th stream, so the Portal is never asked), `INVALID_REQUEST` and `INTERNAL_ERROR` (a Portal answering either would be reporting a fault, which is not a thing a conformant client can ask for) |
| 26 | DIAL_FAILED: unreachable target | covered | `SessionAgainstPortalTest.anUnreachableTargetIsReportedAsDialFailedNotAsSuccess`; `oracle-diff.sh` case `dial_failed`, where both implementations report it identically |

## 5. TCP payload and dedicated lanes (spec 7, spec 1)

| # | Case | State | Covered by |
|---|---|---|---|
| 27 | After READY the lane carries raw stream bytes, no per-chunk header | covered | `SessionAgainstPortalTest.aFlowCarriesBytesToARealTargetAndBack`; `oracle-diff.sh` compares the SHA-256 of a megabyte carried by both implementations, which no framing difference could survive |
| 28 | Cold lane writes AUTH + FLOW + TARGET + first payload in one write | covered | `DedicatedTlsLaneTest.theOpeningWriteIsOneWriteInTheSpecifiedOrder`, `NowhereSessionTest.theFirstPayloadRidesTheOpeningWrite`, `SessionAgainstPortalTest.theFirstPayloadInTheOpeningWriteReachesTheTarget` |
| 29 | A lane carries one flow and is not reused | covered | `DedicatedTlsLaneTest.aLaneCarriesExactlyOneFlow`, `NowhereSessionTest.eachFlowGetsItsOwnConnectionAndItsOwnId` |
| 30 | No first FlowHeader byte within 40 s of auth → reclaimed | covered | `PortalLifecycleTest.aConnectionThatAuthenticatesAndThenSaysNothingIsReclaimed` (`portal-lifecycle.sh`). Measured at **40004 ms** against v1.8.2 |
| 31 | Clean EOF closes the sending half; state released once both finish | covered | `DedicatedTlsLaneTest.closingTheFlowClosesTheTransport`, `NowhereSessionTest.closingAFlowReturnsItsIdToTheSession`, `.closingAFlowTwiceReleasesItsIdOnce` |
| 32 | Large bidirectional transfer is lossless (checksum comparison) | covered | `oracle-diff.sh` cases `tcp_ip_payload` and `tcp_domain_payload`; `QuicPayloadTest.aTransferOverQuicArrivesIntact` (instrumentation, 20 MB against a target only the Portal can reach, digest declared by the origin) and `.theSameBlobOverTlsAndOverQuicProducesTheSameDigest`. **The evidence changed on 2026-08-29**: this row used to cite `FakeIpTunnelTest`, which fetched over an ordinary socket from inside the app's own process — and this client is forced out of its own tunnel in every mode, so that fetch never entered the TUN and proved only that the destination was reachable some other way. That case is disabled and the claim it made now rests on evidence that cannot be satisfied without the Portal |

## 6. UDP over stream (spec 8)

| # | Case | State | Covered by |
|---|---|---|---|
| 33 | Empty packet encodes to `0000` and is valid | covered | `UdpOverTcpVectorTest.anEmptyPacketIsDataAndNotATerminator` |
| 34 | `abc` → `0003616263` | covered | `UdpOverTcpVectorTest.everyPositiveVectorIsReproduced` |
| 35 | Back-to-back packets split correctly | covered | `UdpOverTcpVectorTest.consecutivePacketsSitBackToBack` |
| 36 | EOF after one length byte is a protocol error | covered | `UdpOverTcpVectorTest.truncatedLengthAndTruncatedPayloadAreDifferentFailures` |
| 37 | EOF before the declared payload completes is a protocol error | covered | `UdpOverTcpVectorTest.truncatedLengthAndTruncatedPayloadAreDifferentFailures`, `.everyTruncationPointOfAStreamIsRejectedRatherThanCrashing` |
| 38 | Length above the maximum rejected | covered | `UdpOverTcpVectorTest.aDeclaredLengthCannotDriveAnUnboundedRead`, `.theLargestLegalPacketRoundTrips` |
| 39 | Real UDP round trip (DNS query through the tunnel) | covered | `oracle-diff.sh` case `uot_round_trip`, both implementations against one Portal; and on a device by `FakeIpTunnelTest`, whose fetch cannot resolve without the UoT relay carrying the queries the interceptor declines |

## 9. Configuration and certificates

| # | Case | State | Covered by |
|---|---|---|---|
| 40 | Parse `nowhere://key@host:port?...#name` | covered | `NowhereUrlTest.theClientSchemeIsNowhere` and 30 further cases |
| 41 | Unknown and deprecated parameters ignored (incl. `pool=5`) | covered | `NowhereUrlTest.theDeprecatedPoolParameterIsIgnored`, `.unknownParametersAreIgnoredRatherThanRejected` |
| 42 | Generated share links round-trip with this client **and the dashboard** | covered (this client) / manual (the dashboard) | `NowhereUrlTest.generatedUrlsParseBackToAnEquivalentConfiguration`, `NowhereUrlRoundTripFuzzTest.aRenderedUrlIsAlwaysParseable`. The dashboard half needs a running NowhereDash; the clone carries `docker-compose.yml`, and the check is to paste a link this client generates into the dashboard's import field. Not automated because it would make a client test depend on a server product's release cycle |
| 43 | `up`/`down` default to `udp`; unsupported combinations surfaced, never rewritten | covered | `NowhereUrlTest.defaultsMatchTheSpecification`, `.aDefaultConfigurationNeedsQuicAndSaysSo`, `NodeActionsTest.*`, `DesignRuleUiTest.aNodeNeedingQuicOffersBothChoicesAndRewritesNothing` (instrumentation) |
| 44 | `pin` takes priority over `sni`; case and length validated | covered | `NowhereUrlTest.pinTakesPriorityOverSni`, `.aPinIsNormalisedToLowerCase`, `.aMalformedPinIsRejected` |
| 45 | Neither `sni` nor `pin`: D-11, with a persistent insecure marker | covered | `NowhereUrlTest.neitherSniNorPinMeansVerificationIsSkipped`, `DesignRuleUiTest.theCertificateMarkerIsOnTheHomeScreenAndCannotBeDismissed` (instrumentation) |
| 46 | A wrong `pin` fails the handshake | covered | `NowhereDialerAgainstPortalTest.aPinIsComparedAgainstTheCertificateThePortalActuallyPresents` — the pin is read from the Portal's real leaf over an independent connection, so a pass cannot be the dialer agreeing with itself |
| 47 | Mismatched `alpn` fails the handshake with a readable message | covered | `NowhereDialerAgainstPortalTest.anAlpnThePortalDoesNotSpeakCarriesTheAlpnIntoTheFailure`, `.theNegotiatedProtocolIsCheckedRatherThanAssumed` |

## 10. Dashboard integration

| # | Case | State | Covered by |
|---|---|---|---|
| 48 | Fetch `/sub/portal?token=` and parse multiple `nowhere://` lines | covered | `SubscriptionFetcherTest.nodesAreFetchedAndParsed` against a real local `HttpServer`, not a mock |
| 49 | An http subscription URL produces an explicit warning | covered | `SubscriptionEndpointTest.httpIsFlaggedAsPlaintext`, `SubscriptionFetcherTest.plaintextTransportIsReportedToTheCaller` |
| 50 | Token never appears in logs or crash reports | covered | `SubscriptionEndpointTest.redactsTheToken` and six further redaction cases, `SubscriptionFetcherTest.noFailureReasonEverContainsTheToken`, `FailureMessageTest.noReasonCarriesKeyMaterialMarkers` |
| 51 | Parse and display all four `subscription-userinfo` fields | covered | `SubscriptionFetcherTest.usageHeaderIsParsed` |
| 52 | `total=-1` means unlimited; never "0 of -1" | covered | `SubscriptionFetcherTest.unlimitedIsRepresentedAsUnlimitedNotAsMinusOne`, `DesignRuleUiTest.quotaSaysCountedRatherThanUsed` (instrumentation) |
| 53 | `upload` is always 0; do not present a misleading upload figure | covered | `SubscriptionFetcherTest.thereIsNoWayToReadAnUploadFigure`, `DesignRuleUiTest.nothingOnTheNodeListPresentsUploadAsAMeasurement` (instrumentation) |
| 54 | Parse `profile-title` (base64) and the icon headers | covered | `SubscriptionFetcherTest.aBase64TitleIsDecoded`, `.aMalformedTitleIsDroppedRatherThanFailingTheFetch` |
| 55 | Accept the `add-proxy` deep link form | covered | `ImportLinkTest.*`. This row was genuinely uncovered until the map was written: the manifest already accepted the `anywhere` scheme, so a dashboard's import button reached this app and was then refused by the parser as "scheme 'anywhere' is not a client import URL" |
| 56 | Empty subscription or vanished nodes read as expiry, not a network error | covered | `SubscriptionFetcherTest.anEmptyFeedIsReportedAsQuotaExhaustionNotAsAnError`, `SubscriptionRefreshTest.anEmptyFeedMarksEveryNodeRatherThanReportingANetworkError`, `.aNodeTheFeedDropsIsKeptAndMarkedRatherThanDeleted` |
| 57 | Requests carry `type` / `ver` / `caps` | covered | `SubscriptionEndpointTest.appendsTypeVersionAndCapabilities`, `SubscriptionFetcherTest.capabilityParametersAreActuallySent` |

## 11. Cross-cutting resource and robustness

| # | Case | State | Covered by |
|---|---|---|---|
| 58 | Every decoder survives arbitrary bytes | covered | `DecoderFuzzTest.*` over ten decoders including `DnsMessage.parseQuestion`; `DnsInterceptorTest.arbitrary bytes neither crash nor allocate without bound`; `NowhereUrlRoundTripFuzzTest.*`; `ImportLinkTest.arbitrary text neither throws nor produces something longer than it was given` |
| 59 | The smallest enclosing header is validated before variable-length data | covered | `DecoderFuzzTest.aHostileLengthFieldNeverDrivesAnUnboundedAllocation`, `TargetVectorTest.aHostileLengthByteCannotDriveAnUnboundedRead`, `UdpOverTcpVectorTest.aDeclaredLengthCannotDriveAnUnboundedRead`, `DnsMessageTest.a label length past the end of the message does not read past it` |
| 60 | Network-provided lengths are checked before memory is reserved | covered | as row 59 — the same tests are the check, since an unchecked length shows up as an allocation and not as a wrong answer |
| 61 | Client recovers automatically after a Portal restart | covered | `PortalLifecycleTest.aNewFlowSucceedsAfterThePortalHasRestarted` (`portal-lifecycle.sh`) — the same session, across a kill and a restart, with nothing asked of the user |
| 62 | Kotlin and Rust behave identically against one Portal on the same case set | covered | `oracle-diff.sh` — thirteen cases, both implementations, one Portal, **each of the five run over both carriers**. Verified to fail twice: encoding a domain target with the IPv4 ATYP diverges on one case, and a client that stops multiplexing is caught by the carrier count. It has found one real defect — see the L2 note below |
| 63 | Protocol-layer line coverage ≥ 90% | covered | `koverVerifyDebug`, over `protocol.*`, `subscription.*` and `dns.*`. Verified to fail: a protocol class with no tests reports `0.000000` against a minimum of 90 |

---

## Summary

| State | Rows |
|---|---|
| covered by an automated test | 62 |
| covered in part, with a named manual step | 1 — row 42, whose dashboard half needs a running NowhereDash |

Row 25 is counted among the 62: its claim about the *messages* is tested, while
five of its seven rejections cannot be provoked at this layer at all. The rows
those five need are named in the entry rather than left as an implied gap.

No row is silent.

## What writing this map found

Three things, which is the argument for writing it rather than assuming it:

1. **Row 55 was not implemented at all.** Every NowhereDash import button emits
   `anywhere://add-proxy?link=…`, the manifest already claimed that scheme, and
   the parser then refused it. The manifest entry was an invitation this client
   could not honour.
2. **Rows 30 and 61 had constants where they needed observations.** The 40 s
   reclaim was recorded as a named constant and never watched; the restart path
   was reasoned about and never run. Both now have tests, and the reclaim is
   measured rather than asserted from the document.
3. **Row 25 cannot be finished at L1, and saying so is the finding.** Five of
   the seven rejections need a carrier this layer does not have. Left as a bare
   E2E row it reads as work not yet done; named as unreachable it becomes a
   case L2 and L3 inherit.


---

# L2

Twenty-three rows, added when the Mux carrier landed and extended when a
concurrency measurement that had been passing vacuously was repaired. The same three states, and
the same rule: no row is silent.

| # | Case | State | Covered by |
|---|---|---|---|
| 1 | No duplicate `flowId` under concurrent flow opening (stress) | covered | `FlowIdAllocatorTest.concurrentAllocationNeverHandsOutADuplicate`; and end to end by `MuxSessionTest.nConcurrentFlowsCostCeilingOfNOverFourConnections`, where sixteen flows open at once on one id space |
| 2 | FLOW_LIMIT: exceed the flow cap | covered (client side) / manual (Portal side) | `MuxCarrierTest.theCarrierRefusesMoreThanItsStreamCap` — this client refuses its 257th stream itself, so the Portal is never asked. Provoking the Portal's own `FLOW_LIMIT` means deliberately breaking that cap, which is a case for a fault-injecting build rather than the shipping one |
| 3 | `0xff` marker after AuthFrame; Portal does not echo it | covered | `MuxCarrierTest.theMarkerIsWrittenOnceAfterTheAuthenticationFrame` reads it off the wire at a peer; `.theMarkerCannotBeMistakenForAFlowHeader` is why the dispatch works. Against a live Portal by `e2e-fakeip.sh` under `mux=1`, where nothing would open at all if the marker were wrong |
| 4 | MuxHeader 8-byte layout | covered | `MuxHeaderVectorTest.theHeaderIsEightBytesInTheDocumentedOrder` |
| 5 | STREAM and WINDOW encode/decode | covered | `MuxHeaderVectorTest.validHeadersRoundTrip`, `.theFixtureKindBytesMatchTheEnum` |
| 6 | A DATAGRAM kind closes the carrier as unsupported | covered | `MuxCarrierTest.aDatagramFrameClosesTheCarrier` — a peer really sends one and the carrier really goes |
| 7 | SYN opens, FIN half-closes, RST resets | covered | `MuxCarrierTest.aFlowOpensAndCarriesBytesBothWays`, `.aFinFromThePortalIsACleanEndOfStream`, `.aResetFailsTheStreamWithItsOwnReason`, `.closingAFlowHalfClosesItRatherThanTheCarrier` |
| 8 | RST must be the only flag with `value=0` | covered | `MuxHeaderVectorTest.everyRejectionVectorIsRefused` (both halves: `ResetNotAlone` and `ResetWithValue`) |
| 9 | Any other flag bit set is rejected | covered | `MuxHeaderVectorTest.everyReservedFlagBitIsRejected` |
| 10 | Late FIN and RST processing is idempotent | covered | `MuxCarrierTest.lateFinAndResetAreIdempotent`. This row found a real defect: the first carrier treated a late FIN as data for an unknown flow and tore itself down whenever any flow closed |
| 10a | A rejected flow is released, never reset | covered | `MuxCarrierRefusalTest.aRejectedFlowIsReleasedRatherThanReset`. **The mirror of row 10, from this client's side, and a defect until 2026-08-29.** Section 3 makes STREAM data for an unknown flow a *carrier* error, and the Portal forgets a refused stream as it answers — so a RST after a rejection closed the connection and failed every other flow multiplexed onto it. Found on a device: Android's Private DNS probes port 853 the moment a tunnel comes up, the Portal answers `DIAL_FAILED`, and one to four of sixteen concurrent fetches came back empty depending on which shard the probe landed on. This client now writes no RST at all |
| 10b | A slow SYN answer is waited for, not called a refusal | covered | `MuxCarrierTest.aPortalThatTakesItsTimeAnsweringIsNotAFailedAuthentication`, `.theSetupDeadlineIsTheDedicatedLanesRatherThanAPollInterval`. The carrier gave the setup byte a 250 ms queue-poll interval where a dedicated lane gives the identical protocol step fifteen seconds, and reported anything slower as "authentication most likely failed" |
| 11 | Payload needs both credits before queueing | covered | `MuxCarrierCreditTest.aWindowWithFlowZeroReplenishesTheConnection` — stream credit alone is shown not to be enough. Verified to fail: removing the connection-credit check turns it red |
| 12 | WINDOW with `flowId=0` replenishes connection credit | covered | as row 11 |
| 13 | Credit beyond the configured window closes the carrier | covered | `MuxCreditTest.creditBeyondTheWindowIsRefused`, `MuxCarrierCreditTest.creditBeyondTheWindowClosesTheCarrier` |
| 14 | Late WINDOW for a closed stream is ignored | covered | `MuxCarrierCreditTest.aLateWindowForAClosedStreamIsIgnored` |
| 15 | A STREAM frame never exceeds 32 KiB of payload | covered | `MuxCarrierCreditTest.noStreamFrameCarriesMoreThanThirtyTwoKilobytes` — a 200 KB write, every frame measured, and the payload reassembled by digest |
| 16 | 256-stream cap and 512 outbound queue slots respected | covered | `MuxCarrierTest.theCarrierRefusesMoreThanItsStreamCap`; `MuxCarrierCreditTest.aSlowPeerSlowsTheWriterRatherThanGrowingAQueue` for the queue |
| 17 | A new shard at 4 active flows; a fully idle shard closes after 30 s | covered | `MuxShardSetTest.aNewShardOpensOnlyOnceEveryLiveOneIsFull`, `.aFullyIdleShardClosesAfterThirtySeconds`, `.aFlowArrivingJustBeforeTheDeadlineKeepsTheShard`. The two constants are read from the pinned fixture by `MuxHeaderVectorTest.theFixtureBoundsMatchTheConstants` rather than retyped. The density is also **measured against the reference client**: `oracle-diff.sh` opens sixteen flows at once through each implementation and counts the connections the Portal accepted — four apiece. The idle half is still a fake clock only |
| 18 | Mux `flowId` matches the FlowHeader `flowId` | covered | `MuxCarrierTest.theMuxFlowIdEqualsTheFlowHeaderFlowId` — the peer decodes the FlowHeader out of the SYN payload and the ids are compared there, which is the only place the rule is observable |
| 19 | Closing the carrier fails every logical stream on it | covered | `MuxCarrierTest.closingTheCarrierFailsEveryStreamOnItWithAStatedReason`, and `MuxCarrierRefusalTest.everyRefusalSaysSomethingDifferent` for the "each with its own reason" half |
| 20 | The same case set runs under both `mux=0` and `mux=1` | covered | `conformance/scripts/e2e-fakeip.sh` runs the device set under both by default, and asserts the connection counts differ. Measured: 20 flows over 20 connections at `mux=0`, over 11 at `mux=1`. On the host, `oracle-diff.sh` runs its whole case set under both carriers **and against the reference client**, which is the half a device cannot supply: it is what says the two carriers agree with somebody other than us |
| 21 | Under queue pressure, packets are dropped rather than queued without bound | covered, with a correction | `MuxCarrierCreditTest.aSlowPeerSlowsTheWriterRatherThanGrowingAQueue`, and `UdpRelay`'s `MAX_FLOWS` for the datagram case. **Dropping is right for datagrams and wrong for stream bytes** — a dropped stream byte is a corrupt stream, not a lost packet. So the queue is bounded and a full one makes the writer wait, which on this client becomes back-pressure on the device's own TCP window. The row's wording is upstream's and is about the datagram plane |

## L2 summary

| State | Rows |
|---|---|
| covered by an automated test | 20 |
| covered in part, with a named manual step | 1 — row 2, whose Portal-side `FLOW_LIMIT` this client cannot provoke without breaking its own cap |

Row 25 of L1 said five of the seven rejections were unreachable at that layer.
One of them, `FLOW_LIMIT`, is reachable at L2 in principle and is still not
provoked here, for the reason in row 2 above. The other four remain L3's.

## What running the case set over both carriers found

One defect, and it is the kind only a second implementation finds.

`dial_failed` passed at `mux=0` and diverged at `mux=1`: the oracle reported a
host it could not reach, this client an unclassifiable failure. Nothing on the
wire differed — the Portal sent `DIAL_FAILED` both times and this client read
it both times. What differed is that the two carriers reported it as two
unrelated types, `LaneReason.Rejected` and `MuxCarrierReason.Rejected`, so a
caller that recognised one saw a bare string from the other.

Every Mux test was green because each asserted on the Mux carrier's own type,
which is precisely the shape of mistake a differential exists to catch: both
sides of the assertion moved together. The seven rejections this app renders as
seven distinct explanations are matched on exactly that type, so a `mux=1` node
would have quietly degraded all seven to a generic failure.

`SetupResult` belongs to the protocol and not to whichever carrier fetched it,
so both now implement one `FlowRejected` interface and a caller asks once.
Carrier-specific failures — a lane used twice, a stream the peer reset — keep
their own vocabularies, because those really are the carrier's own. Silence
stays outside it: a Portal that answers nothing has named no `SetupResult`, and
inventing one would be the same mistake facing the other way.

---

## A note on the device cases, added 2026-08-29

**Three instrumentation classes are disabled, and they were proving nothing.**
`FakeIpTunnelTest`, `ThroughputOnDeviceTest` and `ConcurrentFlowsTest` fetch over
ordinary sockets from inside the app's own process. This client is forced out of
its own tunnel in every mode (`AppSelection.ruleFor`) because a VPN inside its
own tunnel is a routing loop, so those sockets never enter the TUN. A case that
succeeded had reached its destination some other way.

It went unnoticed for two runs because the two facts were introduced apart and
never met: per-app selection landed on a day when no device was attached, and
the first execution of these cases afterwards was against a host-local origin an
emulator reaches directly. Every case passed, and the Portal's byte counters had
not moved at all.

What replaces them:

- `TunnelHarness` now **refuses** a target the device can reach directly, so the
  false pass cannot recur.
- Claims about the protocol carrying payload moved to `QuicPayloadTest`, which
  goes through the session layer at a target only the Portal can reach.
- The claim about the **TUN itself** carrying an application's traffic belongs to
  `conformance/scripts/e2e-tunnel-fetch.sh`, which drives the fetch from the
  shell user — who is not this app and therefore is inside the tunnel. **It has
  now run**: 20,971,520 bytes with a matching digest, over both carriers, with
  the Portal's own counters moving by the same amount. That is the first
  evidence this project has ever had that the TUN carries an application's
  traffic.

  Two things it had to learn first. **Loopback never enters a VPN's TUN** — the
  kernel routes 127.0.0.0/8 to `lo` rather than to the default route — so the
  trick that works from inside the app, choosing an address only the Portal can
  reach, proves nothing from a shell. The evidence is the Portal's byte counters
  instead. And a **truncated** transfer is non-empty, so a retry loop that
  accepted any non-empty file reported a corrupt tunnel when what it had was a
  hold window that expired mid-transfer.

- `conformance/scripts/device-acceptance.sh` runs the whole of Phase D as one
  command against whatever device is attached.

# L3

Twenty-seven rows. **Twenty-five are covered and two partly. Nothing is blocked.** — see
the summary below, which names the eight.

Everything covered here is covered *now*, not provisionally. The arithmetic and
framing rows are checked against the same fixture `verify-vectors.py` recomputes
from the specification; the connection, authentication and stream rows are
checked against a live Portal from inside the app process.

| # | Case | State | Covered by |
|---|---|---|---|
| 1 | AuthFrame over QUIC (transport 0x02) matches | covered | `AuthVectorTest.everyPositiveVectorIsReproduced`, `.theTransportByteChangesTheTag` — the QUIC frame differs from the TLS one for the same key, which is the property the transport byte exists for |
| 2 | `OPEN/UDP/quic-up/tls-down` encodes to `0d11223344` | covered | `FlowHeaderVectorTest.everyPositiveVectorEncodesToItsExpectedBytes` |
| 3 | `ATTACH/UDP/tls-up/quic-down` encodes to `1600000007` | covered | as row 2 |
| 4 | `PAIR_TIMEOUT`: OPEN without ATTACH | covered | `QuicSplitFlowTest.pairTimeoutIsProvokedByAnAttachThatArrivesTooLate` — OPEN sent, the Portal's pairing deadline allowed to pass, then ATTACH. Against a Portal run with `NOW_FLOW_PAIR_TIMEOUT=2s`; the default is fifteen |
| 5 | `METADATA_CONFLICT`: mismatched OPEN and ATTACH | covered | `QuicSplitFlowTest.metadataConflictIsProvokedByHalvesThatDisagreeAboutKind` |
| 6 | `SESSION_REPLACED`: a newer carrier for one session | covered | `QuicSplitFlowTest.sessionReplacedIsReturnedToAnAttachWhoseSessionWasTakenOver`. **The obvious provocation does not work**: this Portal implements replacement by tearing the older carrier down, so a fresh flow on it answers `ERR_DRAINING` rather than a setup byte. The shape that works is the specification's own — OPEN arrives, the session is replaced, ATTACH arrives and is told what became of it |
| 7 | Only the downlink of a split flow receives the result | covered | `QuicSplitFlowTest.onlyTheDownlinkReceivesTheResult` on a device, and `SplitCarrierTest.theResultIsReadFromTheDownlinkAndTheUplinkIsNeverRead`, whose uplink would answer a rejection if anything read it |
| 8 | Auth on the first bidirectional stream; later streams send none | covered | `QuicCarrierTest.theFirstStreamCarriesTheAuthFrameAndTheSecondCarriesNone`, `.aRejectedFirstFlowStillCountsAsHavingAuthenticated`; and on a device against a live Portal, `QuicAuthenticationTest.aSecondFlowOpensWithoutASecondAuthFrame` |
| 9 | Unidirectional streams are never used | covered | Two ways, because one of them is only a claim: the client advertises `initial_max_streams_uni = 0`, and `checkNativeBridge` fails the build if `open_uni_stream` appears in the bridge. Verified to fail |
| 10 | Only one bidirectional stream is credited before auth | **partly covered** | The client opens streams one at a time and surfaces `STREAM_ID_BLOCKED` to its caller rather than retrying behind it, so it cannot open a second and stall. **The refusal itself has never been provoked**: the reference Portal credits more than one stream, and a peer that withholds credit is a fake this suite does not have. Recorded rather than claimed |
| 11 | DATA 5-byte header | covered | `QuicDatagramVectorTest.everyPositiveVectorEncodesToItsExpectedBytes` |
| 12 | CLOSE 5-byte header | covered | as row 11, and `.everyRejectionInTheFixtureIsRefused` for the "exactly five bytes" half |
| 13 | FRAGMENT 13-byte header | covered | as row 11 |
| 14 | Type 3 and non-zero reserved bits rejected | covered | `QuicDatagramVectorTest.everyRejectionInTheFixtureIsRefused`, `.everyTruncationPointIsRejectedRatherThanCrashing` |
| 15 | No DATA before READY | covered | `DatagramReassembler` refuses payload until `markReady`, and `QuicCarrier` calls it only when a Portal has answered a flow — so a datagram arriving before any flow exists is discarded rather than queued for one that may never open. `QuicCarrierTest.aUdpFlowsOpeningWriteCarriesNoPayload` pins the other half: the first packet leaves as a DATAGRAM after READY, never on the control stream |
| 16 | Never fragment when the whole packet fits | covered | `QuicDatagramVectorTest.everyPositiveVectorEncodesToItsExpectedBytes` (the fixture's own `payloadLens` case), `.aPacketThatFitsIsOneDataFrameAndCarriesNoFragmentHeader`. This row found a defect: `plan` refused a zero-length packet, having applied the fragment header's `total_len` nonzero rule to a whole packet |
| 17 | `fragmentPayloadMax = maxDatagram - 13`; count in 2..255 | covered | `QuicDatagramVectorTest.theFragmentPlanMatchesTheFixturesOwnArithmetic`, `.everyRejectionInTheFixtureIsRefused` |
| 18 | Reassembly keyed by `(flowId, packetId)` | covered | `FragmentReassemblyTest.packetsOnDifferentFlowsWithTheSameIdDoNotCollide` |
| 19 | Identical duplicate ignored; differing bytes discard the packet | covered | `FragmentReassemblyTest.anIdenticalDuplicateIsIgnoredAndADifferingOneDiscardsThePacket` |
| 20 | Conflicting count or totalLen discards the packet | covered | `FragmentReassemblyTest.aFragmentThatDisagreesAboutCountOrLengthDiscardsThePacket` |
| 21 | Reassembled length not equal to totalLen discards the packet | covered | `FragmentReassemblyTest.aPacketThatDoesNotAddUpToItsDeclaredLengthIsDiscarded` |
| 22 | Reassembly slots and lifetime are bounded (64 slots / 10 s) | covered | `FragmentReassemblyTest.slotsAreBoundedAndAFullTableRefusesNewPacketsRatherThanGrowing`, `.bytesAreBoundedSeparatelyFromSlots`, `.aPacketWhoseLastFragmentNeverComesGivesUpItsSlot`, `.arbitraryFragmentsNeitherCrashNorGrowWithoutBound`. Verified to fail: removing the byte budget turns it red. **The byte budget is a third bound the row does not name** — 64 slots alone would let one fragment each of 64 maximum-size packets pin four megabytes |
| 23 | Replanning after maxDatagram shrinks uses a new packetId | covered | `PacketIdsTest.replanningTakesAFreshIdRatherThanReusingTheOne`, `.aShrunkenDatagramSizeReallyDoesProduceADifferentLayout` — the second states the reason as arithmetic rather than as prose |
| 24 | packetId allocation skips zero | covered | `PacketIdsTest.theFirstIdIsNotZero`, `.idsDoNotRepeatWhileAFlowIsBusy`, `.concurrentAllocationNeverHandsOutADuplicate` |
| 25 | All four carrier combinations interoperate | covered | `oracle-diff.sh` runs its whole case list over `tcp/tcp`, `tcp/tcp`+mux, `udp/udp`, `udp/tcp` and `tcp/udp`, and both implementations behaved identically on every one. The comparison needs a host QUIC bridge — `conformance/scripts/build-host-quic.sh` builds the same JNI sources the app ships against the build machine — and says so loudly when there is none, rather than reporting two pairs as four |
| 26 | Connection migrates or is rebuilt after a network change | **partly covered** | *Rebuilt*, not migrated: a QUIC connection whose path has gone is not something ngtcp2 recovers, so `ReconnectingQuic` builds a new one and `QuicCarrier` authenticates again — checked by `QuicCarrierTest.aRebuiltConnectionAuthenticatesAgain`, because a carrier that remembered having authenticated would produce a tunnel that survives a network change and then carries nothing. **What is not covered is the change itself**: an emulator's network is not a phone's, and observing a real Wi-Fi-to-cellular transition is Phase D's |
| 27 | Keep-alive within the idle timeout, more frugal than 15 s | covered | `KeepAliveTest` checks the relation over every timeout from one second to ten minutes, and that the interval beats upstream's fixed fifteen wherever the timeout allows. `QuicKeepAliveTest` proves the PING is actually sent: an idle **authenticated** connection outlives forty-five seconds of quiet and still opens a flow |

## L3 summary

| State | Rows |
|---|---|
| covered by an automated test | 25 |
| partly covered, with the gap named | 2 — rows 10 and 26 |
| blocked | 0 |

**These are counted from the table above, and the blocked ones are named
rather than totalled.** The previous version of this summary said nine covered
and eighteen blocked while its own table showed sixteen and eleven; the number
had been written by hand and then carried forward through two runs without
anyone re-deriving it. Naming the blocked rows makes the summary re-checkable
by reading, which a total is not.

Row 10 is covered in the half that is this client's behaviour and honest about
the half that needs a peer this suite does not have. The eight blocked rows each
name what they are waiting for rather than being called "not yet written", and
nothing is blocked. Two rows are covered in the half that is this client's
behaviour and honest about the half that needs hardware this project has never
had: row 10's stream-credit refusal, which the reference Portal never produces,
and row 26's network change, which an emulator cannot stage.

**What changed on 2026-08-28.** C1 linked the QUIC stack, C2 made a connection
that completes a handshake against a live Portal, and C3 authenticated over it.
Rows 1, 2 and 3 were already covered as byte-level vectors; what is new is that
the frames they describe are now sent, and a real Portal answers `READY` to the
flow behind them. The Portal's own log records the transition: before C3 a
connection produced "authentication deadline elapsed" because no frame was
sent, and a wrong key now produces "invalid authentication frame" — the frame
arrives, reaches validation, and is refused with silence.

## What C0 found

The run PRD put a spike before any other L3 code, deliberately, because one
requirement decides the shape of everything after it: **NW-P-01 authenticates a
QUIC connection with transport byte `0x02` and an exporter under the label
`EXPORTER-Nowhere-Auth`**, so whatever provides QUIC must expose RFC 5705
keying material from its TLS 1.3 handshake.

`scripts/quic-probe.sh` answers it, and re-answers it on demand:

```
OK  ngtcp2 v1.17.0 (MIT), aws-lc v1.68.0 (Apache-2.0 OR ISC)
OK  arm64-v8a: elf64-littleaarch64
OK  x86_64: elf64-x86-64
handshake=complete
exporter=7f146483b41a9cb04914e962720bf836ad0fa1d4c2de25bd02d34f9805ef709c
setup_result=0x00 READY
```

**ngtcp2 with the stock BoringSSL crypto backend, over aws-lc, builds for both
shipped ABIs, exports 32 bytes under the specified label, and a real Portal
answers READY to the AuthFrame built from them.** D-15 is settled on that.

Three things are worth keeping beyond the verdict.

**The control is the finding, not the READY.** A wrong shared key completes the
same handshake and receives no setup byte at all — silence, which is what
upstream answers a bad tag with over TLS as well, and the Portal logs `invalid
authentication frame`. Without that half, a READY would prove the Portal
answers rather than that the exporter is what it answered to. The script fails
if a wrong key ever reaches a setup result.

**The derivation was pinned before anything was connected.** The C side
recomputes `auth_key` for the shared key `"secret"` and compares it with the
fixed vector this suite already carries. A spike that fails at the Portal
otherwise has four suspects — the derivation, the exporter, the framing, and
the network — and this removes one before the first packet.

**Android needs no custom crypto backend, which is the Apple client's largest
L3 cost and does not recur here.** That client carries
`ngtcp2_crypto_apple.c` plus a TLS 1.3 handshake written in Swift because its
platform ships no TLS a QUIC stack can drive. Nothing of the kind was written
for this: the backend is upstream's own, unmodified.

What remains for C1 is the part the spike deliberately did not do — vendoring,
a JNI bridge, and a Kotlin transport. The probe is a spike and nothing links
against it.
