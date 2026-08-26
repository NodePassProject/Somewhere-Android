// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The pool's one load-bearing promise: an address a flow is using is never
 * given to a different name.
 *
 * Everything else here is bookkeeping. That one rule is the difference between
 * a bounded cache and a cache that occasionally sends one site's traffic to
 * another — silently, with no error at either end, because both halves are
 * behaving exactly as designed.
 */
class FakeIpPoolTest {
    @Test
    fun `the same name gets the same address`() {
        val pool = FakeIpPool()
        val first = pool.allocate("example.com")
        val second = pool.allocate("example.com")
        assertEquals(first, second)
        assertEquals(1, pool.size)
    }

    @Test
    fun `different names get different addresses`() {
        val pool = FakeIpPool()
        assertNotEquals(pool.allocate("one.example"), pool.allocate("two.example"))
    }

    @Test
    fun `an address maps back to the name it was minted for`() {
        val pool = FakeIpPool()
        val offset = pool.allocate("example.com")!!
        assertEquals("example.com", pool.nameFor(FakeIpPool.ipv4(offset)))
        assertEquals("example.com", pool.nameFor(FakeIpPool.ipv6(offset)))
    }

    @Test
    fun `an address outside the range is not ours`() {
        val pool = FakeIpPool()
        pool.allocate("example.com")
        assertNull(pool.nameFor(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
        assertNull(pool.nameFor(ByteArray(16) { 1 }))
        assertNull(pool.nameFor(ByteArray(5)))
    }

    @Test
    fun `exhaustion evicts the oldest idle mapping`() {
        val pool = FakeIpPool(capacity = 4)
        val offsets = (1..4).associateWith { pool.allocate("host$it.example")!! }

        // Touch the first so the second becomes the oldest.
        pool.allocate("host1.example")

        val fresh = pool.allocate("host5.example")
        assertEquals("the evicted slot is reused", offsets.getValue(2), fresh)
        assertEquals("host5.example", pool.nameFor(FakeIpPool.ipv4(fresh!!)))
        assertEquals(4, pool.size)
    }

    @Test
    fun `an address under a live flow is never reassigned`() {
        val pool = FakeIpPool(capacity = 2)
        val held = pool.allocate("held.example")!!
        val idle = pool.allocate("idle.example")!!
        assertEquals("held.example", pool.retain(FakeIpPool.ipv4(held)))

        val fresh = pool.allocate("new.example")
        assertEquals("the idle mapping is the one that goes", idle, fresh)
        assertEquals("held.example", pool.nameFor(FakeIpPool.ipv4(held)))
    }

    @Test
    fun `allocation fails rather than evicting a live mapping`() {
        val pool = FakeIpPool(capacity = 2)
        val first = pool.allocate("one.example")!!
        val second = pool.allocate("two.example")!!
        pool.retain(FakeIpPool.ipv4(first))
        pool.retain(FakeIpPool.ipv4(second))

        assertNull("no address is worth breaking a live flow for", pool.allocate("three.example"))
        assertEquals("one.example", pool.nameFor(FakeIpPool.ipv4(first)))
        assertEquals("two.example", pool.nameFor(FakeIpPool.ipv4(second)))
    }

    @Test
    fun `a released mapping becomes evictable again`() {
        val pool = FakeIpPool(capacity = 1)
        val offset = pool.allocate("one.example")!!
        pool.retain(FakeIpPool.ipv4(offset))
        assertNull(pool.allocate("two.example"))

        pool.release(FakeIpPool.ipv4(offset))
        assertEquals(offset, pool.allocate("two.example"))
    }

    @Test
    fun `one address can carry several flows and the first to finish does not unpin it`() {
        val pool = FakeIpPool(capacity = 1)
        val offset = pool.allocate("busy.example")!!
        val address = FakeIpPool.ipv4(offset)
        repeat(6) { pool.retain(address) }

        repeat(5) { pool.release(address) }
        assertEquals(1, pool.retainedCount)
        assertNull("five of six flows finishing is not all of them", pool.allocate("other.example"))

        pool.release(address)
        assertEquals(0, pool.retainedCount)
        assertEquals(offset, pool.allocate("other.example"))
    }

    @Test
    fun `releasing something never retained is ignored`() {
        val pool = FakeIpPool(capacity = 1)
        val offset = pool.allocate("one.example")!!
        val address = FakeIpPool.ipv4(offset)

        // Teardown paths race and double-release. An under-count would pin the
        // entry forever, which is the failure that never announces itself.
        repeat(4) { pool.release(address) }
        pool.release(byteArrayOf(1, 2, 3, 4))
        assertEquals(0, pool.retainedCount)
        assertEquals(offset, pool.allocate("two.example"))
    }

    @Test
    fun `the pool stays bounded under a long churn of names`() {
        val pool = FakeIpPool(capacity = 32)
        val random = Random(20260826)
        repeat(20_000) { index ->
            val offset = pool.allocate("host${random.nextInt(4_000)}-$index.example")
            assertTrue("allocation must not fail while nothing is retained", offset != null)
            assertTrue("offsets stay inside the pool", offset!! in 1..32)
            assertTrue("the pool never grows past its capacity", pool.size <= 32)
        }
    }

    @Test
    fun `a retained set that fills the pool degrades to refusal, not to corruption`() {
        val pool = FakeIpPool(capacity = 8)
        val random = Random(7)
        val held = mutableListOf<ByteArray>()
        repeat(8) { index ->
            val address = FakeIpPool.ipv4(pool.allocate("held$index.example")!!)
            pool.retain(address)
            held += address
        }

        repeat(500) { assertNull(pool.allocate("transient${random.nextInt()}.example")) }
        held.forEachIndexed { index, address ->
            assertEquals("held$index.example", pool.nameFor(address))
        }
    }

    @Test
    fun `addresses are inside the reserved ranges`() {
        val pool = FakeIpPool(capacity = 300)
        repeat(300) { index ->
            val offset = pool.allocate("host$index.example")!!
            val v4 = FakeIpPool.ipv4(offset)
            assertTrue("$offset is in 198.18.0.0/15", FakeIpPool.isFake(v4))
            assertEquals(198, v4[0].toInt() and 0xFF)
            assertTrue((v4[1].toInt() and 0xFF) in 18..19)
            assertTrue(FakeIpPool.isFake(FakeIpPool.ipv6(offset)))
        }
        assertFalse(FakeIpPool.isFake(byteArrayOf(198.toByte(), 20, 0, 1)))
        assertFalse(FakeIpPool.isFake(byteArrayOf(10, 0, 0, 1)))
        assertFalse(FakeIpPool.isFake(ByteArray(16) { if (it == 0) 0xFD.toByte() else 0 }))
    }

    @Test
    fun `clearing forgets everything`() {
        val pool = FakeIpPool(capacity = 4)
        val offset = pool.allocate("one.example")!!
        pool.retain(FakeIpPool.ipv4(offset))
        pool.clear()
        assertEquals(0, pool.size)
        assertNull(pool.nameFor(FakeIpPool.ipv4(offset)))
        assertEquals(offset, pool.allocate("two.example"))
    }
}
