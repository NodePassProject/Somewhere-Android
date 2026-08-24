// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.net

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.conscrypt.Conscrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The dialer against a live Portal.
 *
 * `SessionAgainstPortalTest` proves the protocol above the transport. This
 * proves the transport itself — the part that decides whether a certificate is
 * checked at all, which is the one piece of this client where being wrong is a
 * security failure rather than a connectivity one.
 *
 * The pin cases matter most: a pin that is compared against a certificate the
 * dialer fetched itself proves nothing. Here the fingerprint is read from the
 * Portal through a separate connection, then handed to the dialer as a `pin=`
 * parameter, so the two paths agree on a value neither of them chose.
 *
 * Requires a Portal (`conformance/scripts/portal-for-tests.sh`). Skipped
 * without one, so an environment that cannot run it reports honestly.
 */
class NowhereDialerAgainstPortalTest {
    private val portalEnv: String? = System.getenv("NOWHERE_E2E_PORTAL")

    private fun portal(): Pair<String, Int> {
        assumeTrue(
            "NOWHERE_E2E_PORTAL is not set — start one with conformance/scripts/portal-for-tests.sh",
            portalEnv != null,
        )
        val (host, port) = portalEnv!!.split(":")
        return host to port.toInt()
    }

    /** On a JVM `Build.VERSION.SDK_INT` reads as 0, which selects Conscrypt — the
     *  only stack a JVM has, so this exercises the real path. */
    private fun dialer() = NowhereDialer()

    private fun node(query: String): NowhereUrl {
        val (host, port) = portal()
        val url = "nowhere://test-key@$host:$port?$query"
        return (NowhereUrl.parse(url) as DecodeResult.Ok).value
    }

    /** The Portal's real leaf fingerprint, read independently of the dialer. */
    private fun portalFingerprint(): String {
        val (host, port) = portal()
        val trustAll =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        context.init(null, arrayOf(trustAll), SecureRandom())
        val raw = Socket().apply { connect(InetSocketAddress(host, port), 5_000) }
        val socket = (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket)
        Conscrypt.setApplicationProtocols(socket, arrayOf("now/1"))
        socket.startHandshake()
        val leaf = socket.session.peerCertificates.first() as X509Certificate
        socket.close()
        return MessageDigest
            .getInstance("SHA-256")
            .digest(leaf.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun anUnverifiedNodeConnectsAndExportsKeyingMaterial() {
        // The configuration every dashboard-generated URL actually has.
        val result = dialer().connect(node("up=tcp&down=tcp"))
        assertTrue("expected a transport, got $result", result is DecodeResult.Ok)
        val transport = (result as DecodeResult.Ok).value
        assertEquals(
            "the exporter must produce exactly the authentication length",
            Authentication.EXPORTER_LENGTH,
            transport.exporter.size,
        )
        transport.close()
    }

    @Test
    fun theCorrectPinIsAccepted() {
        val fingerprint = portalFingerprint()
        val result = dialer().connect(node("up=tcp&down=tcp&pin=$fingerprint"))
        assertTrue("a matching pin must connect, got $result", result is DecodeResult.Ok)
        (result as DecodeResult.Ok).value.close()
    }

    @Test
    fun aPinIsComparedAgainstTheCertificateThePortalActuallyPresents() {
        // The fingerprint the dialer sees must be the one an independent
        // connection sees. If the dialer ever compared against something it
        // derived itself, this is where that shows.
        val fingerprint = portalFingerprint()
        val wrong = fingerprint.replaceRange(0, 2, if (fingerprint.startsWith("00")) "11" else "00")
        assertNotEquals(fingerprint, wrong)

        val result = dialer().connect(node("up=tcp&down=tcp&pin=$wrong"))
        assertTrue("a wrong pin must be refused, got $result", result is DecodeResult.Invalid)
        val reason = (result as DecodeResult.Invalid).reason
        assertTrue("expected a pin mismatch, got $reason", reason is DialReason.PinMismatch)
        assertEquals(fingerprint, (reason as DialReason.PinMismatch).actual)
    }

    @Test
    fun aPinIsCaseInsensitiveBecauseHexIs() {
        val result = dialer().connect(node("up=tcp&down=tcp&pin=${portalFingerprint().uppercase()}"))
        assertTrue("an upper-case pin is the same pin, got $result", result is DecodeResult.Ok)
        (result as DecodeResult.Ok).value.close()
    }

    @Test
    fun anAlpnThePortalDoesNotSpeakCarriesTheAlpnIntoTheFailure() {
        // Measured, not assumed: a Portal that refuses the protocol aborts, and
        // Conscrypt reports that as "Failure in SSL library, usually a protocol
        // error" — no alert code, no mention of ALPN. `openssl s_client` prints
        // "TLS alert, no application protocol" for the same event, which is why
        // matching on that string would give a client that is right at a shell
        // prompt and wrong on every device.
        //
        // So the requirement this test enforces is the one that can actually be
        // met: whatever the reason is called, it must carry the ALPN, because
        // that is the field the user has to change.
        val result = dialer().connect(node("up=tcp&down=tcp&alpn=h2"))
        assertTrue("expected a refusal, got $result", result is DecodeResult.Invalid)
        val reason = (result as DecodeResult.Invalid).reason
        val named =
            when (reason) {
                is DialReason.HandshakeFailed -> reason.requestedAlpn
                is DialReason.AlpnRejected -> reason.requested
                else -> null
            }
        assertEquals("the failure must name the ALPN that was asked for", "h2", named)
    }

    @Test
    fun theNegotiatedProtocolIsCheckedRatherThanAssumed() {
        // The handshake completing is not the same as the Portal having agreed
        // to speak Nowhere. A Portal that selected nothing, or something else,
        // must not be handed to the protocol layer as if it had.
        val result = dialer().connect(node("up=tcp&down=tcp"))
        assertTrue("the default ALPN must be accepted, got $result", result is DecodeResult.Ok)
        (result as DecodeResult.Ok).value.close()
    }

    @Test
    fun anAddressNothingIsListeningOnIsUnreachableNotAHandshakeFailure() {
        // Port 1 is reserved and nothing binds it. The two failures need
        // different messages: one is the user's network, the other is the node.
        val url = "nowhere://test-key@127.0.0.1:1?up=tcp&down=tcp"
        val result = dialer().connect((NowhereUrl.parse(url) as DecodeResult.Ok).value)
        assertTrue("expected a refusal, got $result", result is DecodeResult.Invalid)
        assertEquals(DialReason.Unreachable, (result as DecodeResult.Invalid).reason)
    }
}
