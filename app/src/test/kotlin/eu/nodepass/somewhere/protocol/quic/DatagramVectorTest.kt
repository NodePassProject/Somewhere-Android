// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every vector in the `quicDatagram` family, `docs/protocol.md` § QUIC datagrams. */
class DatagramVectorTest {
    private val cases = VectorFixture.cases("quicDatagram")
    private val rejects = VectorFixture.rejects("quicDatagram")

    private fun readyReassembler() = DatagramReassembler().also { it.markReady() }

    // ── Positive vectors ────────────────────────────────────────────────────

    @Test
    fun everyPositiveVectorIsReproduced() {
        assertEquals("fixture should carry 8 quicDatagram cases", 8, cases.size)
        var checked = 0
        cases.forEach { case ->
            val name = case.str("name")
            when {
                name.startsWith("DATA") -> {
                    val frame =
                        DatagramFrame.Data(
                            case.str("flowId").toUInt(),
                            case.str("payloadHex").hexToByteArrayCompat(),
                        )
                    assertEquals(name, case.str("expectedHex"), frame.encode().toHex())
                    checked++
                }
                name.startsWith("CLOSE") -> {
                    val frame = DatagramFrame.Close(case.str("flowId").toUInt())
                    assertEquals(name, case.str("expectedHex"), frame.encode().toHex())
                    checked++
                }
                name.startsWith("FRAGMENT") -> {
                    val frame =
                        DatagramFrame.Fragment(
                            flowId = case.str("flowId").toUInt(),
                            packetId = case.str("packetId").toUInt(),
                            index = case.str("fragIndex").toInt(),
                            count = case.str("fragCount").toInt(),
                            totalLength = case.str("totalLen").toInt(),
                            payload = ByteArray(0),
                        )
                    assertEquals(name, case.str("expectedHeaderHex"), frame.encode().toHex())
                    checked++
                }
                name.startsWith("never fragment") -> {
                    val maxDatagram = case.str("maxDatagram").toInt()
                    val expected = case.str("expectedFrames").toInt()
                    case["payloadLens"]!!.jsonArray.forEach { element ->
                        val length = element.jsonPrimitive.content.toInt()
                        val planned =
                            (DatagramFrame.plan(1u, 1u, ByteArray(length), maxDatagram) as DecodeResult.Ok).value
                        assertEquals("$name at length $length", expected, planned.size)
                        assertTrue("$name should stay DATA", planned.single() is DatagramFrame.Data)
                    }
                    checked++
                }
                else -> error("unhandled quicDatagram case shape: $name")
            }
        }
        assertEquals("every case must be exercised", cases.size, checked)
    }

    @Test
    fun anEmptyDatagramPayloadIsValid() {
        val frame = DatagramFrame.Data(16909060u, ByteArray(0))
        assertEquals("01020304", frame.encode().toHex())
        val decoded = (DatagramFrame.decode(frame.encode()) as DecodeResult.Ok).value
        assertEquals(0, (decoded as DatagramFrame.Data).payload.size)
    }

    @Test
    fun everyFrameKindRoundTrips() {
        listOf(
            DatagramFrame.Data(7u, byteArrayOf(1, 2, 3)),
            DatagramFrame.Close(7u),
            DatagramFrame.Fragment(7u, 9u, 0, 2, 100, byteArrayOf(4, 5)),
        ).forEach { original ->
            assertEquals(original, (DatagramFrame.decode(original.encode()) as DecodeResult.Ok).value)
        }
    }

    // ── Rejection vectors ───────────────────────────────────────────────────

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 11 quicDatagram rejects", 11, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            when (name) {
                "type 3" -> {
                    val reason = DatagramFrame.decode(reject.str("hex").hexToByteArrayCompat()).reasonOrNull()
                    assertEquals(name, DatagramReason.InvalidType, reason)
                    checked++
                }
                "flow id zero" -> {
                    val reason = DatagramFrame.decode(reject.str("hex").hexToByteArrayCompat()).reasonOrNull()
                    assertEquals(name, DatagramReason.FlowIdZero, reason)
                    checked++
                }
                "CLOSE with trailing bytes" -> {
                    val reason = DatagramFrame.decode(reject.str("hex").hexToByteArrayCompat()).reasonOrNull()
                    assertEquals(name, DatagramReason.ClosePayload, reason)
                    checked++
                }
                "truncated base word" -> {
                    val reason = DatagramFrame.decode(reject.str("hex").hexToByteArrayCompat()).reasonOrNull()
                    assertTrue(name, reason is DatagramReason.Truncated)
                    checked++
                }
                "packet id zero" -> {
                    val bytes = DatagramFrame.Fragment(1u, 0u, 0, 2, 10, ByteArray(1)).encode()
                    assertEquals(name, DatagramReason.PacketIdZero, DatagramFrame.decode(bytes).reasonOrNull())
                    checked++
                }
                "fragIndex >= fragCount" -> {
                    val bytes = DatagramFrame.Fragment(1u, 1u, 2, 2, 10, ByteArray(1)).encode()
                    assertTrue(name, DatagramFrame.decode(bytes).reasonOrNull() is DatagramReason.FragmentIndexOutOfRange)
                    checked++
                }
                "fragCount outside 2..255" -> {
                    listOf(0, 1).forEach { count ->
                        val bytes = DatagramFrame.Fragment(1u, 1u, 0, count, 10, ByteArray(1)).encode()
                        assertTrue(
                            "$name (count=$count)",
                            DatagramFrame.decode(bytes).reasonOrNull() is DatagramReason.FragmentCountOutOfRange,
                        )
                    }
                    checked++
                }
                "totalLen smaller than fragCount" -> {
                    val bytes = DatagramFrame.Fragment(1u, 1u, 0, 3, 2, ByteArray(1)).encode()
                    assertTrue(name, DatagramFrame.decode(bytes).reasonOrNull() is DatagramReason.TotalLengthTooSmall)
                    checked++
                }
                "fragment payload that leaves no byte for some other fragment" -> {
                    // Three fragments of a ten-byte packet: one fragment can
                    // carry at most eight, leaving one for each of the others.
                    val fits = DatagramFrame.Fragment(1u, 1u, 0, 3, 10, ByteArray(8)).encode()
                    assertTrue("$name (8 fits)", DatagramFrame.decode(fits) is DecodeResult.Ok)
                    val tooLong = DatagramFrame.Fragment(1u, 1u, 0, 3, 10, ByteArray(9)).encode()
                    assertEquals(name, DatagramReason.FragmentPayloadLength, DatagramFrame.decode(tooLong).reasonOrNull())
                    val empty = DatagramFrame.Fragment(1u, 1u, 0, 3, 10, ByteArray(0)).encode()
                    assertEquals("$name (empty)", DatagramReason.FragmentPayloadLength, DatagramFrame.decode(empty).reasonOrNull())
                    checked++
                }
                "conflicting fragment metadata or bytes for one packet discards it" -> {
                    val metadata = readyReassembler()
                    metadata.offer(DatagramFrame.Fragment(1u, 1u, 0, 3, 30, ByteArray(10)), 0)
                    val conflicting = metadata.offer(DatagramFrame.Fragment(1u, 1u, 1, 4, 30, ByteArray(10)), 0)
                    assertEquals(name, DatagramReason.MetadataConflict, conflicting.reasonOrNull())

                    val bytes = readyReassembler()
                    bytes.offer(DatagramFrame.Fragment(1u, 1u, 0, 2, 20, ByteArray(10) { 1 }), 0)
                    val differing = bytes.offer(DatagramFrame.Fragment(1u, 1u, 0, 2, 20, ByteArray(10) { 2 }), 0)
                    assertEquals(name, DatagramReason.DuplicateFragmentDiffers, differing.reasonOrNull())
                    assertEquals("the slot must be discarded", 0, bytes.slotCount)
                    checked++
                }
                "DATA for an id that is not READY is dropped" -> {
                    val notReady = DatagramReassembler()
                    val refused = notReady.offer(DatagramFrame.Data(1u, byteArrayOf(1)), 0)
                    assertEquals(name, DatagramReason.NotReady, refused.reasonOrNull())
                    checked++
                }
                else -> error("unhandled quicDatagram reject shape: $name")
            }
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    @Test
    fun theTypeIsTheTopTwoBitsAndTheIdTheRest() {
        // No reserved bits left to reject: every one of the 32 is either type or id.
        for (type in DatagramType.entries) {
            val word = (type.value.toUInt() shl 30) or 0x2A_0000u
            val bytes = byteArrayOf((word shr 24).toByte(), (word shr 16).toByte(), (word shr 8).toByte(), word.toByte())
            val decoded = DatagramFrame.decode(bytes).valueOrNull()
            if (type == DatagramType.Fragment) continue // needs its header; covered by the vectors
            assertEquals("type $type", 0x2A_0000u, decoded?.flowId)
        }
    }

    @Test
    fun aFlowIdBeyondThirtyBitsCannotBeEncoded() {
        // It would spill into the type and turn a DATA into a FRAGMENT.
        val refused = runCatching { DatagramFrame.Data(QuicFlowId.MAX + 1u, ByteArray(0)).encode() }
        assertTrue(refused.isFailure)
    }

    // ── Fragmentation planning ──────────────────────────────────────────────

    @Test
    fun fragmentPayloadMaxIsDatagramSizeMinusTwelve() {
        val maxDatagram = 1200
        val planned =
            (DatagramFrame.plan(1u, 1u, ByteArray(2500), maxDatagram) as DecodeResult.Ok)
                .value
                .map { it as DatagramFrame.Fragment }
        assertEquals(maxDatagram - DatagramFrame.FRAGMENT_HEADER_LENGTH, planned.first().payload.size)
        assertEquals(3, planned.size)
        assertEquals(2500, planned.sumOf { it.payload.size })
    }

    @Test
    fun aPlannedPacketReassemblesToTheOriginal() {
        val payload = ByteArray(2500) { (it % 253).toByte() }
        val frames = (DatagramFrame.plan(1u, 42u, payload, 1200) as DecodeResult.Ok).value
        val reassembler = readyReassembler()

        var assembled: ByteArray? = null
        frames.forEach { frame ->
            val encoded = frame.encode()
            val decoded = (DatagramFrame.decode(encoded) as DecodeResult.Ok).value
            val accepted = reassembler.offer(decoded, 0)
            (accepted.valueOrNull() as? DatagramReassembler.Accepted.Payload)?.let { assembled = it.bytes }
        }
        assertTrue("payload should reassemble", assembled != null)
        assertTrue("bytes must be identical", payload.contentEquals(assembled!!))
    }

    @Test
    fun fragmentsMayArriveOutOfOrder() {
        val payload = ByteArray(2500) { (it % 251).toByte() }
        val frames = (DatagramFrame.plan(1u, 42u, payload, 1200) as DecodeResult.Ok).value.reversed()
        val reassembler = readyReassembler()
        var assembled: ByteArray? = null
        frames.forEach { frame ->
            (reassembler.offer(frame, 0).valueOrNull() as? DatagramReassembler.Accepted.Payload)
                ?.let { assembled = it.bytes }
        }
        assertTrue(payload.contentEquals(assembled!!))
    }

    @Test
    fun anIdenticalDuplicateIsTolerated() {
        // Ordinary network behaviour, unlike a duplicate that differs.
        val reassembler = readyReassembler()
        val first = DatagramFrame.Fragment(1u, 1u, 0, 2, 20, ByteArray(10) { 7 })
        reassembler.offer(first, 0)
        val repeated = reassembler.offer(first, 0)
        assertTrue("a byte-identical duplicate must not fail", repeated is DecodeResult.Ok)
    }

    @Test
    fun closingAFlowDropsItsPendingSlots() {
        val reassembler = readyReassembler()
        reassembler.offer(DatagramFrame.Fragment(1u, 1u, 0, 2, 20, ByteArray(10)), 0)
        assertEquals(1, reassembler.slotCount)
        reassembler.offer(DatagramFrame.Close(1u), 0)
        assertEquals(0, reassembler.slotCount)
    }

    @Test
    fun slotsExpireSoAnAbandonedPacketCannotHoldMemory() {
        val reassembler = readyReassembler()
        reassembler.offer(DatagramFrame.Fragment(1u, 1u, 0, 2, 20, ByteArray(10)), nowMillis = 0)
        assertEquals(1, reassembler.slotCount)
        reassembler.offer(DatagramFrame.Fragment(2u, 2u, 0, 2, 20, ByteArray(10)), nowMillis = 10_000)
        assertEquals("the expired slot must be gone", 1, reassembler.slotCount)
    }

    @Test
    fun slotCountIsBounded() {
        // A peer that opens slots and never completes them must not grow this
        // without limit.
        val reassembler = DatagramReassembler(maxSlots = 4).also { it.markReady() }
        for (packet in 1..20) {
            reassembler.offer(DatagramFrame.Fragment(1u, packet.toUInt(), 0, 2, 20, ByteArray(10)), 0)
        }
        assertTrue("slot count ${reassembler.slotCount} must stay within the bound", reassembler.slotCount <= 4)
    }

    @Test
    fun aDatagramTooSmallForAFragmentHeaderIsRejected() {
        val reason = DatagramFrame.plan(1u, 1u, ByteArray(1000), maxDatagramSize = 10).reasonOrNull()
        assertTrue(reason is DatagramReason.DatagramTooSmall)
    }

    @Test
    fun aPayloadNeedingMoreThan255FragmentsIsRejected() {
        val reason = DatagramFrame.plan(1u, 1u, ByteArray(65535), maxDatagramSize = 20).reasonOrNull()
        assertTrue(reason is DatagramReason.FragmentCountOutOfRange)
    }

    @Test
    fun everyTruncationPointIsRejectedRatherThanCrashing() {
        val full = DatagramFrame.Fragment(1u, 1u, 0, 2, 20, ByteArray(4)).encode()
        for (length in 0 until DatagramFrame.FRAGMENT_HEADER_LENGTH) {
            assertTrue(
                "prefix of $length must not decode",
                DatagramFrame.decode(full.copyOf(length)) is DecodeResult.Invalid,
            )
        }
    }

    @Test
    fun closeIsAcceptedBeforeReady() {
        // Only payload is gated on READY; a close must still be actionable.
        val reassembler = DatagramReassembler()
        assertTrue(reassembler.offer(DatagramFrame.Close(1u), 0) is DecodeResult.Ok)
    }
}
