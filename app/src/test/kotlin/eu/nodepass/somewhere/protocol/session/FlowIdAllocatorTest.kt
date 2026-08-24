// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** NW-P-02: non-zero, unique within the session, monotonic, reusable only after release. */
class FlowIdAllocatorTest {
    @Test
    fun idsAreNeverZero() {
        // Zero is connection-level in a Mux WINDOW frame, so a flow may never
        // carry it.
        val allocator = FlowIdAllocator()
        repeat(1000) { assertNotEquals(0u, allocator.allocate()) }
    }

    @Test
    fun idsAreUniqueWhileLive() {
        val allocator = FlowIdAllocator()
        val seen = (1..5000).map { allocator.allocate() }.toSet()
        assertEquals("every live id must be distinct", 5000, seen.size)
    }

    @Test
    fun idsAreMonotonicUntilSomethingIsReleased() {
        val allocator = FlowIdAllocator()
        val first = allocator.allocate()!!
        val second = allocator.allocate()!!
        val third = allocator.allocate()!!
        assertTrue(first < second && second < third)
    }

    @Test
    fun aReleasedIdIsReusedButNotImmediatelyAfterItsFlowClosed() {
        // The queue is why: handing an id straight back would let a late frame
        // from the closed flow land on its replacement, and the two would be
        // indistinguishable on the wire.
        val allocator = FlowIdAllocator()
        val a = allocator.allocate()!!
        val b = allocator.allocate()!!
        allocator.release(a)
        allocator.release(b)
        assertEquals("released ids come back in release order", a, allocator.allocate())
        assertEquals(b, allocator.allocate())
    }

    @Test
    fun releasingSomethingNeverAllocatedIsIgnored() {
        // Teardown paths race. An allocator that threw here would turn a
        // harmless race into a crash.
        val allocator = FlowIdAllocator()
        allocator.release(999u)
        allocator.release(999u)
        assertEquals(0, allocator.liveCount)
        assertEquals(0, allocator.releasedCount)
    }

    @Test
    fun doubleReleaseDoesNotDuplicateAnId() {
        val allocator = FlowIdAllocator()
        val id = allocator.allocate()!!
        allocator.release(id)
        allocator.release(id)
        assertEquals("a double release must not queue the id twice", 1, allocator.releasedCount)
    }

    @Test
    fun liveIdsAreTracked() {
        val allocator = FlowIdAllocator()
        val id = allocator.allocate()!!
        assertTrue(allocator.isLive(id))
        allocator.release(id)
        assertTrue(!allocator.isLive(id))
    }

    @Test
    fun exhaustionIsReportedRatherThanThrown() {
        // A session that runs out of ids can open another one. A thrown
        // exception would make that the caller's crash instead of its decision.
        val allocator = FlowIdAllocator()
        val field = FlowIdAllocator::class.java.getDeclaredField("next")
        field.isAccessible = true
        (field.get(allocator) as java.util.concurrent.atomic.AtomicLong)
            .set(FlowIdAllocator.MAX_FLOW_ID + 1)
        assertNull("exhaustion returns null", allocator.allocate())
    }

    @Test
    fun concurrentAllocationNeverHandsOutADuplicate() {
        // openFlow is documented as safe from several threads, so this holds it
        // to that rather than trusting the synchronized blocks by inspection.
        val allocator = FlowIdAllocator()
        val pool = Executors.newFixedThreadPool(8)
        val results = java.util.Collections.synchronizedList(mutableListOf<UInt>())
        repeat(8) {
            pool.submit {
                repeat(500) { allocator.allocate()?.let(results::add) }
            }
        }
        pool.shutdown()
        assertTrue("workers should finish", pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals("no duplicates across threads", results.size, results.toSet().size)
    }
}
