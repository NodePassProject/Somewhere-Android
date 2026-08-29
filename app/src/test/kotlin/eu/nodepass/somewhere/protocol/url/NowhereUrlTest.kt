// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NW-P-23 and NW-P-24. No fixed vectors exist for this one, so the cases come
 * from `docs/configuration.md` and from what NowhereDash actually emits.
 */
class NowhereUrlTest {
    private fun parsed(url: String): NowhereUrl = (NowhereUrl.parse(url) as DecodeResult.Ok).value

    private val minimal = "nowhere://secret@example.com:443"

    // ── Scheme ──────────────────────────────────────────────────────────────

    @Test
    fun theClientSchemeIsNowhere() {
        assertEquals("example.com", parsed(minimal).host)
        assertEquals(443, parsed(minimal).port)
    }

    @Test
    fun vectorIsNotTheClientScheme() {
        // The single most common confusion in this ecosystem: vector:// starts
        // the upstream Rust process and is not an import URL. NowhereDash has a
        // test asserting its own output never contains it.
        val reason = NowhereUrl.parse("vector://secret@example.com:443").reasonOrNull()
        assertEquals(UrlReason.WrongScheme("vector"), reason)
    }

    @Test
    fun otherSchemesAreRejected() {
        listOf("portal", "https", "anywhere", "somewhere").forEach { scheme ->
            val reason = NowhereUrl.parse("$scheme://secret@example.com:443").reasonOrNull()
            assertTrue("$scheme should be rejected", reason is UrlReason.WrongScheme)
        }
    }

    @Test
    fun schemeMatchingIsCaseInsensitive() {
        assertEquals("example.com", parsed("NOWHERE://secret@example.com:443").host)
    }

    // ── Shared key, reusing the rules from NW-P-01 ──────────────────────────

    @Test
    fun theSharedKeyIsPercentDecoded() {
        assertEquals("a+b", String(parsed("nowhere://a%2Bb@example.com:443").sharedKey.toByteArray()))
    }

    @Test
    fun aLiteralPlusStaysAPlus() {
        assertEquals("a+b", String(parsed("nowhere://a+b@example.com:443").sharedKey.toByteArray()))
    }

    @Test
    fun aPasswordComponentMakesTheUrlInvalid() {
        assertEquals(
            AuthReason.PasswordComponentPresent,
            NowhereUrl.parse("nowhere://key:pass@example.com:443").reasonOrNull(),
        )
    }

    @Test
    fun aUrlWithoutASharedKeyIsRejected() {
        assertEquals(AuthReason.MissingUserInfo, NowhereUrl.parse("nowhere://example.com:443").reasonOrNull())
    }

    // ── Defaults, docs/configuration.md ─────────────────────────────────────

    @Test
    fun defaultsMatchTheSpecification() {
        val url = parsed(minimal)
        assertEquals(NextHopCarrier.Udp, url.up)
        assertEquals(NextHopCarrier.Udp, url.down)
        assertEquals(false, url.mux)
        assertEquals("now/1", url.alpn)
        assertEquals(CertificateVerification.Skipped, url.certificateVerification)
        assertEquals(0, url.rateMbps)
        assertEquals(0, url.etarMbps)
        assertNull(url.displayName)
    }

    @Test
    fun aDefaultConfigurationNeedsQuicAndSaysSo() {
        // Upstream defaults both directions to udp, so importing a default
        // configuration before QUIC ships has to be surfaced (NW-P-25), never
        // silently rewritten.
        assertTrue(parsed(minimal).requiresQuic)
        assertTrue(!parsed("$minimal?up=tcp&down=tcp").requiresQuic)
        assertTrue(parsed("$minimal?up=tcp&down=udp").requiresQuic)
    }

    // ── Parameters ──────────────────────────────────────────────────────────

    @Test
    fun carriersAreRead() {
        val url = parsed("$minimal?up=tcp&down=udp")
        assertEquals(NextHopCarrier.Tcp, url.up)
        assertEquals(NextHopCarrier.Udp, url.down)
    }

    @Test
    fun anInvalidCarrierIsRejected() {
        val reason = NowhereUrl.parse("$minimal?up=carrier-pigeon").reasonOrNull()
        assertTrue(reason is UrlReason.InvalidCarrier)
    }

    @Test
    fun muxIsOnlyEnabledByExactlyOne() {
        assertTrue(parsed("$minimal?mux=1").mux)
        // Upstream treats mux as a switch, so anything else is the default
        // rather than an error — being strict here would reject configurations
        // upstream accepts.
        listOf("0", "true", "yes", "").forEach { value ->
            assertTrue("mux=$value should stay off", !parsed("$minimal?mux=$value").mux)
        }
    }

    @Test
    fun theDeprecatedPoolParameterIsIgnored() {
        // NowhereDash still emits 1.7-era URLs with pool=5 for tcp/tcp, and 1.8
        // removed the parameter. Rejecting it would reject a live dashboard's
        // entire output.
        val url = parsed("$minimal?up=tcp&down=tcp&pool=5")
        assertEquals(NextHopCarrier.Tcp, url.up)
    }

    @Test
    fun unknownParametersAreIgnoredRatherThanRejected() {
        val url = parsed("$minimal?up=tcp&future=whatever&another=1&x")
        assertEquals(NextHopCarrier.Tcp, url.up)
    }

    @Test
    fun theFragmentIsTheDisplayName() {
        assertEquals("Tokyo Node", parsed("$minimal#Tokyo%20Node").displayName)
    }

    @Test
    fun rateLimitsAreRead() {
        val url = parsed("$minimal?rate=100&etar=50")
        assertEquals(100, url.rateMbps)
        assertEquals(50, url.etarMbps)
    }

    // ── Certificate verification, D-11 ──────────────────────────────────────

    @Test
    fun neitherSniNorPinMeansVerificationIsSkipped() {
        // Every URL NowhereDash currently generates lands here. It parses, and
        // the condition is stated rather than hidden.
        val url = parsed("$minimal?up=tcp&down=tcp")
        assertEquals(CertificateVerification.Skipped, url.certificateVerification)
        assertTrue(!url.certificateVerification.isVerified)
    }

    @Test
    fun anExplicitNoneIsTheSameAsAbsent() {
        val url = parsed("$minimal?sni=none&pin=none")
        assertEquals(CertificateVerification.Skipped, url.certificateVerification)
    }

    @Test
    fun sniGivesChainVerification() {
        val url = parsed("$minimal?sni=real.example.com")
        assertEquals(CertificateVerification.Sni("real.example.com"), url.certificateVerification)
        assertTrue(url.certificateVerification.isVerified)
    }

    @Test
    fun pinTakesPriorityOverSni() {
        // Matches upstream precedence. A URL carrying both is pinned, not
        // chain-verified.
        val pin = "a".repeat(64)
        val url = parsed("$minimal?sni=real.example.com&pin=$pin")
        assertEquals(CertificateVerification.Pin(pin), url.certificateVerification)
    }

    @Test
    fun aPinConvertsToTheSameBytesTheCarriersCompare() {
        // Two carriers compare a pin in two places: the TLS path hashes a
        // certificate object and compares hex, the QUIC path hands 32 bytes
        // across a JNI boundary. One conversion serves both, so a mistake here
        // would make a node verify against an arbitrary digest on one carrier
        // and the right one on the other.
        val pin = CertificateVerification.Pin("00112233445566778899aabbccddeeff" + "ffeeddccbbaa99887766554433221100")
        val bytes = pin.bytes
        assertEquals("a pin is a SHA-256, so 32 bytes", 32, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x11.toByte(), bytes[1])
        assertEquals(0xff.toByte(), bytes[15])
        assertEquals(0xff.toByte(), bytes[16])
        assertEquals(0x00.toByte(), bytes[31])
        assertEquals(
            "the bytes must render back to the pin they came from",
            pin.sha256,
            bytes.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun aPinParsedFromAUrlSurvivesTheRoundTripToBytes() {
        // Through the parser rather than constructed, because the parser is
        // what normalises case and it is the normalised form the carriers see.
        val url = parsed("$minimal?pin=${"AB".repeat(32)}")
        val pin = url.certificateVerification as CertificateVerification.Pin
        assertEquals(32, pin.bytes.size)
        assertTrue("every byte should be 0xab", pin.bytes.all { it == 0xab.toByte() })
    }

    @Test
    fun aPinBuiltByHandFromSomethingThatIsNotHexFailsLoudly() {
        // The parser accepts 64 lower-case hex characters and nothing else, so
        // this is unreachable through it. It is reachable by constructing the
        // value directly, which is what a later caller will eventually do, and
        // the alternative to throwing is silently translating a non-hex
        // character into a digit — a pin that then verifies against a digest
        // nobody chose.
        val bogus = CertificateVerification.Pin("z".repeat(64))
        val failure = runCatching { bogus.bytes }.exceptionOrNull()
        assertTrue("a non-hex pin must not be quietly translated", failure is IllegalStateException)
    }

    @Test
    fun aPinIsNormalisedToLowerCase() {
        val url = parsed("$minimal?pin=${"AB".repeat(32)}")
        assertEquals(CertificateVerification.Pin("ab".repeat(32)), url.certificateVerification)
    }

    @Test
    fun aMalformedPinIsRejected() {
        listOf("tooshort", "z".repeat(64), "a".repeat(63), "a".repeat(65)).forEach { pin ->
            assertTrue("pin=$pin", NowhereUrl.parse("$minimal?pin=$pin").reasonOrNull() is UrlReason.InvalidPin)
        }
    }

    // ── ALPN, NW-P-08 ───────────────────────────────────────────────────────

    @Test
    fun alpnDefaultsAndCanBeOverridden() {
        assertEquals("now/1", parsed(minimal).alpn)
        assertEquals("h2", parsed("$minimal?alpn=h2").alpn)
    }

    @Test
    fun anOutOfRangeAlpnIsRejected() {
        assertTrue(NowhereUrl.parse("$minimal?alpn=").reasonOrNull() is UrlReason.InvalidAlpn)
        assertTrue(
            NowhereUrl.parse("$minimal?alpn=${"a".repeat(256)}").reasonOrNull() is UrlReason.InvalidAlpn,
        )
    }

    // ── Host and port ───────────────────────────────────────────────────────

    @Test
    fun anInvalidPortIsRejected() {
        assertTrue(NowhereUrl.parse("nowhere://k@example.com:0").reasonOrNull() is UrlReason.InvalidPort)
        assertTrue(NowhereUrl.parse("nowhere://k@example.com").reasonOrNull() is UrlReason.InvalidPort)
    }

    @Test
    fun anIpv6HostIsAccepted() {
        val url = parsed("nowhere://secret@[2001:db8::1]:443")
        assertEquals(443, url.port)
        assertTrue(url.host.contains("2001:db8"))
    }

    @Test
    fun malformedInputIsRejectedRatherThanCrashing() {
        listOf("", "not a url", "nowhere://", "://x", "nowhere://k@:443").forEach { input ->
            assertTrue("'$input' should be rejected", NowhereUrl.parse(input) is DecodeResult.Invalid)
        }
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("example.com", parsed("  $minimal\n").host)
    }

    // ── Round trip ──────────────────────────────────────────────────────────

    @Test
    fun generatedUrlsParseBackToAnEquivalentConfiguration() {
        listOf(
            "$minimal?up=tcp&down=tcp",
            "$minimal?up=tcp&down=tcp&mux=1",
            "$minimal?up=tcp&down=tcp&sni=real.example.com",
            "$minimal?up=tcp&down=tcp&pin=${"ab".repeat(32)}",
            "$minimal?up=tcp&down=tcp&rate=100&etar=50#My%20Node",
            "nowhere://a%2Bb@example.com:8443?up=udp&down=udp",
        ).forEach { original ->
            val first = parsed(original)
            val second = parsed(first.toUrl())
            assertEquals("round trip of $original", first.host, second.host)
            assertEquals(first.port, second.port)
            assertEquals(first.up, second.up)
            assertEquals(first.down, second.down)
            assertEquals(first.mux, second.mux)
            assertEquals(first.alpn, second.alpn)
            assertEquals(first.certificateVerification, second.certificateVerification)
            assertEquals(first.rateMbps, second.rateMbps)
            assertEquals(first.displayName, second.displayName)
            assertTrue(first.sharedKey.toByteArray().contentEquals(second.sharedKey.toByteArray()))
        }
    }

    @Test
    fun aGeneratedUrlUsesTheClientScheme() {
        assertTrue(parsed(minimal).toUrl().startsWith("nowhere://"))
    }
}
