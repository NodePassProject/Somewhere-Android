// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class PacketIdsTest {
    @Test
    fun theFirstIdIsNotZero() {
        // The case every flow reaches immediately, rather than the wrap-around
        // case no flow will ever reach.
        assertNotEquals(0u, PacketIds().allocate())
    }

    @Test
    fun idsDoNotRepeatWhileAFlowIsBusy() {
        val ids = PacketIds()
        val seen = (1..10_000).map { ids.allocate() }.toSet()
        assertEquals(10_000, seen.size)
        assertTrue("none of them may be zero", seen.none { it == 0u })
    }

    @Test
    fun concurrentAllocationNeverHandsOutADuplicate() {
        val ids = PacketIds()
        val ready = CountDownLatch(1)
        val handed = java.util.Collections.synchronizedList(mutableListOf<UInt>())
        val threads =
            (1..8).map {
                thread {
                    ready.await()
                    repeat(500) { handed += ids.allocate() }
                }
            }
        ready.countDown()
        threads.forEach { it.join() }
        assertEquals(4_000, handed.toSet().size)
    }

    @Test
    fun replanningTakesAFreshIdRatherThanReusingTheOne() {
        // A packet partly sent under one datagram size and re-planned under a
        // smaller one has two fragment layouts. Sharing an id between them lets
        // a peer reassemble a packet that was never sent.
        val ids = PacketIds()
        val first = ids.allocate()
        assertNotEquals(first, ids.replan())
    }

    @Test
    fun aShrunkenDatagramSizeReallyDoesProduceADifferentLayout() {
        // The reason the rule exists, stated as arithmetic rather than as
        // prose: the same packet plans into a different number of fragments.
        val length = 2500
        val before = (QuicDatagram.plan(length, 1200) as DecodeResult.Ok).value
        val after = (QuicDatagram.plan(length, 600) as DecodeResult.Ok).value
        assertNotEquals(before, after)
    }
}
