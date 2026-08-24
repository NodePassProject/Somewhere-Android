// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.target

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.int
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every vector in the `target` family. NW-P-05. */
class TargetVectorTest {
    private val cases = VectorFixture.cases("target")
    private val rejects = VectorFixture.rejects("target")

    private fun decoded(hex: String): Target.Decoded = (Target.decode(hex.hexToByteArrayCompat()) as DecodeResult.Ok).value

    // ── Positive vectors ────────────────────────────────────────────────────

    @Test
    fun everyPositiveVectorDecodesAndReEncodes() {
        assertEquals("fixture should carry 3 target cases", 3, cases.size)
        cases.forEach { case ->
            val hex = case.str("expectedHex")
            val result = decoded(hex)
            assertEquals(case.str("name"), case.int("totalLen"), result.consumed)
            assertEquals(case.str("name"), hex, result.target.encode().toHex())
        }
    }

    @Test
    fun theIpv4VectorCarriesTheExpectedAddressAndPort() {
        val target = decoded("01c000020101bb").target as Target.Ip
        assertEquals(listOf(192, 0, 2, 1), target.octets.map { it.toInt() and 0xFF })
        assertEquals(443, target.port)
        assertTrue(!target.isIpv6)
    }

    @Test
    fun theIpv6VectorCarriesTheExpectedAddressAndPort() {
        val target = decoded("0420010db80000000000000000000000010035").target as Target.Ip
        assertTrue(target.isIpv6)
        assertEquals(16, target.octets.size)
        assertEquals(53, target.port)
    }

    @Test
    fun theIdnaDomainVectorSurvivesUnchanged() {
        // Already in punycode on the wire: the client must not re-encode or
        // normalise it, only carry it.
        val target = decoded("0315786e2d2d62636865722d6b76612e6578616d706c651f90").target as Target.Domain
        assertEquals("xn--bcher-kva.example", target.host)
        assertEquals(8080, target.port)
    }

    // ── Rejection vectors ───────────────────────────────────────────────────

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 6 target rejects", 6, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            val reason: Any? =
                when {
                    name.contains("port 0") -> {
                        // Every form must reject it, not just one.
                        assertEquals(TargetReason.PortZero, Target.decode("01c00002010000".hexToByteArrayCompat()).reasonOrNull())
                        assertEquals(
                            TargetReason.PortZero,
                            Target.decode("04200000000000000000000000000000010000".hexToByteArrayCompat()).reasonOrNull(),
                        )
                        Target.decode("03016100 00".replace(" ", "").hexToByteArrayCompat()).reasonOrNull()
                    }
                    name.contains("empty domain") ->
                        Target.decode(byteArrayOf(0x03, 0x00, 0x01, 0xbb.toByte())).reasonOrNull()
                    name.contains("longer than 253") -> {
                        val long = "a".repeat(254)
                        Target.ofDomain(long, 443).reasonOrNull()
                    }
                    name.contains("non-ASCII") ->
                        Target
                            .decode(byteArrayOf(0x03, 0x02, 0xC3.toByte(), 0xA9.toByte(), 0x01, 0xbb.toByte()))
                            .reasonOrNull()
                    name.contains("unknown ATYP") ->
                        Target.decode(byteArrayOf(0x02, 1, 2, 3, 4, 0x01, 0xbb.toByte())).reasonOrNull()
                    name.contains("truncated") ->
                        Target.decode(byteArrayOf(0x01, 192.toByte(), 0, 2)).reasonOrNull()
                    else -> error("unhandled target reject shape: $name")
                }
            assertTrue("$name should be rejected, got $reason", reason != null)
            checked++
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    @Test
    fun rejectionsAreDistinguishable() {
        assertEquals(TargetReason.PortZero, Target.ofDomain("example.com", 0).reasonOrNull())
        assertTrue(Target.ofDomain("", 443).reasonOrNull() is TargetReason.DomainLength)
        assertTrue(Target.ofDomain("a".repeat(254), 443).reasonOrNull() is TargetReason.DomainLength)
        assertEquals(TargetReason.DomainNotAscii, Target.ofDomain("héllo.example", 443).reasonOrNull())
        assertTrue(
            Target.decode(byteArrayOf(0x09, 1, 2, 3, 4, 0x01, 0xbb.toByte())).reasonOrNull()
                is TargetReason.UnknownAddressType,
        )
    }

    @Test
    fun atypTwoDoesNotExist() {
        // ATYP matches SOCKS5, where 0x02 is unassigned. It has to be an unknown
        // type rather than a gap that decodes as something.
        val reason = Target.decode(byteArrayOf(0x02, 1, 2, 3, 4, 0x01, 0xbb.toByte())).reasonOrNull()
        assertEquals(TargetReason.UnknownAddressType(2), reason)
    }

    // ── DNS label rules, docs/protocol.md section 5 ─────────────────────────

    @Test
    fun labelRulesAreEnforced() {
        assertTrue(Target.ofDomain("a".repeat(64) + ".example", 443).reasonOrNull() is TargetReason.DomainLabelLength)
        assertEquals(TargetReason.DomainLabelHyphen, Target.ofDomain("-bad.example", 443).reasonOrNull())
        assertEquals(TargetReason.DomainLabelHyphen, Target.ofDomain("bad-.example", 443).reasonOrNull())
        assertEquals(TargetReason.DomainLabelCharacter, Target.ofDomain("bad_label.example", 443).reasonOrNull())
        assertTrue(Target.ofDomain("a..example", 443).reasonOrNull() is TargetReason.DomainLabelLength)
    }

    @Test
    fun legitimateDomainsAreAccepted() {
        listOf(
            "example.com",
            "a.b.c.d.example",
            "xn--bcher-kva.example",
            "has-a-hyphen.example",
            "digits123.example",
            "a".repeat(63) + ".example",
        ).forEach { host ->
            assertTrue("$host should be accepted", Target.ofDomain(host, 443) is DecodeResult.Ok)
        }
    }

    // ── Prefix decoding, NW-P-10 ────────────────────────────────────────────

    @Test
    fun decodingStopsAtTheEndOfTheTargetAndReportsWhatItUsed() {
        // A cold connection may write AuthFrame || FlowHeader || Target ||
        // payload in one write, so trailing bytes belong to the caller.
        val withPayload = "01c000020101bb".hexToByteArrayCompat() + "deadbeef".hexToByteArrayCompat()
        val result = (Target.decode(withPayload) as DecodeResult.Ok).value
        assertEquals(7, result.consumed)
        assertEquals("deadbeef", withPayload.copyOfRange(result.consumed, withPayload.size).toHex())
    }

    @Test
    fun decodingHonoursAnOffset() {
        val prefixed = "ffff".hexToByteArrayCompat() + "01c000020101bb".hexToByteArrayCompat()
        val result = (Target.decode(prefixed, offset = 2) as DecodeResult.Ok).value
        assertEquals(443, result.target.port)
        assertEquals(7, result.consumed)
    }

    @Test
    fun aHostileLengthByteCannotDriveAnUnboundedRead() {
        // Declared length 255 with two bytes present. The outer header must be
        // validated before the variable-length read.
        val hostile = byteArrayOf(0x03, 0xff.toByte(), 0x61, 0x62)
        assertEquals(TargetReason.Truncated, Target.decode(hostile).reasonOrNull())
    }

    @Test
    fun everyTruncationPointIsRejectedRatherThanCrashing() {
        val full = "0315786e2d2d62636865722d6b76612e6578616d706c651f90".hexToByteArrayCompat()
        for (length in 0 until full.size) {
            val result = Target.decode(full.copyOf(length))
            assertTrue("prefix of $length bytes should be rejected", result is DecodeResult.Invalid)
        }
        assertTrue(Target.decode(full) is DecodeResult.Ok)
    }

    @Test
    fun encodedTargetsRoundTrip() {
        listOf(
            Target.ofIpv4(byteArrayOf(10, 0, 0, 1), 80),
            Target.ofIpv6(ByteArray(16) { it.toByte() }, 65535),
            Target.ofDomain("example.com", 1),
        ).forEach { built ->
            val target = (built as DecodeResult.Ok).value
            val again = (Target.decode(target.encode()) as DecodeResult.Ok).value
            assertEquals(target, again.target)
            assertEquals(target.encode().size, again.consumed)
        }
    }
}
