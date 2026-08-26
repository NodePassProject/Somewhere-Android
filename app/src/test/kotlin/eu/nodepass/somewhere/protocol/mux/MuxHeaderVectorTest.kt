// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowHeaderReason
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every vector in the `tlsMux` family. NW-P-12 through NW-P-16. */
class MuxHeaderVectorTest {
    private val family = VectorFixture.family("tlsMux")
    private val rejects = VectorFixture.rejects("tlsMux")

    private fun header(
        kind: MuxKind,
        flags: Int = 0,
        value: Int = 0,
        flowId: UInt = 1u,
    ) = MuxHeader(kind, flags, value, flowId)

    // ── The mode marker, NW-P-12 ────────────────────────────────────────────

    @Test
    fun theFixtureMarkerMatchesTheImplementation() {
        assertEquals(family["marker"]!!.jsonPrimitive.content, byteArrayOf(MuxHeader.MODE_MARKER).toHex())
    }

    @Test
    fun theMarkerCanNeverBeMistakenForAFlowHeader() {
        // The reason 0xff is usable as the marker at all: its role bits are the
        // reserved 0b11, so no valid FlowHeader can begin with it. T2 asserts
        // the same thing from the other side; both must hold for the carrier to
        // be able to tell mode from data.
        val asFlowHeader = FlowHeader.decode(byteArrayOf(MuxHeader.MODE_MARKER, 0, 0, 0, 1))
        assertEquals(FlowHeaderReason.ReservedRole, asFlowHeader.reasonOrNull())
    }

    // ── Header layout ───────────────────────────────────────────────────────

    @Test
    fun theHeaderIsEightBytesInTheDocumentedOrder() {
        assertEquals(
            8,
            family["muxHeader"]!!
                .jsonObject["headerLen"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(MuxHeader.LENGTH, 8)
        val encoded = header(MuxKind.Stream, flags = 0x01, value = 0x1234, flowId = 0x05060708u).encode()
        assertEquals("01011234" + "05060708", encoded.toHex())
    }

    @Test
    fun theFixtureKindBytesMatchTheEnum() {
        val kinds = family["muxHeader"]!!.jsonObject["kinds"]!!.jsonObject
        assertEquals(MuxKind.Stream.byte, kinds.keys.first { kinds[it]!!.jsonPrimitive.content.startsWith("STREAM") }.toInt(16))
        assertEquals(MuxKind.Window.byte, kinds.keys.first { kinds[it]!!.jsonPrimitive.content.startsWith("WINDOW") }.toInt(16))
        assertEquals(MuxKind.Datagram.byte, kinds.keys.first { kinds[it]!!.jsonPrimitive.content.startsWith("DATAGRAM") }.toInt(16))
    }

    @Test
    fun theFixtureFlagBytesMatchTheConstants() {
        val flags = family["muxHeader"]!!.jsonObject["streamFlags"]!!.jsonObject
        assertEquals(MuxHeader.FLAG_SYN, flags.keys.first { flags[it]!!.jsonPrimitive.content == "SYN" }.toInt(16))
        assertEquals(MuxHeader.FLAG_FIN, flags.keys.first { flags[it]!!.jsonPrimitive.content == "FIN" }.toInt(16))
        assertEquals(MuxHeader.FLAG_RST, flags.keys.first { flags[it]!!.jsonPrimitive.content == "RST" }.toInt(16))
    }

    @Test
    fun theFixtureBoundsMatchTheConstants() {
        val bounds = family["bounds"]!!.jsonObject

        fun bound(key: String) = bounds[key]!!.jsonPrimitive.content.toInt()
        assertEquals(bound("maxPayloadPerStreamFrame"), MuxHeader.MAX_STREAM_PAYLOAD)
        assertEquals(bound("perStreamReceiveCredit"), MuxHeader.DEFAULT_STREAM_CREDIT)
        assertEquals(bound("connectionReceiveCredit"), MuxHeader.DEFAULT_CONNECTION_CREDIT)
        assertEquals(bound("maxActiveStreams"), MuxHeader.MAX_ACTIVE_STREAMS)
        assertEquals(bound("outboundQueueSlots"), MuxHeader.OUTBOUND_QUEUE_SLOTS)
        // The two the shard layer reads. They are pinned here rather than
        // retyped in the placement code, because the density moved once
        // already — v1.8.1 changed it while nothing was reading it, and it sat
        // wrong in the fixture for a day without anything noticing.
        assertEquals(bound("shardFlowThreshold"), MuxHeader.SHARD_FLOW_THRESHOLD)
        assertEquals(bound("shardIdleCloseSeconds"), MuxHeader.SHARD_IDLE_CLOSE_SECONDS)
    }

    // ── Rejection vectors ───────────────────────────────────────────────────

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 6 tlsMux rejects", 6, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            when {
                name.contains("DATAGRAM kind closes") -> {
                    val reason = MuxHeader.decode(header(MuxKind.Datagram).encode()).reasonOrNull()
                    assertEquals(name, MuxReason.DatagramUnsupported, reason)
                    checked++
                }
                name.contains("RST must be the only flag") -> {
                    val notAlone =
                        MuxHeader.decode(header(MuxKind.Stream, MuxHeader.FLAG_RST or MuxHeader.FLAG_FIN).encode())
                    assertEquals(name, MuxReason.ResetNotAlone, notAlone.reasonOrNull())
                    val withValue =
                        MuxHeader.decode(header(MuxKind.Stream, MuxHeader.FLAG_RST, value = 5).encode())
                    assertTrue(name, withValue.reasonOrNull() is MuxReason.ResetWithValue)
                    checked++
                }
                name.contains("other STREAM flag bits must be zero") -> {
                    val reason = MuxHeader.decode(header(MuxKind.Stream, flags = 0x08).encode()).reasonOrNull()
                    assertTrue(name, reason is MuxReason.ReservedFlagBits)
                    checked++
                }
                name.contains("WINDOW must have flags=0") -> {
                    assertTrue(
                        name,
                        MuxHeader
                            .decode(header(MuxKind.Window, flags = MuxHeader.FLAG_SYN, value = 1).encode())
                            .reasonOrNull() is MuxReason.WindowWithFlags,
                    )
                    assertEquals(
                        name,
                        MuxReason.WindowZeroCredit,
                        MuxHeader.decode(header(MuxKind.Window, value = 0).encode()).reasonOrNull(),
                    )
                    checked++
                }
                name.contains("credit exceeding the configured window") -> {
                    val result =
                        MuxCarrierRules.checkCredit(
                            outstanding = MuxHeader.DEFAULT_STREAM_CREDIT.toLong(),
                            returned = 1,
                            window = MuxHeader.DEFAULT_STREAM_CREDIT,
                        )
                    assertTrue(name, result.reasonOrNull() is MuxReason.CreditExceedsWindow)
                    checked++
                }
                name.contains("unknown flowId") -> {
                    val data = header(MuxKind.Stream, flags = 0, value = 10, flowId = 99u)
                    val result = MuxCarrierRules.checkFlowKnown(data, openFlows = setOf(1u, 2u))
                    assertTrue(name, result.reasonOrNull() is MuxReason.UnknownFlow)
                    checked++
                }
                else -> error("unhandled tlsMux reject shape: $name")
            }
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    // ── Frame rules in detail ───────────────────────────────────────────────

    @Test
    fun everyReservedFlagBitIsRejected() {
        for (bit in listOf(0x08, 0x10, 0x20, 0x40, 0x80)) {
            val reason = MuxHeader.decode(header(MuxKind.Stream, flags = bit).encode()).reasonOrNull()
            assertTrue("bit 0x%02x must be rejected".format(bit), reason is MuxReason.ReservedFlagBits)
        }
    }

    @Test
    fun synAndFinMayCombine() {
        // A stream can open and half-close in one frame; only RST is exclusive.
        val combined = MuxHeader.decode(header(MuxKind.Stream, MuxHeader.FLAG_SYN or MuxHeader.FLAG_FIN).encode())
        val decoded = (combined as DecodeResult.Ok).value
        assertTrue(decoded.isSyn && decoded.isFin && !decoded.isReset)
    }

    @Test
    fun streamRequiresANonZeroFlow() {
        assertEquals(
            MuxReason.StreamFlowIdZero,
            MuxHeader.decode(header(MuxKind.Stream, flowId = 0u).encode()).reasonOrNull(),
        )
    }

    @Test
    fun connectionLevelWindowUsesFlowZero() {
        val connection = (MuxHeader.decode(header(MuxKind.Window, value = 100, flowId = 0u).encode()) as DecodeResult.Ok).value
        assertTrue(connection.isConnectionLevel)
        val perStream = (MuxHeader.decode(header(MuxKind.Window, value = 100, flowId = 7u).encode()) as DecodeResult.Ok).value
        assertTrue(!perStream.isConnectionLevel)
    }

    @Test
    fun streamPayloadIsBoundedAtThirtyTwoKilobytes() {
        assertTrue(MuxHeader.decode(header(MuxKind.Stream, value = MuxHeader.MAX_STREAM_PAYLOAD).encode()) is DecodeResult.Ok)
        assertTrue(
            MuxHeader
                .decode(header(MuxKind.Stream, value = MuxHeader.MAX_STREAM_PAYLOAD + 1).encode())
                .reasonOrNull() is MuxReason.StreamPayloadTooLarge,
        )
    }

    @Test
    fun unknownKindsAreRejected() {
        listOf(0x00, 0x04, 0xff).forEach { kind ->
            val bytes = byteArrayOf(kind.toByte(), 0, 0, 1, 0, 0, 0, 1)
            assertTrue("kind 0x%02x".format(kind), MuxHeader.decode(bytes).reasonOrNull() is MuxReason.UnknownKind)
        }
    }

    @Test
    fun aSynMayNameAFlowThatIsNotYetOpen() {
        // Opening is exactly the case where an unknown id is expected.
        val syn = header(MuxKind.Stream, flags = MuxHeader.FLAG_SYN, flowId = 42u)
        assertTrue(MuxCarrierRules.checkFlowKnown(syn, openFlows = emptySet()) is DecodeResult.Ok)
    }

    @Test
    fun creditWithinTheWindowIsAccepted() {
        val result = MuxCarrierRules.checkCredit(outstanding = 0, returned = 1024, window = 524288)
        assertEquals(1024L, (result as DecodeResult.Ok).value)
    }

    @Test
    fun everyTruncationPointIsRejectedRatherThanCrashing() {
        val full = header(MuxKind.Stream, flags = MuxHeader.FLAG_SYN, value = 16, flowId = 3u).encode()
        for (length in 0 until full.size) {
            assertTrue(
                "prefix of $length must not decode",
                MuxHeader.decode(full.copyOf(length)).reasonOrNull() is MuxReason.Truncated,
            )
        }
        assertTrue(MuxHeader.decode(full) is DecodeResult.Ok)
    }

    @Test
    fun validHeadersRoundTrip() {
        listOf(
            header(MuxKind.Stream, MuxHeader.FLAG_SYN, 100, 1u),
            header(MuxKind.Stream, MuxHeader.FLAG_RST, 0, 0xFFFFFFFFu),
            header(MuxKind.Window, 0, 65535, 0u),
        ).forEach { original ->
            val decoded = (MuxHeader.decode(original.encode()) as DecodeResult.Ok).value
            assertEquals(original, decoded)
        }
    }
}
