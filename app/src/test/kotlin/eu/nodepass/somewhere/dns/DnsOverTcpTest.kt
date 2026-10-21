// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsOverTcpTest {
    private val one = byteArrayOf(1, 2, 3)
    private val two = ByteArray(300) { it.toByte() }

    @Test
    fun `a framed message comes back whole`() {
        val framer = DnsOverTcp()
        val out = framer.feed(DnsOverTcp.frame(one))
        assertArrayEquals(one, out.single())
    }

    @Test
    fun `two messages in one read are two messages`() {
        val out = DnsOverTcp().feed(DnsOverTcp.frame(one) + DnsOverTcp.frame(two))
        assertEquals(2, out.size)
        assertArrayEquals(two, out[1])
    }

    @Test
    fun `a message split anywhere, the length prefix included, waits for the rest`() {
        val stream = DnsOverTcp.frame(two) + DnsOverTcp.frame(one)
        for (cut in 1 until stream.size) {
            val framer = DnsOverTcp()
            val first = framer.feed(stream.copyOfRange(0, cut))
            val second = framer.feed(stream.copyOfRange(cut, stream.size))
            val all = first + second
            assertEquals("cut at $cut", 2, all.size)
            assertArrayEquals(two, all[0])
            assertArrayEquals(one, all[1])
        }
    }

    @Test
    fun `nothing whole is nothing`() {
        assertTrue(DnsOverTcp().feed(byteArrayOf(0)).isEmpty())
        assertTrue(DnsOverTcp().feed(byteArrayOf(0, 5, 1, 2)).isEmpty())
    }

    @Test
    fun `an empty message is a message`() {
        assertEquals(0, DnsOverTcp().feed(byteArrayOf(0, 0)).single().size)
    }
}
