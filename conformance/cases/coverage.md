# Coverage map

Every row of [`conformance-matrix.md`](conformance-matrix.md), and what covers
it, at **Nowhere v3.0.1**. Eighty-nine rows, in the matrix's own order.

A row is in one of two states, and there is no third:

- **covered** — a named test or script. Run it and it either passes or it does not.
- **manual** — no automated test, with the reason it cannot have one and what
  to do instead. A reason, never "todo".

Test names are `Class.method`. Unless noted they are JVM unit tests run by
`./gradlew testDebugUnitTest`. *(Portal)* marks a JVM test that needs a live
Portal (`eval "$(conformance/scripts/portal-for-tests.sh)"`) and skips without
one; *(device)* marks instrumentation, run with the arguments in
`conformance/README.md`; scripts are in `conformance/scripts/`.

The v1.8.2 map — 111 rows, including the TLS Mux and split-carrier sections v3
deleted, and the findings writing it produced — is `cases/l1-coverage.md` in
git history up to `8f16af2`.

---

## 1. Authentication

| # | Case | State | Covered by |
|---|---|---|---|
| 1 | Salt, root and both role keys match upstream's vectors | covered | `AuthVectorTest.everyPositiveVectorIsReproduced`, `VectorFixtureTest.theAnchorVectorIsIntact`; recomputed by `verify-vectors.py` |
| 2 | Vector AuthFrame over TLS matches | covered | `AuthVectorTest.everyPositiveVectorIsReproduced`, `.thisClientSendsTheVectorFrame` |
| 3 | Vector AuthFrame over QUIC matches | covered | as row 2; `.theTransportByteChangesTheTag` |
| 4 | The AuthFrame is exactly the 16-byte tag | covered | `AuthVectorTest.theFrameIsExactlySixteenBytes`, `DedicatedTlsLaneTest.theOpeningIsOneWriteAndThePayloadWaitsForReady`, `QuicCarrierTest.streamZeroCarriesTheAuthFrameAndTheNextCarriesNone` |
| 5 | Exporter is 32 bytes, nw3 label, empty context | covered | `ExporterAgainstPortalTest.conscryptExportsAnExporterAndThePortalAcceptsTheResultingAuthFrame`, `.theLabelChangesTheExportedBytes`, `.anEmptyContextDiffersFromNoContext` *(Portal)*; `QuicHandshakeTest.keyingMaterialComesOutAndDependsOnItsLabel` *(device)*; `quic-probe.sh` |
| 6 | An Egress tag verifies over QUIC only | covered | `AuthVectorTest.anEgressTagVerifiesOverQuicOnly` |
| 7 | Share-link keys are 32 lowercase hex, text bytes | covered | `NowhereUrlTest.theKeyMustBeThirtyTwoLowercaseHexCharacters`, `AuthVectorTest.hexLookingTextIsNotHexDecoded`, `.everyRejectionVectorIsRefused` |
| 8 | Password component rejected | covered | `NowhereUrlTest.aPasswordComponentMakesTheUrlInvalid` |
| 9 | Malformed percent escapes rejected | covered | `AuthVectorTest.rejectionsAreDistinguishable`, `NowhereUrlTest.malformedInputIsRejectedRatherThanCrashing` |
| 10 | Literal `+` is not a space | covered | `AuthVectorTest.plusIsALiteralPlusAndNotASpace`, `ImportLinkTest.a shared key's literal plus is not turned into a space` |
| 11 | Tag comparison is constant time | covered | `ConstantTimeComparisonTest.theAuthenticationTagIsNeverComparedWithAByteWiseEquality`, `.theTagComparisonUsesAConstantTimeCall` — a source rule, because a timing-unsafe comparison passes every functional test |
| 12 | A wrong key is never answered | covered | `oracle-diff.sh` cases `wrong_key` (Morph on: the handshake fails, both sides) and `nomorph_wrong_key` (Morph off: the Portal closes, both sides), over both carriers; `SessionAgainstPortalTest.aWrongKeyFailsBeforeAnythingIsAuthenticated` *(Portal)*; `QuicAuthenticationTest.aWrongSharedKeyIsNotAnswered` *(device)* |

## 2. FlowStart

| # | Case | State | Covered by |
|---|---|---|---|
| 13 | TCP `00`, UDP `01` | covered | `FlowStartVectorTest.everyVectorIsReproduced`, `.senderSetsTheReservedBitsToZero`; `verify-vectors.py` |
| 14 | `00 01 7f000001 01bb` | covered | `FlowStartVectorTest.everyVectorIsReproduced` |
| 15 | Reserved bits sent as zero, ignored on receipt | covered | `FlowStartVectorTest.everyByteDecodesAndOnlyBitZeroMatters` |

## 3. Target

| # | Case | State | Covered by |
|---|---|---|---|
| 16 | IPv4 vector | covered | `TargetVectorTest.theIpv4VectorCarriesTheExpectedAddressAndPort` |
| 17 | IPv6 vector | covered | `TargetVectorTest.theIpv6VectorCarriesTheExpectedAddressAndPort` |
| 18 | IDNA domain vector | covered | `TargetVectorTest.theIdnaDomainVectorSurvivesUnchanged` |
| 19 | Port 0 rejected before dialling | covered | `TargetVectorTest.everyRejectionVectorIsRefused`, `FakeIpResolverTest.port zero is refused on both paths and leaves nothing held` |
| 20 | Bad domains rejected | covered | `TargetVectorTest.everyRejectionVectorIsRefused`, `.labelRulesAreEnforced` |
| 21 | Unknown ATYP, truncation rejected | covered | `TargetVectorTest.atypTwoDoesNotExist`, `.everyTruncationPointIsRejectedRatherThanCrashing` |

## 4. SetupResult

| # | Case | State | Covered by |
|---|---|---|---|
| 22 | Five values, v3 bytes | covered | `SetupResultVectorTest.everyPositiveVectorDecodesToItsNamedResult`, `.theFixtureNamesMatchTheEnumOneForOne` |
| 23 | Values above 4 are errors | covered | `SetupResultVectorTest.everyValueOutsideTheRangeIsAProtocolError`, `.everyRejectionVectorIsRefused` |
| 24 | v1's numbering is gone | covered | `SetupResultVectorTest.theV1NumberingIsGone` |
| 25 | Four rejections, four messages | covered | `SetupResultVectorTest.allFourRejectionReasonsAreDistinct`, `SetupResultTextTest.theFourRejectionsReachFourDifferentExplanations`, `StringResourceTest.theFourRejectionsAllHaveAnExplanation`, `DesignRuleUiTest.everyRejectionRendersAsItsOwnIdentifier` *(device)* |
| 26 | DIAL_FAILED, both carriers | covered | `oracle-diff.sh` cases `dial_failed`, `quic_dial_failed`, `nomorph_*`; `SessionAgainstPortalTest.anUnreachableTargetIsReportedAsDialFailedNotAsSuccess` *(Portal)* |
| 27 | Missing flow request → INVALID_REQUEST at the deadline | covered | `PortalLifecycleTest.aConnectionThatAuthenticatesAndThenSaysNothingIsReclaimed` (`portal-lifecycle.sh`) — measured at 5.0 s |

## 5. The TLS carrier

| # | Case | State | Covered by |
|---|---|---|---|
| 28 | One opening write | covered | `DedicatedTlsLaneTest.theOpeningIsOneWriteAndThePayloadWaitsForReady` |
| 29 | Target payload waits for READY | covered | `DedicatedTlsLaneTest.theOpeningIsOneWriteAndThePayloadWaitsForReady`, `.aRejectedFlowNeverSendsItsPayload`, `NowhereSessionTest.theFirstPayloadIsSentOnceThePortalAnswers`; `SessionAgainstPortalTest.theFirstPayloadReachesTheTarget` *(Portal)* — the case that found it |
| 30 | One connection per flow | covered | `DedicatedTlsLaneTest.aLaneCarriesExactlyOneFlow`, `NowhereSessionTest.eachFlowGetsItsOwnConnectionAndItsOwnId`; `oracle-diff.sh` `tls_connections` (exactly 16 for 16 held flows, both implementations); `e2e-fakeip.sh` on a device |
| 31 | Raw bytes after READY; deadline lifted | covered | `DedicatedTlsLaneTest.theFlowReadsAndWritesThroughTheTransport`, `.theSetupDeadlineIsLiftedOnceTheFlowIsOpen`, `.aRejectedOpenLeavesTheDeadlineInPlace` |
| 32 | Large transfer is lossless | covered | `oracle-diff.sh` `tcp_ip_payload` (1 MiB digest); `e2e-fakeip.sh` (20 MiB, IPv4 and IPv6, on a device) |

## 6. UDP over TLS

| # | Case | State | Covered by |
|---|---|---|---|
| 33 | Empty packet | covered | `UdpOverTcpVectorTest.anEmptyPacketIsDataAndNotATerminator` |
| 34 | `abc` | covered | `UdpOverTcpVectorTest.everyPositiveVectorIsReproduced` |
| 35 | Back to back | covered | `UdpOverTcpVectorTest.consecutivePacketsSitBackToBack` |
| 36 | EOF rules | covered | `UdpOverTcpVectorTest.truncatedLengthAndTruncatedPayloadAreDifferentFailures`, `.aCleanEndIsNotAnError`, `.everyTruncationPointOfAStreamIsRejectedRatherThanCrashing` |
| 37 | Real round trip | covered | `oracle-diff.sh` `udp_round_trip`, `nomorph_udp_round_trip` |

## 7. The QUIC carrier and DATAGRAMs

| # | Case | State | Covered by |
|---|---|---|---|
| 38 | AuthFrame on Stream 0 only | covered | `QuicCarrierTest.streamZeroCarriesTheAuthFrameAndTheNextCarriesNone`, `.aRejectedFirstFlowDoesNotCauseASecondAuthFrame`; `QuicAuthenticationTest.aSecondFlowOpensWithoutASecondAuthFrame` *(device)* |
| 39 | A rebuilt connection authenticates again | covered | `QuicCarrierTest.aRebuiltConnectionAuthenticatesAgain` |
| 40 | No unidirectional streams | covered | `checkNativeBridge`, a source rule over the bridge |
| 41 | One stream credited before auth | covered | `QuicHandshakeTest.manyThreadsShareOneConnectionWithoutReachingTheNativeSide`, `QuicPayloadTest.sixteenConcurrentFlowsShareOneConnection` *(device)* |
| 42 | Stream-derived flow id | covered | `QuicFlowIdVectorTest.*`, `QuicCarrierTest.eachFlowIsNamedByItsStream`; `verify-vectors.py` |
| 43 | Base word layout | covered | `DatagramVectorTest.theTypeIsTheTopTwoBitsAndTheIdTheRest`, `.aFlowIdBeyondThirtyBitsCannotBeEncoded`, `QuicDatagramVectorTest.everyPositiveVectorEncodesToItsExpectedBytes` |
| 44 | 4- and 12-byte headers | covered | `DatagramVectorTest.everyPositiveVectorIsReproduced`, `QuicDatagramVectorTest.bothCodecsProduceTheSameBytes` |
| 45 | Invalid datagrams rejected | covered | `DatagramVectorTest.everyRejectionVectorIsRefused`, `QuicDatagramVectorTest.everyDecoderRejectionIsRefused` |
| 46 | No DATA before READY; nothing on the control stream | covered | `DatagramVectorTest.everyRejectionVectorIsRefused` (not-READY case), `QuicCarrierTest.aUdpFlowsOpeningWriteCarriesNoPayload` |
| 47 | Fragment planning | covered | `DatagramVectorTest.fragmentPayloadMaxIsDatagramSizeMinusTwelve`, `.aPayloadNeedingMoreThan255FragmentsIsRejected`, `QuicCarrierTest.aPacketTooLargeForOneDatagramIsFragmentedWithOnePacketId` |
| 48 | A fragment leaves a byte for each other | covered | `DatagramVectorTest.everyRejectionVectorIsRefused` |
| 49 | Reassembly keyed, conflicts discard, bounded | covered | `FragmentReassemblyTest.*`, `DatagramVectorTest.slotCountIsBounded`, `.slotsExpireSoAnAbandonedPacketCannotHoldMemory`, `QuicCarrierTest.aFragmentedPacketIsReassembledBeforeDelivery` |
| 50 | Both carriers, both kinds, against the oracle | covered | `oracle-diff.sh` — every case over TLS and QUIC, Morph on and off; `QuicPayloadTest.theSameBlobOverTlsAndOverQuicProducesTheSameDigest`, `QuicDatagramTest.*` *(device)* |
| 51 | Keep-alive outlives the idle timeout | covered | `QuicKeepAliveTest.anIdleAuthenticatedConnectionOutlivesTheIdleTimeoutAndStillOpensAFlow` *(device)*, `KeepAliveTest.*` |
| 52 | A stream beyond the id space is refused | covered | `QuicCarrierTest.aStreamBeyondTheIdSpaceIsRefusedWithoutAWrite` |

## 8. Morph

| # | Case | State | Covered by |
|---|---|---|---|
| 53 | Directional keys | covered | `MorphVectorTest.theDirectionalKeysAreUpstreams`; `verify-vectors.py` |
| 54 | ChaCha20 | covered | `MorphVectorTest.chaCha20MatchesRfc8439`, `.chaCha20StartsAtBlockZero`; `verify-vectors.py` checks its own ChaCha20 against the RFC first |
| 55 | TCP wire image | covered | `MorphVectorTest.theTcpWireIsUpstreamsLayout`, `MorphSocketTest.bothDirectionsRoundTripThroughAHandWrittenPortal` |
| 56 | Keystream continuity | covered | `MorphVectorTest.theKeystreamContinuesAcrossCalls`, `MorphSocketTest.aSingleByteWriteAndReadKeepTheKeystreamInStep`, `.theCallersArrayIsNotMaskedInPlace` |
| 57 | `fade` prelude | covered | `MorphPreludeTest.fadeSamplesTheBoundariesAsUpstreamDoes`, `.rejectionSamplingRedrawsTheBiasedTail`, `.fadeHasThreeBandsWithBoundariesInRange` |
| 58 | UDP wire image | covered | `MorphVectorTest.theUdpWireIsUpstreamsLayout`, `.aDatagramNoLongerThanTheNonceIsDiscarded`, `.everyDatagramGetsItsOwnNonce` |
| 59 | QUIC payload reduced by the nonce | manual | Set in the bridge (`max_udp_payload`) and in `QuicConnection.open`; observable only as the absence of loss on a 1500-byte path. The device and oracle runs carry 20 MiB with Morph on, which is where an oversized packet would show as a stall |
| 60 | Both modes interoperate; a mismatch fails | covered | `oracle-diff.sh` (all cases with Morph on and off); `NowhereDialerAgainstPortalTest.aMorphMismatchFailsTheHandshake` *(Portal)* |

## 9. Configuration and certificates

| # | Case | State | Covered by |
|---|---|---|---|
| 61 | Parse a share link | covered | `NowhereUrlTest.theClientSchemeIsNowhere` and thirty-five further cases |
| 62 | Defaults | covered | `NowhereUrlTest.defaultsMatchTheSpecification` |
| 63 | First duplicate wins | covered | `NowhereUrlTest.theFirstDuplicateWins` |
| 64 | Unknown and retired parameters ignored | covered | `NowhereUrlTest.unknownParametersAreIgnoredRatherThanRejected`, `.legacyParametersAreIgnoredRatherThanRejected` |
| 65 | A 2.x dashboard's `up`/`down` | covered | `NowhereUrlTest.agreeingLegacyCarriersAreTakenAsNet`, `.aLegacySplitFallsBackToTheDefault`, `.netWinsOverLegacyCarriers`, `.aLegacyLinkIsRewrittenInV3Form` |
| 66 | Carrier paths refused | covered | `NowhereUrlTest.aCarrierPathIsRefusedWithItsName` |
| 67 | Round trip | covered (this client) / manual (the dashboard) | `NowhereUrlTest.generatedUrlsParseBackToTheSameNode`, `NowhereUrlRoundTripFuzzTest.*`. The dashboard half needs a running NowhereDash; NowhereDash at `c753043` targets Nowhere 2.1.1 and does not yet emit v3 links |
| 68 | `pin` over `sni`; validation | covered | `NowhereUrlTest.pinTakesPriorityOverSni`, `.aPinIsNormalisedToLowerCase`, `.aMalformedPinIsRejected`, `.sniMustBeAnAsciiDnsName` |
| 69 | No unverified mode | covered | `PeerVerificationTest.*`; `QuicPinTest.neitherAPinNorAChainCheckIsRefused` *(device)*; `NowhereDialerAgainstPortalTest.aGeneratedCertificateIsNotTrustedByName` *(Portal)* |
| 70 | Hostname matching | covered | `PeerVerificationTest.anExactNameMatchesCaseInsensitively`, `.aWildcardStandsForExactlyOneLeftmostLabel`, `.anIpLiteralMatchesAnIpEntryByItsBytes`, `.theCommonNameIsNeverConsulted` |
| 71 | A wrong `pin` fails, named | covered | `NowhereDialerAgainstPortalTest.aWrongPinIsRefusedAndNamesWhatWasPresented` *(Portal)*; `QuicPinTest.oneWrongNibbleStopsTheHandshake` *(device)* — the pin is the one the Portal logged, so a pass cannot be the client agreeing with itself |
| 72 | Refused by name on a device | covered | `ChainVerificationOnDeviceTest.*` *(device)*. The acceptance half — a chain this device trusts — is manual: it needs a CA key a test can sign with, and committing one would make every debug build trust its reader. `PortalLifecycleTest` accepts a chain by name against a live Portal on the JVM |
| 73 | ALPN `nw3` | covered | `oracle-diff.sh` (every case negotiates it); `DialReason.AlpnRejected` is the observable mismatch — a Portal that refuses aborts the handshake instead, see `CLAUDE.md` fact 11 |

## 10. Dashboard integration

| # | Case | State | Covered by |
|---|---|---|---|
| 74 | Fetch and parse | covered | `SubscriptionFetcherTest.nodesAreFetchedAndParsed`, against a real local `HttpServer` |
| 75 | http warned about | covered | `SubscriptionEndpointTest.httpIsFlaggedAsPlaintext`, `SubscriptionFetcherTest.plaintextTransportIsReportedToTheCaller` |
| 76 | Token never logged | covered | `SubscriptionEndpointTest.redactsTheToken` and six further cases, `SubscriptionFetcherTest.noFailureReasonEverContainsTheToken`, `FailureMessageTest.noReasonCarriesKeyMaterialMarkers` |
| 77 | `subscription-userinfo` | covered | `SubscriptionFetcherTest.usageHeaderIsParsed` |
| 78 | `total=-1` | covered | `SubscriptionFetcherTest.unlimitedIsRepresentedAsUnlimitedNotAsMinusOne`, `DesignRuleUiTest.quotaSaysCountedRatherThanUsed` *(device)* |
| 79 | No upload figure | covered | `SubscriptionFetcherTest.thereIsNoWayToReadAnUploadFigure`, `DesignRuleUiTest.nothingOnTheNodeListPresentsUploadAsAMeasurement` *(device)* |
| 80 | `profile-title` | covered | `SubscriptionFetcherTest.aBase64TitleIsDecoded`, `.aMalformedTitleIsDroppedRatherThanFailingTheFetch` |
| 81 | `add-proxy` deep link | covered | `ImportLinkTest.*` |
| 82 | Empty feed read as expiry | covered | `SubscriptionFetcherTest.anEmptyFeedIsReportedAsQuotaExhaustionNotAsAnError`, `SubscriptionRefreshTest.anEmptyFeedMarksEveryNodeRatherThanReportingANetworkError`, `.aNodeTheFeedDropsIsKeptAndMarkedRatherThanDeleted` |
| 83 | `type` / `ver` / `caps` | covered | `SubscriptionEndpointTest.appendsTypeVersionAndCapabilities`, `SubscriptionFetcherTest.capabilityParametersAreActuallySent` — `caps` is now `nw3,quic` |

## 11. Cross-cutting

| # | Case | State | Covered by |
|---|---|---|---|
| 84 | Decoders survive arbitrary bytes | covered | `DecoderFuzzTest.*` over eleven decoders including `MorphDatagram.open`; `DnsInterceptorTest.arbitrary bytes neither crash nor allocate without bound`; `NowhereUrlRoundTripFuzzTest.*`; `ImportLinkTest.arbitrary text neither throws nor produces something longer than it was given` |
| 85 | Headers before variable data | covered | `DecoderFuzzTest.aHostileLengthFieldNeverDrivesAnUnboundedAllocation`, `TargetVectorTest.aHostileLengthByteCannotDriveAnUnboundedRead`, `UdpOverTcpVectorTest.aDeclaredLengthCannotDriveAnUnboundedRead`, `DnsMessageTest.a label length past the end of the message does not read past it` |
| 86 | Recovery after a Portal restart | covered | `PortalLifecycleTest.aNewFlowSucceedsAfterThePortalHasRestarted` (`portal-lifecycle.sh`) |
| 87 | Kotlin and Rust identical | covered | `oracle-diff.sh` — 22 cases and 2 connection counts, every case over four carrier and Morph combinations. Found two defects on its first v3 runs; see below |
| 88 | Remote resolution on a device | covered | `e2e-fakeip.sh` — the origin is reachable only by a name that resolves on the Portal's docker network, and it hears only from the Portal |
| 89 | Coverage ≥ 90% | covered | `koverVerifyDebug` — 91.7% at this baseline |

---

## Summary

**88 of 89 rows covered by an automated test or script, one manual with its
reason (row 59), and none blocked.** Two rows are covered with a stated gap:
row 67's dashboard half, because NowhereDash does not yet emit v3 links, and
row 72's acceptance half, for the reason given there.

## What the first v3 runs found

Each of these was invisible to the vectors, which agreed with the client the
whole time, and each looked like something other than what it was.

- **The first payload was riding the opening write.** v1 allowed it and this
  client did it on purpose, to save a round trip. A v3 Portal watches the
  stream while it dials and ends the flow on an early byte ("target payload
  arrived before READY"). Every TCP flow that had its first bytes in hand when
  it opened — most of them — would have been torn down. Found by
  `SessionAgainstPortalTest.theFirstPayloadReachesTheTarget`.
- **A refused AuthFrame over QUIC surfaced as a transport failure.** v3
  closes the connection on a bad tag. Over TLS that reached the client as an
  end of stream, "no setup byte"; over QUIC the native connection error
  escaped the stream's `read`, and `oracle-diff.sh` showed the oracle reading
  the same event as "no setup result". The stream transport now presents a
  dead connection as the I/O failure its contract promises.
- **The harness's pin came from a Portal that was not answering.** A Portal
  started inside `$(…)` recorded its PID in a subshell, outlived the run, and
  held the port; the next run's Portal failed to bind after logging a
  fingerprint of its own. Both implementations then agreed on every case —
  that the certificate was wrong. Agreement on a failure is still agreement,
  which is why the script now refuses to start on an occupied port.
- **Sixteen held flows counted as three connections.** The burst's overlap
  lasted only as long as the last arrival took to be answered, which was
  shorter than the socket-table sampler's interval. The origin now holds its
  answers for two seconds after the barrier.
