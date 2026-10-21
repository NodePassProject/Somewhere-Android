// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Share links, `docs/configuration.md` § Share links and § Parsing configuration.
 * No fixed vectors exist for this one, so the cases come from upstream's own
 * tables and from what NowhereDash actually emits.
 */
class NowhereUrlTest {
    private fun parsed(url: String): NowhereUrl = (NowhereUrl.parse(url) as DecodeResult.Ok).value

    private val key = "0123456789abcdef0123456789abcdef"
    private val minimal = "nowhere://$key@example.com:443"

    // ── Scheme ──────────────────────────────────────────────────────────────

    @Test
    fun theClientSchemeIsNowhere() {
        assertEquals("example.com", parsed(minimal).host)
        assertEquals(443, parsed(minimal).port)
    }

    @Test
    fun vectorIsNotTheClientScheme() {
        // The single most common confusion in this ecosystem: vector:// starts
        // the upstream Rust process and is not an import URL.
        val reason = NowhereUrl.parse("vector://$key@example.com:443").reasonOrNull()
        assertEquals(UrlReason.WrongScheme("vector"), reason)
    }

    @Test
    fun otherSchemesAreRejected() {
        listOf("portal", "egress", "https", "anywhere", "somewhere").forEach { scheme ->
            val reason = NowhereUrl.parse("$scheme://$key@example.com:443").reasonOrNull()
            assertTrue("$scheme should be rejected", reason is UrlReason.WrongScheme)
        }
    }

    @Test
    fun schemeMatchingIsCaseInsensitive() {
        assertEquals("example.com", parsed("NOWHERE://$key@example.com:443").host)
    }

    // ── Shared key ──────────────────────────────────────────────────────────

    @Test
    fun theKeyMustBeThirtyTwoLowercaseHexCharacters() {
        // "Portal, Vector and Egress service URLs require exactly 32 lowercase
        // hexadecimal text characters." A share link carrying any other key
        // names a Portal that cannot exist.
        listOf("secret", key.uppercase(), key.dropLast(1), key + "0", "g" + key.drop(1)).forEach { bad ->
            assertEquals("key '$bad'", AuthReason.NotServiceFormat, NowhereUrl.parse("nowhere://$bad@example.com:443").reasonOrNull())
        }
    }

    @Test
    fun theKeyIsPercentDecodedOnce() {
        // %30 is '0': a percent-encoded hex key is the same key.
        assertEquals(key, String(parsed("nowhere://%30${key.drop(1)}@example.com:443").sharedKey.toByteArray()))
    }

    @Test
    fun aPasswordComponentMakesTheUrlInvalid() {
        assertEquals(AuthReason.PasswordComponentPresent, NowhereUrl.parse("nowhere://$key:pass@example.com:443").reasonOrNull())
    }

    @Test
    fun aUrlWithoutASharedKeyIsRejected() {
        assertEquals(AuthReason.MissingUserInfo, NowhereUrl.parse("nowhere://example.com:443").reasonOrNull())
    }

    // ── Defaults ────────────────────────────────────────────────────────────

    @Test
    fun defaultsMatchTheSpecification() {
        val url = parsed(minimal)
        assertEquals("net defaults to tcp in v3", NextHopCarrier.Tcp, url.net)
        assertFalse(url.usesQuic)
        assertTrue("morph defaults to on", url.morph)
        assertEquals("sni defaults to the endpoint host", CertificateVerification.Sni("example.com"), url.certificateVerification)
        assertEquals(0, url.rateMbps)
        assertEquals(0, url.etarMbps)
        assertNull(url.displayName)
    }

    // ── net ─────────────────────────────────────────────────────────────────

    @Test
    fun netSelectsTheCarrier() {
        assertEquals(NextHopCarrier.Tcp, parsed("$minimal?net=tcp").net)
        assertEquals(NextHopCarrier.Udp, parsed("$minimal?net=udp").net)
        assertTrue(parsed("$minimal?net=udp").usesQuic)
    }

    @Test
    fun anInvalidCarrierIsRejected() {
        listOf("carrier-pigeon", "mix", "TCP", "").forEach { value ->
            assertTrue("net=$value", NowhereUrl.parse("$minimal?net=$value").reasonOrNull() is UrlReason.InvalidCarrier)
        }
    }

    // ── A Nowhere 2.x dashboard ─────────────────────────────────────────────

    @Test
    fun agreeingLegacyCarriersAreTakenAsNet() {
        // NowhereDash emits up/down with no net. Read strictly they are unknown
        // and would land on tcp; for a QUIC-only node that is a dial to a port
        // nobody answers.
        assertEquals(NextHopCarrier.Udp, parsed("$minimal?up=udp&down=udp").net)
        assertEquals(NextHopCarrier.Tcp, parsed("$minimal?up=tcp&down=tcp&mux=1").net)
    }

    @Test
    fun aLegacySplitFallsBackToTheDefault() {
        assertEquals(NextHopCarrier.Tcp, parsed("$minimal?up=tcp&down=udp").net)
        assertEquals(NextHopCarrier.Tcp, parsed("$minimal?up=udp").net)
    }

    @Test
    fun netWinsOverLegacyCarriers() {
        assertEquals(NextHopCarrier.Tcp, parsed("$minimal?up=udp&down=udp&net=tcp").net)
    }

    @Test
    fun legacyParametersAreIgnoredRatherThanRejected() {
        // `mux`, `alpn`, `pool`: removed in v3, 1.8 and 1.8 respectively, and
        // still in links dashboards produce.
        val url = parsed("$minimal?mux=1&alpn=h2&pool=5&up=carrier-pigeon")
        assertEquals(NextHopCarrier.Tcp, url.net)
    }

    @Test
    fun aCarrierPathIsRefusedWithItsName() {
        // What a 2.x dashboard emits when a Portal's two ports differ. Share
        // links take HOST:PORT only.
        val reason = NowhereUrl.parse("nowhere://$key@example.com/tcp:443/udp:8443?morph=1").reasonOrNull()
        assertEquals(UrlReason.CarrierPath("/tcp:443/udp:8443"), reason)
    }

    // ── morph ───────────────────────────────────────────────────────────────

    @Test
    fun morphIsZeroOrOne() {
        assertTrue(parsed("$minimal?morph=1").morph)
        assertFalse(parsed("$minimal?morph=0").morph)
        listOf("true", "2", "").forEach { value ->
            assertTrue("morph=$value", NowhereUrl.parse("$minimal?morph=$value").reasonOrNull() is UrlReason.InvalidMorph)
        }
    }

    @Test
    fun theFirstDuplicateWins() {
        // Upstream's own table: `morph=1&morph=0` is Morph on, and
        // `morph=invalid&morph=1` is a configuration error.
        assertTrue(parsed("$minimal?morph=1&morph=0").morph)
        assertTrue(NowhereUrl.parse("$minimal?morph=invalid&morph=1").reasonOrNull() is UrlReason.InvalidMorph)
    }

    // ── Other parameters ────────────────────────────────────────────────────

    @Test
    fun unknownParametersAreIgnoredRatherThanRejected() {
        val url = parsed("$minimal?net=udp&future=whatever&another=1&x")
        assertEquals(NextHopCarrier.Udp, url.net)
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

    @Test
    fun aMalformedRateIsRejected() {
        listOf("-1", "fast", "2147483648", "").forEach { value ->
            assertTrue("rate=$value", NowhereUrl.parse("$minimal?rate=$value").reasonOrNull() is UrlReason.InvalidRate)
        }
    }

    // ── Certificate verification ────────────────────────────────────────────

    @Test
    fun noneAndEmptyMeanTheDefault() {
        // "empty or `none` uses the host" — for both parameters.
        listOf("sni=none&pin=none", "sni=&pin=", "sni=NONE").forEach { query ->
            assertEquals(query, CertificateVerification.Sni("example.com"), parsed("$minimal?$query").certificateVerification)
        }
    }

    @Test
    fun sniNamesAnotherHostToVerify() {
        val url = parsed("$minimal?sni=real.example.com")
        assertEquals(CertificateVerification.Sni("real.example.com"), url.certificateVerification)
    }

    @Test
    fun sniMustBeAnAsciiDnsName() {
        listOf("under_score.example", "-lead.example", "a..b", "例え.jp").forEach { name ->
            assertTrue("sni=$name", NowhereUrl.parse("$minimal?sni=$name").reasonOrNull() is UrlReason.InvalidSni)
        }
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
        // certificate object, the QUIC path hands 32 bytes across a JNI
        // boundary. One conversion serves both.
        val pin = CertificateVerification.Pin("00112233445566778899aabbccddeeff" + "ffeeddccbbaa99887766554433221100")
        val bytes = pin.bytes
        assertEquals("a pin is a SHA-256, so 32 bytes", 32, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x11.toByte(), bytes[1])
        assertEquals(0xff.toByte(), bytes[15])
        assertEquals(0xff.toByte(), bytes[16])
        assertEquals(0x00.toByte(), bytes[31])
        assertEquals("the bytes must render back to the pin", pin.sha256, bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun aPinBuiltByHandFromSomethingThatIsNotHexFailsLoudly() {
        // Unreachable through the parser; reachable by constructing the value
        // directly, and the alternative to throwing is a pin that verifies
        // against a digest nobody chose.
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

    // ── Host and port ───────────────────────────────────────────────────────

    @Test
    fun anInvalidPortIsRejected() {
        assertTrue(NowhereUrl.parse("nowhere://$key@example.com:0").reasonOrNull() is UrlReason.InvalidPort)
        assertTrue(NowhereUrl.parse("nowhere://$key@example.com").reasonOrNull() is UrlReason.InvalidPort)
    }

    @Test
    fun anIpv6HostLosesItsBrackets() {
        // The brackets are URL syntax. A host handed to a resolver or matched
        // against a certificate's iPAddress has none.
        val url = parsed("nowhere://$key@[2001:db8::1]:443")
        assertEquals(443, url.port)
        assertEquals("2001:db8::1", url.host)
        assertEquals(CertificateVerification.Sni("2001:db8::1"), url.certificateVerification)
        assertTrue("and gets them back when rendered", url.toUrl().contains("@[2001:db8::1]:443"))
    }

    @Test
    fun malformedInputIsRejectedRatherThanCrashing() {
        listOf("", "not a url", "nowhere://", "://x", "nowhere://$key@:443").forEach { input ->
            assertTrue("'$input' should be rejected", NowhereUrl.parse(input) is DecodeResult.Invalid)
        }
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("example.com", parsed("  $minimal\n").host)
    }

    // ── Round trip ──────────────────────────────────────────────────────────

    @Test
    fun generatedUrlsParseBackToTheSameNode() {
        listOf(
            minimal,
            "$minimal?net=udp",
            "$minimal?morph=0",
            "$minimal?net=udp&sni=real.example.com",
            "$minimal?pin=${"ab".repeat(32)}",
            "$minimal?rate=100&etar=50#My%20Node",
            "nowhere://$key@[2001:db8::1]:8443?net=udp",
        ).forEach { original ->
            val first = parsed(original)
            assertEquals("round trip of $original", first, parsed(first.toUrl()))
        }
    }

    @Test
    fun defaultsAreLeftOutOfAGeneratedUrl() {
        // A v3 link that names its defaults is longer and says nothing more.
        assertEquals(minimal, parsed("$minimal?net=tcp&morph=1&sni=example.com").toUrl())
    }

    @Test
    fun aLegacyLinkIsRewrittenInV3Form() {
        // What the client stores and shares is the v3 spelling of what it
        // understood, not the dashboard's 2.x one.
        assertEquals("$minimal?net=udp", parsed("$minimal?up=udp&down=udp&mux=1").toUrl())
    }
}
