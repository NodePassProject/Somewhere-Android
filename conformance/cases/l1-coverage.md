# L1 coverage map

Every L1 row of [`conformance-matrix.md`](conformance-matrix.md), and the test
that covers it. Sixty-three rows, in the matrix's own order.

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
| 25 | All seven rejections produce distinct messages and log lines | covered (messages) / unreachable at L1 (provocation) | `SetupResultVectorTest.allSevenRejectionReasonsAreDistinct`, `SetupResultTextTest.theSevenRejectionsReachSevenDifferentExplanations`, `StringResourceTest.theSevenRejectionsAllHaveAnExplanation`, `DesignRuleUiTest.allSevenRejectionsRenderAsSevenDistinctIdentifiers` (instrumentation). **Provoking all seven from a real Portal is not possible at L1**: `FLOW_LIMIT` needs a Mux carrier's stream cap (L2), and `PAIR_TIMEOUT`, `METADATA_CONFLICT` and `SESSION_REPLACED` all need split flows, which need QUIC (L3). Two are reachable and both are exercised — `READY` and `DIAL_FAILED` |
| 26 | DIAL_FAILED: unreachable target | covered | `SessionAgainstPortalTest.anUnreachableTargetIsReportedAsDialFailedNotAsSuccess`; `oracle-diff.sh` case `dial_failed`, where both implementations report it identically |

## 5. TCP payload and dedicated lanes (spec 7, spec 1)

| # | Case | State | Covered by |
|---|---|---|---|
| 27 | After READY the lane carries raw stream bytes, no per-chunk header | covered | `SessionAgainstPortalTest.aFlowCarriesBytesToARealTargetAndBack`; `oracle-diff.sh` compares the SHA-256 of a megabyte carried by both implementations, which no framing difference could survive |
| 28 | Cold lane writes AUTH + FLOW + TARGET + first payload in one write | covered | `DedicatedTlsLaneTest.theOpeningWriteIsOneWriteInTheSpecifiedOrder`, `NowhereSessionTest.theFirstPayloadRidesTheOpeningWrite`, `SessionAgainstPortalTest.theFirstPayloadInTheOpeningWriteReachesTheTarget` |
| 29 | A lane carries one flow and is not reused | covered | `DedicatedTlsLaneTest.aLaneCarriesExactlyOneFlow`, `NowhereSessionTest.eachFlowGetsItsOwnConnectionAndItsOwnId` |
| 30 | No first FlowHeader byte within 40 s of auth → reclaimed | covered | `PortalLifecycleTest.aConnectionThatAuthenticatesAndThenSaysNothingIsReclaimed` (`portal-lifecycle.sh`). Measured at **40004 ms** against v1.8.2 |
| 31 | Clean EOF closes the sending half; state released once both finish | covered | `DedicatedTlsLaneTest.closingTheFlowClosesTheTransport`, `NowhereSessionTest.closingAFlowReturnsItsIdToTheSession`, `.closingAFlowTwiceReleasesItsIdOnce` |
| 32 | Large bidirectional transfer is lossless (checksum comparison) | covered | `FakeIpTunnelTest.aDomainFetchGoesOutAsANameAndComesBackIntact` (instrumentation, 20 MB with a SHA-256 the origin declares); `oracle-diff.sh` cases `tcp_ip_payload` and `tcp_domain_payload` |

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
| 62 | Kotlin and Rust behave identically against one Portal on the same case set | covered | `oracle-diff.sh` — five cases, both implementations, one Portal. Verified to fail: encoding a domain target with the IPv4 ATYP diverges on one case |
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
