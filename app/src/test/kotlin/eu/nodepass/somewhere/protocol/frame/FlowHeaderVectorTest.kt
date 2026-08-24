// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.int
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every vector in the `flowHeader` family. NW-P-03. */
class FlowHeaderVectorTest {
    private val cases = VectorFixture.cases("flowHeader")
    private val rejects = VectorFixture.rejects("flowHeader")

    private fun roleOf(name: String) =
        when (name) {
            "DUPLEX" -> FlowRole.Duplex
            "OPEN" -> FlowRole.Open
            "ATTACH" -> FlowRole.Attach
            else -> error("unknown role in fixture: $name")
        }

    private fun carrierOf(name: String) =
        when (name) {
            "tls" -> FlowCarrier.TlsTcp
            "quic" -> FlowCarrier.Quic
            else -> error("unknown carrier in fixture: $name")
        }

    // ── Positive vectors ────────────────────────────────────────────────────

    @Test
    fun everyPositiveVectorEncodesToItsExpectedBytes() {
        assertEquals("fixture should carry 3 flowHeader cases", 3, cases.size)
        cases.forEach { case ->
            val header =
                FlowHeader(
                    role = roleOf(case.str("role")),
                    kind = if (case.str("kind") == "UDP") FlowKind.Udp else FlowKind.Tcp,
                    up = carrierOf(case.str("up")),
                    down = carrierOf(case.str("down")),
                    hops = case.int("hops"),
                    flowId = case.str("flowId").toUInt(),
                )
            assertEquals(case.str("name"), case.str("expectedHex"), header.encode().toHex())
        }
    }

    @Test
    fun everyPositiveVectorDecodesBackToItsFields() {
        cases.forEach { case ->
            val decoded = FlowHeader.decode(case.str("expectedHex").hexToByteArrayCompat())
            val header = (decoded as DecodeResult.Ok).value
            assertEquals(case.str("name"), roleOf(case.str("role")), header.role)
            assertEquals(case.str("name"), carrierOf(case.str("up")), header.up)
            assertEquals(case.str("name"), carrierOf(case.str("down")), header.down)
            assertEquals(case.str("name"), case.str("flowId").toUInt(), header.flowId)
            assertEquals(case.str("name"), case.int("hops"), header.hops)
        }
    }

    // ── Rejection vectors ───────────────────────────────────────────────────

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 3 flowHeader rejects", 3, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            when {
                "flagsHex" in reject -> {
                    // Reserved role bits, supplied as a raw flags byte.
                    val bytes = (reject.str("flagsHex") + "00000001").hexToByteArrayCompat()
                    assertEquals(name, FlowHeaderReason.ReservedRole, FlowHeader.decode(bytes).reasonOrNull())
                    checked++
                }
                "hops" in reject && reject["origin"] != null -> {
                    val result =
                        FlowHeader.decode(
                            FlowHeader(
                                FlowRole.Duplex,
                                FlowKind.Tcp,
                                FlowCarrier.TlsTcp,
                                FlowCarrier.TlsTcp,
                                hops = reject.int("hops"),
                                flowId = 1u,
                            ).encode(),
                            FlowOrigin.Client,
                        )
                    assertTrue(name, result.reasonOrNull() is FlowHeaderReason.ClientFlowWithHops)
                    checked++
                }
                "flowId" in reject -> {
                    val bytes = byteArrayOf(0, 0, 0, 0, 0)
                    assertEquals(name, FlowHeaderReason.ZeroFlowId, FlowHeader.decode(bytes).reasonOrNull())
                    checked++
                }
                else -> error("unhandled flowHeader reject shape: $name")
            }
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    // ── Properties the fixture notes state ──────────────────────────────────

    @Test
    fun theMuxMarkerCanNeverBeAValidFirstByte() {
        // This is why 0xff works as the TLS Mux mode marker: its role bits are
        // the reserved 0b11. If this ever stopped holding, the marker would
        // become ambiguous with a real FlowHeader.
        val result = FlowHeader.decode(byteArrayOf(0xff.toByte(), 0, 0, 0, 1))
        assertEquals(FlowHeaderReason.ReservedRole, result.reasonOrNull())
    }

    @Test
    fun duplexRequiresMatchingCarriers() {
        val mismatched =
            FlowHeader(FlowRole.Duplex, FlowKind.Tcp, FlowCarrier.TlsTcp, FlowCarrier.Quic, 0, 1u)
        assertEquals(
            FlowHeaderReason.DuplexCarriersDiffer,
            FlowHeader.decode(mismatched.encode()).reasonOrNull(),
        )
    }

    @Test
    fun splitFlowsRequireDifferingCarriers() {
        listOf(FlowRole.Open, FlowRole.Attach).forEach { role ->
            val matched = FlowHeader(role, FlowKind.Tcp, FlowCarrier.Quic, FlowCarrier.Quic, 0, 1u)
            assertEquals(
                "$role with identical carriers",
                FlowHeaderReason.SplitCarriersMatch,
                FlowHeader.decode(matched.encode()).reasonOrNull(),
            )
        }
    }

    @Test
    fun clientOriginatedFlowsCannotCarryHops() {
        val built = FlowHeader.forClient(FlowRole.Duplex, FlowKind.Tcp, FlowCarrier.TlsTcp, FlowCarrier.TlsTcp, 1u)
        assertEquals(0, (built as DecodeResult.Ok).value.hops)
    }

    @Test
    fun aPeerMayLegitimatelyCarryHops() {
        // hops is only constrained for client-originated flows; a relayed flow
        // arriving from a Portal may carry them.
        val relayed = FlowHeader(FlowRole.Duplex, FlowKind.Tcp, FlowCarrier.TlsTcp, FlowCarrier.TlsTcp, 3, 1u)
        val decoded = FlowHeader.decode(relayed.encode(), FlowOrigin.Peer)
        assertEquals(3, (decoded as DecodeResult.Ok).value.hops)
    }

    // ── Exhaustive round trip ───────────────────────────────────────────────

    @Test
    fun everyValidFlagCombinationRoundTrips() {
        // 256 flag bytes is small enough to enumerate, so enumerate rather than
        // sample: this is the cheapest possible proof that packing and unpacking
        // are actually inverses.
        var valid = 0
        for (flags in 0..0xFF) {
            val bytes = byteArrayOf(flags.toByte(), 0, 0, 0, 1)
            val header = (FlowHeader.decode(bytes) as? DecodeResult.Ok)?.value ?: continue
            valid++
            assertEquals("flags 0x%02x must round trip".format(flags), bytes.toHex(), header.encode().toHex())
        }
        assertTrue("some flag combinations should be valid, got $valid", valid > 0)
    }

    @Test
    fun wrongLengthIsRejected() {
        listOf(0, 1, 4, 6, 32).forEach { size ->
            val result = FlowHeader.decode(ByteArray(size))
            assertTrue("$size bytes should be rejected", result.reasonOrNull() is FlowHeaderReason.WrongLength)
        }
    }

    @Test
    fun flowIdSpansTheFullUnsignedRange() {
        // A signed int would silently mangle anything above 2^31.
        val high = FlowHeader(FlowRole.Duplex, FlowKind.Tcp, FlowCarrier.Quic, FlowCarrier.Quic, 0, 0xFFFFFFFFu)
        val decoded = FlowHeader.decode(high.encode())
        assertNotNull(decoded.valueOrNull())
        assertEquals(0xFFFFFFFFu, (decoded as DecodeResult.Ok).value.flowId)
    }
}
