// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.vpn.TunConfiguration.Cidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CidrSetTest {
    private val v4 = Cidr("0.0.0.0", 0)
    private val v6 = Cidr("::", 0)

    private fun covers(
        cidrs: List<Cidr>,
        address: String,
    ): Boolean {
        val value = BigInteger(1, TunConfiguration.parse(address)!!)
        return cidrs.any { cidr ->
            val range = CidrSet.range(cidr)!!
            range.bytes == TunConfiguration.parse(address)!!.size && value >= range.first && value <= range.last
        }
    }

    @Test
    fun `nothing excluded is the universe`() {
        assertEquals(listOf(v4), CidrSet.subtract(v4, emptyList()))
    }

    @Test
    fun `excluding one half leaves the other`() {
        assertEquals(listOf(Cidr("128.0.0.0", 1)), CidrSet.subtract(v4, listOf(Cidr("0.0.0.0", 1))))
    }

    @Test
    fun `the private ranges come out and nothing else does`() {
        val local = listOf(Cidr("10.0.0.0", 8), Cidr("172.16.0.0", 12), Cidr("192.168.0.0", 16), Cidr("169.254.0.0", 16))
        val routes = CidrSet.subtract(v4, local)
        listOf("10.1.2.3", "172.20.0.1", "192.168.1.1", "169.254.9.9").forEach {
            assertFalse("$it should be outside", covers(routes, it))
        }
        listOf("0.0.0.1", "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "8.8.8.8", "255.255.255.255")
            .forEach { assertTrue("$it should be inside", covers(routes, it)) }
        // Minimal: no two prefixes are halves of one larger prefix.
        val ranges = routes.map { CidrSet.range(it)!! to it.prefix }
        for ((a, prefixA) in ranges) {
            for ((b, prefixB) in ranges) {
                if (a == b || prefixA != prefixB) continue
                val adjacent = a.last.add(BigInteger.ONE) == b.first
                val alignedPair = a.first.testBit(32 - prefixA).not()
                assertFalse("${a.first}/$prefixA and its sibling could merge", adjacent && alignedPair)
            }
        }
    }

    @Test
    fun `overlapping and out-of-family exclusions are handled`() {
        val routes = CidrSet.subtract(v4, listOf(Cidr("10.0.0.0", 8), Cidr("10.1.0.0", 16), Cidr("fd00::", 8)))
        assertFalse(covers(routes, "10.1.0.5"))
        assertTrue(covers(routes, "11.0.0.0"))
    }

    @Test
    fun `ipv6 complement works the same way`() {
        val routes = CidrSet.subtract(v6, listOf(Cidr("fc00::", 7), Cidr("fe80::", 10)))
        assertFalse(covers(routes, "fd66::2"))
        assertFalse(covers(routes, "fe80::1"))
        assertTrue(covers(routes, "2001:db8::1"))
        assertTrue(covers(routes, "ff02::1"))
    }

    @Test
    fun `parsing clears host bits and refuses nonsense`() {
        assertEquals(Cidr("192.168.0.0", 16), CidrSet.parse("192.168.7.9/16"))
        assertEquals(Cidr("1.2.3.4", 32), CidrSet.parse("1.2.3.4"))
        assertEquals(Cidr("fd00:0:0:0:0:0:0:0", 8), CidrSet.parse("fd12::/8"))
        assertNull(CidrSet.parse("1.2.3.4/33"))
        assertNull(CidrSet.parse("example.com/8"))
        assertNull(CidrSet.parse("1.2.3/8"))
    }
}
