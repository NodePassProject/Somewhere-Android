// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.mux.FakeMuxPortal
import eu.nodepass.somewhere.protocol.mux.LoopbackTransport
import eu.nodepass.somewhere.protocol.mux.MuxHeader
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `mux=1` selects the Mux carrier, and `mux=0` selects what was there before.
 *
 * The claim L2 exists to make is arithmetic and this is where it is asserted:
 * N concurrent flows cost ⌈N/4⌉ connections rather than N. Everything else in
 * this file is the other half of rule 2 of the run — that turning Mux on must
 * not have changed what happens when it is off.
 */
class MuxSessionTest {
    private val key = (SharedKey.of("mux-session-key") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(10, 0, 0, 1), 443) as DecodeResult.Ok).value

    private val portals = mutableListOf<FakeMuxPortal>()
    private val connections = AtomicInteger(0)
    private var session: NowhereSession? = null

    @After
    fun tearDown() {
        runCatching { session?.close() }
        portals.forEach { runCatching { it.stop() } }
    }

    /** A session whose every connection is answered by a Portal that speaks Mux. */
    private fun muxSession(): NowhereSession =
        NowhereSession(
            sharedKey = key,
            mux = true,
            connect = {
                connections.incrementAndGet()
                val (clientSide, portalSide) = LoopbackTransport.pair()
                portals += FakeMuxPortal(portalSide, { SetupResult.Ready }).also { it.start() }
                clientSide
            },
        ).also { session = it }

    @Test
    fun nConcurrentFlowsCostCeilingOfNOverFourConnections() {
        // The whole case for L2. At L1 this is sixteen TLS handshakes and
        // sixteen authentication frames; here it is four.
        val built = muxSession()
        val flows = (1..16).map { (built.openFlow(target, FlowKind.Tcp) as DecodeResult.Ok).value }

        assertEquals("sixteen flows are live", 16, built.liveFlowCount)
        assertEquals("over four carriers", 4, built.carrierCount)
        assertEquals("and four connections were opened, not sixteen", 4, connections.get())
        assertTrue("every flow really opened", flows.all { it.setupResult == SetupResult.Ready })
    }

    @Test
    fun aMuxFlowCarriesBytesLikeAnyOther() {
        val built = muxSession()
        val flow = (built.openFlow(target, FlowKind.Tcp, "ping".toByteArray()) as DecodeResult.Ok).value
        val buffer = ByteArray(4)
        var filled = 0
        val deadline = System.currentTimeMillis() + 5_000
        while (filled < 4 && System.currentTimeMillis() < deadline) {
            val read = flow.read(buffer, filled, 4 - filled)
            if (read < 0) break
            filled += read
        }
        assertEquals("ping", String(buffer, 0, filled))
    }

    @Test
    fun closingAFlowReturnsItsIdWithoutClosingTheCarrier() {
        val built = muxSession()
        val first = (built.openFlow(target, FlowKind.Tcp) as DecodeResult.Ok).value
        val second = (built.openFlow(target, FlowKind.Tcp) as DecodeResult.Ok).value
        assertEquals(2, built.liveFlowCount)

        first.close()
        assertEquals("the id comes back", 1, built.liveFlowCount)
        assertEquals("and the carrier stays", 1, built.carrierCount)
        assertEquals("as does the other flow", SetupResult.Ready, second.setupResult)
    }

    @Test
    fun closingTheSessionClosesEveryCarrier() {
        val built = muxSession()
        repeat(MuxHeader.SHARD_FLOW_THRESHOLD * 2) { built.openFlow(target, FlowKind.Tcp) }
        assertEquals(2, built.carrierCount)

        built.close()
        assertEquals(0, built.carrierCount)
        assertTrue(runCatching { built.openFlow(target, FlowKind.Tcp) }.isFailure)
    }

    @Test
    fun aRejectionOnOneFlowLeavesTheCarrierAndTheOthersAlone() {
        val refuseSecond = AtomicInteger(0)
        val built =
            NowhereSession(
                sharedKey = key,
                mux = true,
                connect = {
                    val (clientSide, portalSide) = LoopbackTransport.pair()
                    portals +=
                        FakeMuxPortal(
                            portalSide,
                            { if (refuseSecond.incrementAndGet() == 2) SetupResult.DialFailed else SetupResult.Ready },
                        ).also { it.start() }
                    clientSide
                },
            ).also { session = it }

        assertTrue(built.openFlow(target, FlowKind.Tcp) is DecodeResult.Ok)
        val refused = built.openFlow(target, FlowKind.Tcp)
        assertTrue("the second flow is refused", refused is DecodeResult.Invalid)
        assertTrue("but a third still opens on the same carrier", built.openFlow(target, FlowKind.Tcp) is DecodeResult.Ok)
        assertEquals("and no second connection was needed", 1, built.carrierCount)
        assertEquals("the refused flow's id came back", 2, built.liveFlowCount)
    }

    @Test
    fun theDedicatedPathIsUnchangedWhenMuxIsOff() {
        // Rule 2 of the run: with mux off, this must behave exactly as it did
        // before the Mux layer existed — one connection per flow, closed with it.
        val opened = AtomicInteger(0)
        val built =
            NowhereSession(
                sharedKey = key,
                mux = false,
                connect = {
                    opened.incrementAndGet()
                    // A Portal that answers READY on a dedicated lane: one byte.
                    FakeTransport(peerBytes = byteArrayOf(SetupResult.Ready.byte.toByte()))
                },
            ).also { session = it }

        repeat(6) { assertTrue(built.openFlow(target, FlowKind.Tcp) is DecodeResult.Ok) }
        assertEquals("one connection per flow, as at L1", 6, opened.get())
        assertEquals(6, built.carrierCount)
        assertEquals(6, built.liveFlowCount)
    }

    @Test
    fun theCarrierCountMeansTheSameThingUnderBothModes() {
        // It is the number of TLS connections this session is holding, which is
        // the figure the whole layer exists to reduce — so it must not quietly
        // mean something else on each side.
        val dedicated =
            NowhereSession(
                sharedKey = key,
                mux = false,
                connect = { FakeTransport(peerBytes = byteArrayOf(SetupResult.Ready.byte.toByte())) },
            )
        repeat(4) { dedicated.openFlow(target, FlowKind.Tcp) }
        assertEquals("four flows, four connections", 4, dedicated.carrierCount)
        dedicated.close()

        val multiplexed = muxSession()
        repeat(4) { multiplexed.openFlow(target, FlowKind.Tcp) }
        assertEquals("four flows, one connection", 1, multiplexed.carrierCount)
    }
}
