// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Placement. NW-P-17, `docs/protocol.md` section 3.
 *
 * A fake clock throughout: the idle rule is thirty seconds, and a test that
 * actually waited them out would take half a minute to say something a counter
 * can say instantly — and would still be measuring the machine rather than the
 * rule.
 */
class MuxShardSetTest {
    private val key = (SharedKey.of("shard-test-key") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(10, 0, 0, 1), 443) as DecodeResult.Ok).value

    private var now = 0L
    private val portals = mutableListOf<FakeMuxPortal>()
    private var set: MuxShardSet? = null
    private var nextFlowId = 1u

    @After
    fun tearDown() {
        runCatching { set?.close() }
        portals.forEach { runCatching { it.stop() } }
    }

    private fun shardSet(): MuxShardSet =
        MuxShardSet(
            openCarrier = {
                val (clientSide, portalSide) = LoopbackTransport.pair()
                portals += FakeMuxPortal(portalSide, { SetupResult.Ready }).also { it.start() }
                val carrier = MuxCarrier(clientSide, key, SessionId.random()) { now }
                when (val started = carrier.start()) {
                    is DecodeResult.Ok -> DecodeResult.Ok(carrier)
                    is DecodeResult.Invalid -> started
                }
            },
            clock = { now },
            automaticReaping = false,
        ).also { set = it }

    /** Places a flow and opens it, as the session does. */
    private fun openOne(shards: MuxShardSet): Pair<MuxCarrier, Flow> {
        var placed: MuxCarrier? = null
        val flow =
            shards.placing { carrier, slot ->
                placed = carrier
                carrier.open(target, FlowKind.Tcp, nextFlowId++, ByteArray(0), slot)
            }
        return placed!! to (flow as DecodeResult.Ok).value
    }

    @Test
    fun theFirstFlowOpensTheFirstShard() {
        val shards = shardSet()
        assertEquals("shards open lazily", 0, shards.liveShardCount)
        openOne(shards)
        assertEquals(1, shards.liveShardCount)
    }

    @Test
    fun aNewShardOpensOnlyOnceEveryLiveOneIsFull() {
        // The boundary the specification names: four active flows per shard.
        // Four is one shard; the fifth is what opens the second.
        val shards = shardSet()
        val carriers = (1..MuxHeader.SHARD_FLOW_THRESHOLD).map { openOne(shards).first }
        assertEquals("four flows still fit on one carrier", 1, shards.liveShardCount)
        carriers.forEach { assertSame("and they are all on the same one", carriers.first(), it) }

        val fifth = openOne(shards).first
        assertEquals("the fifth opens a second", 2, shards.liveShardCount)
        assertNotSame(carriers.first(), fifth)
    }

    @Test
    fun theLeastLoadedLiveShardIsChosen() {
        // Not first-fit: flows do not end in the order they began, so a carrier
        // that has lost three of its four has room, and first-fit would hand
        // out a carrier with three flows while one with a single flow sat there.
        //
        // Distinguishing the two needs *two* carriers with room and different
        // loads, with the busier one earlier in the list. The first version of
        // this test had one emptied carrier and passed under first-fit too,
        // which is to say it asserted nothing about the rule in its name.
        val shards = shardSet()
        val opened = (1..MuxHeader.SHARD_FLOW_THRESHOLD * 2 + 1).map { openOne(shards) }
        assertEquals(3, shards.liveShardCount)

        val carriers = opened.map { it.first }.distinct()
        val (first, second) = carriers[0] to carriers[1]

        // Leave the earliest carrier busiest, and the one after it nearly empty.
        opened.filter { it.first === first }.take(1).forEach { it.second.close() }
        opened.filter { it.first === second }.take(3).forEach { it.second.close() }
        assertEquals(MuxHeader.SHARD_FLOW_THRESHOLD - 1, first.activeFlowCount)
        assertEquals(1, second.activeFlowCount)

        val chosen = (shards.place() as DecodeResult.Ok).value
        assertNotSame("the busiest carrier with room must not be chosen", first, chosen)
        assertEquals("the least-loaded one is", 1, chosen.activeFlowCount)
        assertEquals("and nothing new was opened", 3, shards.liveShardCount)
    }

    @Test
    fun aFlowStaysOnTheShardItWasPlacedOn() {
        // Placed, never migrated: moving a flow would need a new stream id on a
        // different connection, with the Portal's pairing state left behind and
        // no way to tell it.
        val shards = shardSet()
        val (carrier, flow) = openOne(shards)
        repeat(MuxHeader.SHARD_FLOW_THRESHOLD * 3) { openOne(shards) }
        assertTrue("more carriers exist now", shards.liveShardCount > 1)
        assertEquals("but the first flow is still where it started", 1, flow.id.toInt())
        assertTrue("on a carrier that is still up", carrier.isOpen)
        assertTrue("holding it", carrier.activeFlowCount >= 1)
    }

    @Test
    fun aFullyIdleShardClosesAfterThirtySeconds() {
        val shards = shardSet()
        val (carrier, flow) = openOne(shards)
        flow.close()
        assertEquals(0, carrier.activeFlowCount)

        now += (MuxHeader.SHARD_IDLE_CLOSE_SECONDS - 1) * 1_000L
        shards.reap()
        assertEquals("one second short is not thirty seconds", 1, shards.liveShardCount)

        now += 1_000
        shards.reap()
        assertEquals(0, shards.liveShardCount)
        assertTrue("and the carrier is really closed", !carrier.isOpen)
    }

    @Test
    fun aFlowArrivingJustBeforeTheDeadlineKeepsTheShard() {
        val shards = shardSet()
        val (carrier, flow) = openOne(shards)
        flow.close()

        now += MuxHeader.SHARD_IDLE_CLOSE_SECONDS * 1_000L - 100
        // A flow at 29.9 seconds. The clock is what the carrier reads, so this
        // is the same race a real one has, without the wait.
        val (second, _) = openOne(shards)
        assertSame("it should land on the shard that is still there", carrier, second)

        shards.reap()
        assertEquals("a shard with a live flow is not idle at all", 1, shards.liveShardCount)
        assertTrue(carrier.isOpen)
    }

    @Test
    fun aShardWithAnyLiveFlowIsNeverReaped() {
        val shards = shardSet()
        val (carrier, _) = openOne(shards)
        now += MuxHeader.SHARD_IDLE_CLOSE_SECONDS * 10_000L
        shards.reap()
        assertEquals(1, shards.liveShardCount)
        assertTrue(carrier.isOpen)
    }

    @Test
    fun aShardWhoseCarrierDiedIsNotAPlacementCandidate() {
        val shards = shardSet()
        val (carrier, _) = openOne(shards)
        carrier.close()

        val next = (shards.place() as DecodeResult.Ok).value
        assertNotSame("a dead carrier must not be handed out", carrier, next)
        assertTrue(next.isOpen)
        assertEquals("and the dead one is gone", 1, shards.liveShardCount)
    }

    @Test
    fun closingTheSetClosesEveryShard() {
        val shards = shardSet()
        val carriers = (1..MuxHeader.SHARD_FLOW_THRESHOLD * 2).map { openOne(shards).first }.distinct()
        assertEquals(2, carriers.size)

        shards.close()
        carriers.forEach { assertTrue("every carrier closes with the set", !it.isOpen) }
        assertEquals(0, shards.liveShardCount)
        assertTrue(shards.place() is DecodeResult.Invalid)
    }

    @Test
    fun aBurstOfSimultaneousFlowsDoesNotOpenACarrierEach() {
        // The defect this class was rewritten for, and it was found on a
        // device rather than here: sixteen concurrent flows opened fifteen TLS
        // connections. Every thread read the same empty carrier set, every one
        // decided a carrier was needed, and every one opened one — multiplexing
        // nothing, under exactly the load multiplexing exists for.
        //
        // Two things fix it and both are asserted by this test: a placement
        // reserves its slot, so the next thread sees the carrier as loaded
        // before the flow is open; and only one carrier is opened at a time, so
        // threads that find nothing with room wait rather than racing.
        val shards = shardSet()
        val flows = 16
        val ready = CountDownLatch(1)
        val done = CountDownLatch(flows)
        val failures = AtomicInteger(0)

        val threads =
            (1..flows).map {
                thread {
                    ready.await()
                    runCatching { openOne(shards) }.onFailure { failures.incrementAndGet() }
                    done.countDown()
                }
            }
        ready.countDown()
        assertTrue("every flow should have opened", done.await(60, TimeUnit.SECONDS))
        threads.forEach { it.join(5_000) }

        assertEquals("no flow may fail", 0, failures.get())
        assertEquals(flows, shards.activeFlowCount)
        assertEquals(
            "sixteen simultaneous flows over a density of four",
            (flows + MuxHeader.SHARD_FLOW_THRESHOLD - 1) / MuxHeader.SHARD_FLOW_THRESHOLD,
            shards.liveShardCount,
        )
    }

    @Test
    fun nConcurrentFlowsUseCeilingOfNOverFourCarriers() {
        // The claim L2 exists to make, stated as arithmetic: sixteen flows
        // should cost four connections rather than sixteen.
        val shards = shardSet()
        val flows = 16
        repeat(flows) { openOne(shards) }
        assertEquals(
            "sixteen flows over a threshold of four",
            (flows + MuxHeader.SHARD_FLOW_THRESHOLD - 1) / MuxHeader.SHARD_FLOW_THRESHOLD,
            shards.liveShardCount,
        )
        assertEquals(flows, shards.activeFlowCount)
    }
}
