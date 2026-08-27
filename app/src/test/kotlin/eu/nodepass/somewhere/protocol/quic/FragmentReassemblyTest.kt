// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The reassembly rules, every one of which discards rather than repairs.
 *
 * A fake clock throughout: the lifetime is ten seconds, and a test that waited
 * them out would take ten seconds to say what a counter says at once — and
 * would be measuring the machine rather than the rule.
 */
class FragmentReassemblyTest {
    private var now = 0L

    private fun reassembly(
        slots: Int = FragmentReassembly.MAX_SLOTS,
        bytes: Int = FragmentReassembly.MAX_BYTES,
    ) = FragmentReassembly({ now }, maxSlots = slots, maxBytes = bytes)

    private fun fragment(
        index: Int,
        count: Int = 2,
        flowId: UInt = 1u,
        packetId: UInt = 1u,
        totalLength: Int = 8,
        payload: ByteArray = ByteArray(4) { (index * 4 + it).toByte() },
    ) = QuicDatagram.Fragment(flowId, packetId, index, count, totalLength, payload)

    @Test
    fun twoFragmentsBecomeThePacketTheyCameFrom() {
        val reassembly = reassembly()
        assertEquals(FragmentReassembly.Outcome.Held, reassembly.accept(fragment(0)))
        val outcome = reassembly.accept(fragment(1))
        assertTrue(outcome is FragmentReassembly.Outcome.Complete)
        assertTrue(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
                .contentEquals((outcome as FragmentReassembly.Outcome.Complete).payload),
        )
        assertEquals("the slot is given back", 0, reassembly.activeSlots)
        assertEquals(0, reassembly.bytesHeld)
    }

    @Test
    fun fragmentsMayArriveInAnyOrder() {
        val reassembly = reassembly()
        reassembly.accept(fragment(1))
        val outcome = reassembly.accept(fragment(0))
        assertTrue(outcome is FragmentReassembly.Outcome.Complete)
    }

    @Test
    fun anIdenticalDuplicateIsIgnoredAndADifferingOneDiscardsThePacket() {
        val reassembly = reassembly()
        reassembly.accept(fragment(0))
        assertEquals("networks duplicate; that is ordinary", FragmentReassembly.Outcome.Held, reassembly.accept(fragment(0)))

        val differing = fragment(0, payload = byteArrayOf(9, 9, 9, 9))
        val outcome = reassembly.accept(differing)
        assertTrue("there is no way to know which copy is real", outcome is FragmentReassembly.Outcome.Discarded)
        assertEquals(0, reassembly.activeSlots)
    }

    @Test
    fun aFragmentThatDisagreesAboutCountOrLengthDiscardsThePacket() {
        for (odd in listOf(fragment(1, count = 3), fragment(1, totalLength = 9))) {
            val reassembly = reassembly()
            reassembly.accept(fragment(0))
            val outcome = reassembly.accept(odd)
            assertTrue(outcome is FragmentReassembly.Outcome.Discarded)
            assertEquals(0, reassembly.activeSlots)
        }
    }

    @Test
    fun aPacketThatDoesNotAddUpToItsDeclaredLengthIsDiscarded() {
        val reassembly = reassembly()
        reassembly.accept(fragment(0, totalLength = 99))
        val outcome = reassembly.accept(fragment(1, totalLength = 99))
        assertTrue(outcome is FragmentReassembly.Outcome.Discarded)
        assertTrue(
            (outcome as FragmentReassembly.Outcome.Discarded).why.contains("claims 99"),
        )
    }

    @Test
    fun slotsAreBoundedAndAFullTableRefusesNewPacketsRatherThanGrowing() {
        val reassembly = reassembly(slots = 2)
        reassembly.accept(fragment(0, packetId = 1u))
        reassembly.accept(fragment(0, packetId = 2u))
        assertEquals(2, reassembly.activeSlots)

        val outcome = reassembly.accept(fragment(0, packetId = 3u))
        assertTrue(outcome is FragmentReassembly.Outcome.Discarded)
        assertEquals("and the ones already held are untouched", 2, reassembly.activeSlots)
    }

    @Test
    fun bytesAreBoundedSeparatelyFromSlots() {
        // Slots alone bound the count and not the size: a peer sending one
        // fragment of each of 64 enormous packets pins megabytes.
        val reassembly = reassembly(bytes = 16)
        reassembly.accept(fragment(0, packetId = 1u, payload = ByteArray(16)))
        val outcome = reassembly.accept(fragment(0, packetId = 2u, payload = ByteArray(4)))
        assertTrue(outcome is FragmentReassembly.Outcome.Discarded)
    }

    @Test
    fun aPacketWhoseLastFragmentNeverComesGivesUpItsSlot() {
        val reassembly = reassembly()
        reassembly.accept(fragment(0))
        assertEquals(1, reassembly.activeSlots)

        now += FragmentReassembly.LIFETIME_MILLIS - 1
        reassembly.expire()
        assertEquals("one millisecond short is not ten seconds", 1, reassembly.activeSlots)

        now += 1
        reassembly.expire()
        assertEquals(0, reassembly.activeSlots)
        assertEquals(0, reassembly.bytesHeld)
    }

    @Test
    fun closingAFlowForgetsItsHalfAssembledPackets() {
        val reassembly = reassembly()
        reassembly.accept(fragment(0, flowId = 1u, packetId = 1u))
        reassembly.accept(fragment(0, flowId = 2u, packetId = 1u))
        reassembly.forget(1u)
        assertEquals(1, reassembly.activeSlots)
        assertEquals("and its bytes with it", 4, reassembly.bytesHeld)
    }

    @Test
    fun packetsOnDifferentFlowsWithTheSameIdDoNotCollide() {
        // Keyed by (flowId, packetId): packet ids are per flow, so two flows
        // both using 1 is ordinary and must not merge into one packet.
        val reassembly = reassembly()
        reassembly.accept(fragment(0, flowId = 1u, payload = byteArrayOf(1, 1, 1, 1)))
        val outcome = reassembly.accept(fragment(1, flowId = 2u, payload = byteArrayOf(2, 2, 2, 2)))
        assertEquals(FragmentReassembly.Outcome.Held, outcome)
        assertEquals(2, reassembly.activeSlots)
    }

    @Test
    fun arbitraryFragmentsNeitherCrashNorGrowWithoutBound() {
        val random = Random(20260827)
        val reassembly = reassembly(slots = 8, bytes = 4096)
        repeat(20_000) {
            val count = random.nextInt(2, 6)
            reassembly.accept(
                QuicDatagram.Fragment(
                    flowId = random.nextInt(1, 4).toUInt(),
                    packetId = random.nextInt(1, 6).toUInt(),
                    index = random.nextInt(0, count),
                    count = count,
                    totalLength = random.nextInt(1, 64),
                    payload = ByteArray(random.nextInt(1, 32)),
                ),
            )
            now += random.nextInt(0, 200)
            assertTrue("slots must stay bounded", reassembly.activeSlots <= 8)
            assertTrue("bytes must stay bounded", reassembly.bytesHeld <= 4096)
        }
    }
}
