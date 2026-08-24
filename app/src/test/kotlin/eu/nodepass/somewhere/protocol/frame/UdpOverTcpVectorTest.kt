// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every vector in the `uot` family. NW-P-07. */
class UdpOverTcpVectorTest {
    private val cases = VectorFixture.cases("uot")
    private val rejects = VectorFixture.rejects("uot")

    @Test
    fun everyPositiveVectorIsReproduced() {
        assertEquals("fixture should carry 3 uot cases", 3, cases.size)
        var checked = 0
        cases.forEach { case ->
            val name = case.str("name")
            when {
                "payloadHex" in case -> {
                    val payload = case.str("payloadHex").hexToByteArrayCompat()
                    val encoded = (UdpOverTcp.encode(payload) as DecodeResult.Ok).value
                    assertEquals(name, case.str("expectedHex"), encoded.toHex())
                    checked++
                }
                "payloadUtf8" in case -> {
                    val encoded =
                        (UdpOverTcp.encode(case.str("payloadUtf8").encodeToByteArray()) as DecodeResult.Ok).value
                    assertEquals(name, case.str("expectedHex"), encoded.toHex())
                    checked++
                }
                "declaredLen" in case -> {
                    // Header-only vector: the prefix for a declared length.
                    val declared = case.str("declaredLen").toInt()
                    val header = (UdpOverTcp.encode(ByteArray(declared)) as DecodeResult.Ok).value
                    assertEquals(name, case.str("expectedHeaderHex"), header.copyOf(2).toHex())
                    checked++
                }
                else -> error("unhandled uot case shape: $name")
            }
        }
        assertEquals("every case must be exercised", cases.size, checked)
    }

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 3 uot rejects", 3, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            when {
                name.contains("above UOT_PACKET_MAX") -> {
                    val reason = UdpOverTcp.encode(ByteArray(UdpOverTcp.PACKET_MAX + 1)).reasonOrNull()
                    assertTrue(name, reason is UotReason.PayloadTooLarge)
                    checked++
                }
                name.contains("EOF after one length byte") -> {
                    val next = UdpOverTcp.next(byteArrayOf(0x00))
                    assertEquals(name, UotReason.TruncatedLength, (next as UdpOverTcp.Next.Invalid).reason)
                    checked++
                }
                name.contains("before the declared payload completes") -> {
                    val next = UdpOverTcp.next(byteArrayOf(0x00, 0x05, 0x61, 0x62))
                    assertTrue(name, (next as UdpOverTcp.Next.Invalid).reason is UotReason.TruncatedPayload)
                    checked++
                }
                else -> error("unhandled uot reject shape: $name")
            }
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    @Test
    fun anEmptyPacketIsDataAndNotATerminator() {
        // UDP carries empty datagrams. Treating a zero length as end-of-stream
        // would silently drop them.
        val next = UdpOverTcp.next(byteArrayOf(0x00, 0x00))
        val packet = (next as UdpOverTcp.Next.Ready).packet
        assertEquals(0, packet.payload.size)
        assertEquals(2, packet.consumed)
    }

    @Test
    fun aCleanEndIsNotAnError() {
        // Ending on a packet boundary is ordinary shutdown, not truncation.
        assertEquals(UdpOverTcp.Next.EndOfStream, UdpOverTcp.next(ByteArray(0)))
        assertEquals(UdpOverTcp.Next.EndOfStream, UdpOverTcp.next(byteArrayOf(0x00, 0x00), offset = 2))
    }

    @Test
    fun truncatedLengthAndTruncatedPayloadAreDifferentFailures() {
        // One means the peer stopped mid-header; the other means it announced
        // more than it sent. Collapsing them loses which happened.
        val midHeader = UdpOverTcp.next(byteArrayOf(0x12)) as UdpOverTcp.Next.Invalid
        val shortBody = UdpOverTcp.next(byteArrayOf(0x00, 0x09, 0x61)) as UdpOverTcp.Next.Invalid
        assertEquals(UotReason.TruncatedLength, midHeader.reason)
        assertTrue(shortBody.reason is UotReason.TruncatedPayload)
        assertTrue(midHeader.reason != shortBody.reason)
    }

    @Test
    fun consecutivePacketsSitBackToBack() {
        val stream =
            (UdpOverTcp.encode("one".encodeToByteArray()) as DecodeResult.Ok).value +
                (UdpOverTcp.encode(ByteArray(0)) as DecodeResult.Ok).value +
                (UdpOverTcp.encode("three".encodeToByteArray()) as DecodeResult.Ok).value

        val packets = (UdpOverTcp.decodeAll(stream) as DecodeResult.Ok).value
        assertEquals(3, packets.size)
        assertEquals("one", String(packets[0]))
        assertEquals(0, packets[1].size)
        assertEquals("three", String(packets[2]))
    }

    @Test
    fun theLargestLegalPacketRoundTrips() {
        val payload = ByteArray(UdpOverTcp.PACKET_MAX) { (it % 251).toByte() }
        val encoded = (UdpOverTcp.encode(payload) as DecodeResult.Ok).value
        assertEquals(UdpOverTcp.PACKET_MAX + 2, encoded.size)
        val packet = (UdpOverTcp.next(encoded) as UdpOverTcp.Next.Ready).packet
        assertTrue(payload.contentEquals(packet.payload))
    }

    @Test
    fun everyTruncationPointOfAStreamIsRejectedRatherThanCrashing() {
        val full = (UdpOverTcp.encode("payload".encodeToByteArray()) as DecodeResult.Ok).value
        for (length in 1 until full.size) {
            val next = UdpOverTcp.next(full.copyOf(length))
            assertTrue("prefix of $length must not decode", next is UdpOverTcp.Next.Invalid)
        }
    }

    @Test
    fun aDeclaredLengthCannotDriveAnUnboundedRead() {
        // Declares 65535 with three bytes present.
        val hostile = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x61)
        val next = UdpOverTcp.next(hostile) as UdpOverTcp.Next.Invalid
        assertTrue(next.reason is UotReason.TruncatedPayload)
    }
}
