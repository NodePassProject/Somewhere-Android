// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.net

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The dialer against a live v3 Portal.
 *
 * `SessionAgainstPortalTest` proves the protocol above the transport. This
 * proves the transport itself: that Morph masks the bytes the way a Portal
 * unmasks them, and that a certificate is checked — the one piece of this
 * client where being wrong is a security failure rather than a connectivity
 * one.
 *
 * The pin is the fingerprint the Portal logged at startup, exported by
 * `conformance/scripts/portal-for-tests.sh`. Neither the dialer nor any
 * connection this test makes produced it, so a pass cannot be the dialer
 * agreeing with itself.
 *
 * Requires a Portal. Skipped without one, so an environment that cannot run it
 * reports honestly.
 */
class NowhereDialerAgainstPortalTest {
    private val portalEnv: String? = System.getenv("NOWHERE_E2E_PORTAL")
    private val keyEnv: String? = System.getenv("NOWHERE_E2E_KEY")
    private val pinEnv: String? = System.getenv("NOWHERE_E2E_PIN")

    private fun portal(): Pair<String, Int> {
        assumeTrue(
            "NOWHERE_E2E_PORTAL is not set — start one with conformance/scripts/portal-for-tests.sh",
            portalEnv != null && keyEnv != null && pinEnv != null,
        )
        val (host, port) = portalEnv!!.split(":")
        return host to port.toInt()
    }

    private fun dialer() = NowhereDialer()

    private fun node(query: String = ""): NowhereUrl {
        val (host, port) = portal()
        val url = "nowhere://$keyEnv@$host:$port" + if (query.isEmpty()) "" else "?$query"
        return (NowhereUrl.parse(url) as DecodeResult.Ok).value
    }

    @Test
    fun aPinnedNodeConnectsThroughMorphAndExportsKeyingMaterial() {
        // The configuration a generated-certificate Portal needs, over the
        // carrier and mask a bare v3 link selects: TLS with Morph on.
        val result = dialer().connect(node("pin=$pinEnv"))
        assertTrue("expected a transport, got ${result.reasonOrNull()?.detail}", result is DecodeResult.Ok)
        val transport = (result as DecodeResult.Ok).value
        assertEquals(Authentication.EXPORTER_LENGTH, transport.exporter.size)
        transport.close()
    }

    @Test
    fun aWrongPinIsRefusedAndNamesWhatWasPresented() {
        portal()
        val pin = pinEnv!!
        val wrong = pin.replaceRange(0, 2, if (pin.startsWith("00")) "11" else "00")
        assertNotEquals(pin, wrong)

        val result = dialer().connect(node("pin=$wrong"))
        val reason = (result as DecodeResult.Invalid).reason
        assertTrue("expected the certificate to be refused, got $reason", reason is DialReason.Untrusted)
        assertTrue("the refusal must name the presented fingerprint", reason.detail.contains(pin))
    }

    @Test
    fun aPinIsCaseInsensitiveBecauseHexIs() {
        portal()
        val result = dialer().connect(node("pin=${pinEnv!!.uppercase()}"))
        assertTrue("an upper-case pin is the same pin, got ${result.reasonOrNull()?.detail}", result is DecodeResult.Ok)
        (result as DecodeResult.Ok).value.close()
    }

    @Test
    fun aGeneratedCertificateIsNotTrustedByName() {
        // v3 has no "verify nothing" mode. A bare link to a Portal on its
        // generated certificate verifies the chain against the host, finds no
        // anchor for it, and must say so rather than connecting.
        val result = dialer().connect(node())
        val reason = (result as DecodeResult.Invalid).reason
        assertTrue("an untrusted chain must be refused, got $reason", reason is DialReason.Untrusted)
    }

    @Test
    fun aMorphMismatchFailsTheHandshake() {
        // The Portal masks and this side does not, so it reads our ClientHello
        // as noise. Since 3.1 it says nothing at all — it holds the connection
        // to its deadline and resets it — so what is observable is a connection
        // accepted and then closed unanswered, and the reason says what to check.
        val result = dialer().connect(node("morph=0&pin=$pinEnv"))
        val reason = (result as DecodeResult.Invalid).reason
        assertEquals(DialReason.Silent, reason)
        assertTrue("the message names morph as a thing to check", reason.detail.contains("morph"))
    }

    @Test
    fun anAddressNothingIsListeningOnIsUnreachableNotAHandshakeFailure() {
        // Port 1 is reserved and nothing binds it. The two failures need
        // different messages: one is the user's network, the other is the node.
        val url = "nowhere://0123456789abcdef0123456789abcdef@127.0.0.1:1"
        val result = dialer().connect((NowhereUrl.parse(url) as DecodeResult.Ok).value)
        assertEquals(DialReason.Unreachable, (result as DecodeResult.Invalid).reason)
    }
}
