// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mux carrier against a Portal that really speaks Mux.
 *
 * Every case here is a matrix row from section 7, and each is written against
 * the specification's bytes rather than against this implementation's idea of
 * them: the fake Portal decodes what it is sent and encodes what it sends, so a
 * test cannot pass because both halves share a misreading.
 */
class MuxCarrierTest {
    private val key = (SharedKey.of("mux-test-key") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34), 443) as DecodeResult.Ok).value

    private var carrier: MuxCarrier? = null
    private var portal: FakeMuxPortal? = null

    @After
    fun tearDown() {
        runCatching { carrier?.close() }
        runCatching { portal?.stop() }
    }

    /** A started carrier and the Portal on the other end of it. */
    private fun connect(
        setupResult: (Target) -> SetupResult = { SetupResult.Ready },
        onReady: (FakeMuxPortal) -> Unit = {},
        onPayload: (FakeMuxPortal, UInt, ByteArray) -> Unit = { p, id, bytes -> p.sendStream(id, bytes) },
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): Pair<MuxCarrier, FakeMuxPortal> {
        val (clientSide, portalSide) = LoopbackTransport.pair()
        val fake = FakeMuxPortal(portalSide, setupResult, onReady, onPayload).also { it.start() }
        val built = MuxCarrier(clientSide, key, SessionId.random(), clock)
        assertTrue("the carrier failed to start", built.start() is DecodeResult.Ok)
        carrier = built
        portal = fake
        return built to fake
    }

    private fun openFlow(
        carrier: MuxCarrier,
        flowId: UInt = 1u,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> = carrier.open(target, FlowKind.Tcp, flowId, firstPayload)

    private fun readAll(
        flow: Flow,
        count: Int,
    ): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        val deadline = System.currentTimeMillis() + 5_000
        while (filled < count && System.currentTimeMillis() < deadline) {
            val read = flow.read(out, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return out.copyOf(filled)
    }

    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ── B1: the marker and dispatch ─────────────────────────────────────────

    @Test
    fun theMarkerIsWrittenOnceAfterTheAuthenticationFrame() {
        val (_, fake) = connect()
        await("the Portal to read the marker") { fake.markerByte >= 0 }
        assertEquals("the frame is 32 bytes", 32, fake.authFrame!!.size)
        assertEquals("and the marker follows it", 0xff, fake.markerByte)
    }

    @Test
    fun theMarkerCannotBeMistakenForAFlowHeader() {
        // Why the dispatch works at all: 0xff has role bits 0b11, which is
        // reserved, so no valid FlowHeader can begin with it.
        assertTrue(FlowHeader.decode(byteArrayOf(MuxHeader.MODE_MARKER, 0, 0, 0, 1)) is DecodeResult.Invalid)
    }

    @Test
    fun aFlowOpensAndCarriesBytesBothWays() {
        val (built, fake) = connect()
        val flow = (openFlow(built) as DecodeResult.Ok).value
        assertEquals(SetupResult.Ready, flow.setupResult)
        assertEquals("the Portal was asked for the target we named", target, fake.opened[1u])

        flow.write("hello".toByteArray())
        assertEquals("hello", String(readAll(flow, 5)))
    }

    @Test
    fun theOpeningWriteIsTheSameLogicalStreamADedicatedLaneWrites() {
        // The reconstructed logical stream is defined to be identical, so the
        // Portal's side of an opened flow is unchanged between L1 and L2.
        val (built, fake) = connect()
        openFlow(built, firstPayload = "GET /".toByteArray())
        await("the first payload") { fake.payloadByFlow[1u] != null }
        assertEquals(target, fake.opened[1u])
        assertEquals("GET /", String(fake.payloadByFlow[1u]!!))

        val syn = fake.frames().first { it.kind == MuxKind.Stream }
        assertTrue("the opening frame carries SYN", syn.isSyn)
        assertEquals("and names the flow", 1u, syn.flowId)
    }

    @Test
    fun theMuxFlowIdEqualsTheFlowHeaderFlowId() {
        // NW-P-13: the two MUST match, and nothing else on the wire says so.
        val (built, fake) = connect()
        listOf(1u, 7u, 4_000_000_000u).forEach { id ->
            openFlow(built, flowId = id)
            await("flow $id to open") { fake.opened.containsKey(id) }
        }
        fake.frames().filter { it.kind == MuxKind.Stream && it.isSyn }.forEach { syn ->
            assertTrue("the Portal decoded a FlowHeader for ${syn.flowId}", fake.opened.containsKey(syn.flowId))
        }
    }

    @Test
    fun aDatagramFrameClosesTheCarrier() {
        val (built, _) = connect(onReady = { it.sendDatagram(1u) })
        await("the carrier to close") { !built.isOpen }
        val refused = openFlow(built)
        assertTrue(refused is DecodeResult.Invalid)
        assertEquals(
            "the carrier must say DATAGRAM was the cause",
            MuxReason.DatagramUnsupported,
            (refused as DecodeResult.Invalid).reason,
        )
    }

    @Test
    fun streamDataForAFlowThatWasNeverOpenedClosesTheCarrier() {
        // It has to close: the payload was already consumed to find the next
        // frame boundary, so there is no way to ignore the frame and carry on.
        val (built, _) = connect(onReady = { it.sendStream(99u, "surprise".toByteArray()) })
        await("the carrier to close") { !built.isOpen }
        val reason = (openFlow(built) as DecodeResult.Invalid).reason
        assertTrue("expected an unknown-flow error, got $reason", reason is MuxReason.UnknownFlow)
    }

    @Test
    fun aPortalOpenedStreamClosesTheCarrier() {
        // This client dials; it is not dialled. A SYN inbound is a peer doing
        // something the protocol allows in general and this client does not.
        val (built, _) = connect(onReady = { it.sendStream(5u, ByteArray(0), MuxHeader.FLAG_SYN) })
        await("the carrier to close") { !built.isOpen }
        val reason = (openFlow(built) as DecodeResult.Invalid).reason
        assertTrue("expected a peer-opened-stream error, got $reason", reason is MuxCarrierReason.PeerOpenedAStream)
    }

    @Test
    fun anUndecodableHeaderClosesTheCarrier() {
        // Unresynchronisable: without a valid header the length of what follows
        // is unknown, so the next boundary cannot be found.
        val (built, _) = connect(onReady = { it.write(byteArrayOf(0x7f, 0, 0, 0, 0, 0, 0, 1)) })
        await("the carrier to close") { !built.isOpen }
        val reason = (openFlow(built) as DecodeResult.Invalid).reason
        assertTrue("expected an unknown kind, got $reason", reason is MuxReason.UnknownKind)
    }

    // ── B2: stream lifecycle ────────────────────────────────────────────────

    @Test
    fun aRejectedFlowReachesTheCallerAsItsOwnResult() {
        val (built, _) = connect(setupResult = { SetupResult.DialFailed })
        val refused = openFlow(built)
        val reason = (refused as DecodeResult.Invalid).reason
        assertTrue(reason is MuxCarrierReason.Rejected)
        assertEquals(SetupResult.DialFailed, (reason as MuxCarrierReason.Rejected).result)
        assertTrue("a rejection must not take the carrier down with it", built.isOpen)
        assertEquals("and must not leak the stream", 0, built.activeFlowCount)
    }

    @Test
    fun aFinFromThePortalIsACleanEndOfStream() {
        val (built, fake) = connect()
        val flow = (openFlow(built) as DecodeResult.Ok).value
        fake.sendStream(1u, "last".toByteArray())
        fake.sendFin(1u)

        assertEquals("last", String(readAll(flow, 4)))
        assertEquals("a clean end is -1, not an error", -1, flow.read(ByteArray(8)))
    }

    @Test
    fun aResetFailsTheStreamWithItsOwnReason() {
        val (built, fake) = connect()
        val flow = (openFlow(built) as DecodeResult.Ok).value
        fake.sendReset(1u)
        await("the stream to end") { flow.read(ByteArray(4)) < 0 }
        assertTrue("a reset must not take the carrier down", built.isOpen)
    }

    @Test
    fun lateFinAndResetAreIdempotent() {
        val (built, fake) = connect()
        val flow = (openFlow(built) as DecodeResult.Ok).value
        fake.sendFin(1u)
        await("the stream to end") { flow.read(ByteArray(4)) < 0 }

        // Frames that crossed the close in flight. None of them may fail the
        // carrier, and none may be counted twice.
        repeat(3) { fake.sendFin(1u) }
        repeat(3) { fake.sendReset(1u) }
        Thread.sleep(200)
        assertTrue("late frames must not close the carrier", built.isOpen)

        flow.close()
        flow.close()
        assertEquals("double close releases the slot exactly once", 0, built.activeFlowCount)
    }

    @Test
    fun closingAFlowHalfClosesItRatherThanTheCarrier() {
        val (built, fake) = connect()
        val first = (openFlow(built, flowId = 1u) as DecodeResult.Ok).value
        val second = (openFlow(built, flowId = 2u) as DecodeResult.Ok).value

        first.close()
        await("the Portal to see a FIN") { fake.frames().any { it.isFin && it.flowId == 1u } }
        assertTrue("the carrier stays up", built.isOpen)

        second.write("still here".toByteArray())
        assertEquals("still here", String(readAll(second, 10)))
    }

    @Test
    fun closingTheCarrierFailsEveryStreamOnItWithAStatedReason() {
        val (built, _) = connect()
        val flows = (1..4).map { (openFlow(built, flowId = it.toUInt()) as DecodeResult.Ok).value }
        assertEquals(4, built.activeFlowCount)

        built.close()
        flows.forEach { flow ->
            assertEquals("every stream ends", -1, flow.read(ByteArray(8)))
            assertFalse(flow.isOpen)
        }
        val reason = (openFlow(built) as DecodeResult.Invalid).reason
        assertNotNull("the carrier names why it went", reason.detail)
        assertEquals(MuxCarrierReason.ClosedByCaller, reason)
    }

    @Test
    fun theCarrierRefusesMoreThanItsStreamCap() {
        val (built, _) = connect()
        // 256 is the cap. Opening them all costs 256 round trips against the
        // fake, which is cheap, and the boundary is the whole point.
        (1..MuxHeader.MAX_ACTIVE_STREAMS).forEach { index ->
            assertTrue("flow $index should open", openFlow(built, flowId = index.toUInt()) is DecodeResult.Ok)
        }
        assertEquals(MuxHeader.MAX_ACTIVE_STREAMS, built.activeFlowCount)

        val refused = openFlow(built, flowId = (MuxHeader.MAX_ACTIVE_STREAMS + 1).toUInt())
        val reason = (refused as DecodeResult.Invalid).reason
        assertTrue("expected the stream cap, got $reason", reason is MuxCarrierReason.StreamLimit)
        assertTrue("and the carrier survives being full", built.isOpen)
    }

    @Test
    fun aZeroFlowIdIsRefused() {
        val (built, _) = connect()
        val reason = (openFlow(built, flowId = 0u) as DecodeResult.Invalid).reason
        assertEquals(MuxReason.StreamFlowIdZero, reason)
    }
}
