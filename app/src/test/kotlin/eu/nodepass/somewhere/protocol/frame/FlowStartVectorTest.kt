// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Drives every vector in the `flowStart` family, `docs/protocol.md` § FlowStart and Target. */
class FlowStartVectorTest {
    private val cases = VectorFixture.cases("flowStart")

    private fun kindOf(name: String): FlowKind = if (name == "TCP") FlowKind.Tcp else FlowKind.Udp

    @Test
    fun everyVectorIsReproduced() {
        assertEquals("fixture should carry 5 flowStart cases", 5, cases.size)
        cases.forEach { case ->
            val name = case.str("name")
            when {
                "decodeHex" in case -> {
                    val decoded = FlowStart.decode(case.str("decodeHex").hexToByteArrayCompat()[0])
                    assertEquals(name, kindOf(case.str("expectedKind")), decoded.kind)
                }
                "target" in case -> {
                    val (host, port) = case.str("target").split(':')
                    val target = (Target.ofIpv4(InetAddress.getByName(host).address, port.toInt()) as DecodeResult.Ok).value
                    val bytes = byteArrayOf(FlowStart(kindOf(case.str("kind"))).encode()) + target.encode()
                    assertEquals(name, case.str("expectedHex"), bytes.toHex())
                }
                else -> {
                    val bytes = byteArrayOf(FlowStart(kindOf(case.str("kind"))).encode())
                    assertEquals(name, case.str("expectedHex"), bytes.toHex())
                }
            }
        }
    }

    @Test
    fun everyByteDecodesAndOnlyBitZeroMatters() {
        // Reserved bits are ignored on receipt, so no byte is an error: a
        // decoder that rejected one would turn a future extension into an
        // interoperability failure.
        for (value in 0..255) {
            val kind = FlowStart.decode(value.toByte()).kind
            assertEquals("byte $value", if (value and 1 == 0) FlowKind.Tcp else FlowKind.Udp, kind)
        }
    }

    @Test
    fun senderSetsTheReservedBitsToZero() {
        FlowKind.entries.forEach { kind ->
            assertEquals(kind.bit, FlowStart(kind).encode().toInt())
        }
    }

    @Test
    fun anAbsentByteIsReported() {
        assertTrue(FlowStart.decode(ByteArray(0)) is DecodeResult.Invalid)
        assertEquals(FlowKind.Udp, FlowStart.decode(byteArrayOf(0, 1), 1).valueOrNull()?.kind)
    }
}
