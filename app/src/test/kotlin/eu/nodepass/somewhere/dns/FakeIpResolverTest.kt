// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam the whole of A1 exists for: a flow that arrives at a synthetic
 * address leaves as a name.
 */
class FakeIpResolverTest {
    private val pool = FakeIpPool(capacity = 8)

    @Test
    fun `a flow to a synthetic address becomes a domain target`() {
        val offset = pool.allocate("example.com")!!
        val resolution = FakeIpResolver.resolve(pool, FakeIpPool.ipv4(offset), 443)

        assertEquals(Target.Domain("example.com", 443), (resolution.target as DecodeResult.Ok).value)
        assertTrue("the flow now holds the mapping", resolution.retained)
        assertEquals(1, pool.retainedCount)
    }

    @Test
    fun `a flow to a real literal is unchanged`() {
        val octets = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
        val resolution = FakeIpResolver.resolve(pool, octets, 80)

        val target = (resolution.target as DecodeResult.Ok).value as Target.Ip
        assertEquals(80, target.port)
        assertFalse(target.isIpv6)
        assertFalse("nothing was held for a literal", resolution.retained)
    }

    @Test
    fun `an IPv6 literal stays an IPv6 target`() {
        val octets =
            ByteArray(16).also {
                it[0] = 0x20
                it[1] = 0x01
            }
        val target = (FakeIpResolver.resolve(pool, octets, 53).target as DecodeResult.Ok).value as Target.Ip
        assertTrue(target.isIpv6)
    }

    @Test
    fun `a synthetic IPv6 address resolves to the same name as its IPv4 twin`() {
        val offset = pool.allocate("example.com")!!
        val resolution = FakeIpResolver.resolve(pool, FakeIpPool.ipv6(offset), 443)
        assertEquals(Target.Domain("example.com", 443), (resolution.target as DecodeResult.Ok).value)
    }

    @Test
    fun `an address in the range that the pool never minted falls back to a literal`() {
        // A device that cached an address past the tunnel it belonged to. The
        // literal is unroutable at the Portal and says so; guessing a name
        // would send the traffic somewhere.
        val resolution = FakeIpResolver.resolve(pool, FakeIpPool.ipv4(4_000), 443)
        assertTrue((resolution.target as DecodeResult.Ok).value is Target.Ip)
        assertFalse(resolution.retained)
    }

    @Test
    fun `port zero is refused on both paths and leaves nothing held`() {
        val offset = pool.allocate("example.com")!!
        val synthetic = FakeIpResolver.resolve(pool, FakeIpPool.ipv4(offset), 0)
        assertTrue(synthetic.target is DecodeResult.Invalid)
        assertFalse("a target that will not encode must not leave a hold behind", synthetic.retained)
        assertEquals(0, pool.retainedCount)

        assertTrue(FakeIpResolver.resolve(pool, byteArrayOf(1, 1, 1, 1), 0).target is DecodeResult.Invalid)
    }

    @Test
    fun `the mapping survives while the flow does and is released afterwards`() {
        val address = FakeIpPool.ipv4(pool.allocate("held.example")!!)
        repeat(7) { pool.allocate("filler$it.example") }
        val resolution = FakeIpResolver.resolve(pool, address, 443)
        assertTrue(resolution.retained)

        // The pool is full; every allocation from here has to evict something,
        // and the held mapping must not be what goes.
        repeat(50) { pool.allocate("churn$it.example") }
        assertEquals("held.example", pool.nameFor(address))

        pool.release(address)
        assertEquals(0, pool.retainedCount)
    }
}
