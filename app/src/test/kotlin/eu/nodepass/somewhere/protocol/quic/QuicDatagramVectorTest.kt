// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every vector in the `quicDatagram` family. NW-P-20, NW-P-21.
 *
 * The fixture was written when the matrix was, and `verify-vectors.py` has
 * been recomputing these bytes from the specification's prose since before
 * there was any Kotlin to compare them with. This is the comparison.
 */
class QuicDatagramVectorTest {
    private val cases = VectorFixture.cases("quicDatagram")

    @Test
    fun everyPositiveVectorEncodesToItsExpectedBytes() {
        assertEquals("fixture should carry 8 datagram cases", 8, cases.size)
        var checked = 0
        cases.forEach { case ->
            val name = case.str("name")
            when {
                "fragIndex" in case -> {
                    val fragment =
                        QuicDatagram.Fragment(
                            flowId = case.str("flowId").toUInt(),
                            packetId = case.str("packetId").toUInt(),
                            index = case.str("fragIndex").toInt(),
                            count = case.str("fragCount").toInt(),
                            totalLength = case.str("totalLen").toInt(),
                            payload = byteArrayOf(0),
                        )
                    val header = fragment.encode().copyOf(QuicDatagram.FRAGMENT_HEADER_SIZE)
                    assertEquals(name, case.str("expectedHeaderHex"), header.toHex())
                    assertEquals("$name header length", case.str("headerLen").toInt(), header.size)
                    checked++
                }

                name.startsWith("CLOSE") -> {
                    val encoded = QuicDatagram.Close(case.str("flowId").toUInt()).encode()
                    assertEquals(name, case.str("expectedHex"), encoded.toHex())
                    assertEquals("$name header length", case.str("headerLen").toInt(), encoded.size)
                    checked++
                }

                "payloadHex" in case -> {
                    val payload = case.str("payloadHex").hexToByteArrayCompat()
                    val encoded = QuicDatagram.Data(case.str("flowId").toUInt(), payload).encode()
                    assertEquals(name, case.str("expectedHex"), encoded.toHex())
                    checked++
                }

                "payloadLens" in case -> {
                    // Never fragment what fits. The fixture's own arithmetic.
                    val maxDatagram = case.str("maxDatagram").toInt()
                    val expected = case.str("expectedFrames").toInt()
                    (case["payloadLens"] as JsonArray).forEach { element ->
                        val length = element.jsonPrimitive.content.toInt()
                        val planned = QuicDatagram.plan(length, maxDatagram)
                        val frames = (planned as DecodeResult.Ok).value
                        assertEquals("$name len=$length", expected, frames)
                    }
                    checked++
                }
            }
        }
        assertEquals("every case must have been checked", cases.size, checked)
    }

    @Test
    fun theFragmentPlanMatchesTheFixturesOwnArithmetic() {
        // fragmentPayloadMax = maxDatagram - 12, and the 2500-byte case in the
        // fixture is three fragments at a 1200-byte datagram.
        val planned = QuicDatagram.plan(2500, 1200)
        assertEquals(3, (planned as DecodeResult.Ok).value)
    }

    @Test
    fun aFragmentedPacketReassemblesToTheBytesItCameFrom() {
        val payload = ByteArray(2500) { (it * 31 + 7).toByte() }
        val frames = (QuicDatagram.frames(1u, 99u, payload, 1200) as DecodeResult.Ok).value
        assertEquals(3, frames.size)

        val rebuilt =
            frames
                .filterIsInstance<QuicDatagram.Fragment>()
                .sortedBy { it.index }
                .fold(ByteArray(0)) { acc, fragment -> acc + fragment.payload }
        assertTrue("the reassembled packet must be the original", payload.contentEquals(rebuilt))
    }

    @Test
    fun aPacketThatFitsIsOneDataFrameAndCarriesNoFragmentHeader() {
        val payload = ByteArray(96)
        val frames = (QuicDatagram.frames(1u, 99u, payload, 100) as DecodeResult.Ok).value
        assertEquals(1, frames.size)
        assertTrue(frames.single() is QuicDatagram.Data)
    }

    @Test
    fun everyFrameThisProducesDecodesBackToWhatItWas() {
        val payload = ByteArray(2500) { it.toByte() }
        val frames = (QuicDatagram.frames(7u, 42u, payload, 1200) as DecodeResult.Ok).value
        frames.forEach { frame ->
            val decoded = QuicDatagram.decode(frame.encode())
            assertEquals(frame, (decoded as DecodeResult.Ok).value)
        }
    }

    @Test
    fun everyDecoderRejectionIsRefused() {
        // The fixture's decoder rules. The rest belong to reassembly and to the
        // carrier, and are held by DatagramVectorTest and QuicCarrierTest.
        VectorFixture.rejects("quicDatagram").filter { "hex" in it }.forEach { reject ->
            val bytes = reject.str("hex").hexToByteArrayCompat()
            assertTrue(reject.str("name"), QuicDatagram.decode(bytes) is DecodeResult.Invalid)
        }

        val flowId = 1u
        val zeroPacketId =
            QuicDatagram.Fragment(flowId, 1u, 0, 2, 8, byteArrayOf(1)).encode().also {
                it[4] = 0
                it[5] = 0
                it[6] = 0
                it[7] = 0
            }
        assertTrue("packet_id is nonzero", QuicDatagram.decode(zeroPacketId) is DecodeResult.Invalid)

        val indexPastCount = QuicDatagram.Fragment(flowId, 1u, 0, 2, 8, byteArrayOf(1)).encode().also { it[8] = 2 }
        assertTrue("frag_ix is below frag_count", QuicDatagram.decode(indexPastCount) is DecodeResult.Invalid)

        val countOfOne = QuicDatagram.Fragment(flowId, 1u, 0, 2, 8, byteArrayOf(1)).encode().also { it[9] = 1 }
        assertTrue("frag_count is 2..255", QuicDatagram.decode(countOfOne) is DecodeResult.Invalid)

        val greedy = QuicDatagram.Fragment(flowId, 1u, 0, 3, 4, ByteArray(3)).encode()
        assertTrue("a fragment leaves a byte for each other fragment", QuicDatagram.decode(greedy) is DecodeResult.Invalid)
    }

    @Test
    fun bothCodecsProduceTheSameBytes() {
        // Two codecs exist for the same frames — one the sender uses, one the
        // reassembler reads — and a fixture they both pass is what keeps them
        // one format.
        val payload = ByteArray(2500) { it.toByte() }
        val sent = (QuicDatagram.frames(7u, 42u, payload, 1200) as DecodeResult.Ok).value
        val read = (DatagramFrame.plan(7u, 42u, payload, 1200) as DecodeResult.Ok).value
        assertEquals(sent.map { it.encode().toHex() }, read.map { it.encode().toHex() })
    }

    @Test
    fun everyTruncationPointIsRejectedRatherThanCrashing() {
        val whole = QuicDatagram.Fragment(1u, 1u, 0, 2, 8, ByteArray(4)).encode()
        for (length in 0 until whole.size) {
            val truncated = whole.copyOf(length)
            // Either refused, or decoded into something coherent. Never a throw.
            QuicDatagram.decode(truncated)
        }
    }
}
