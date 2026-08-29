// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * `pin` on the QUIC carrier, checked against the certificate a real Portal
 * actually presents.
 *
 * ## Why the pin is read rather than written down
 *
 * A test that pins a value it also configured proves that the client agrees
 * with itself. So the pin here is read off the Portal's own leaf over an
 * **independent TLS connection** — a different socket, a different protocol, a
 * different code path from the one under test — and only then handed back as
 * `pin=`. The same shape the TLS carrier's own test uses, for the same reason.
 *
 * ## The control is the finding
 *
 * A matching pin completing the handshake proves nothing on its own: the
 * connection would have completed anyway, because a QUIC connection with no pin
 * verifies nothing at all, which is upstream's behaviour and this client's
 * default. What has to be true is the other half — that one wrong nibble stops
 * it. Both are here, and the second is the one worth reading.
 */
@RunWith(AndroidJUnit4::class)
class QuicPinTest {
    @Test
    fun aPinReadFromThePortalsOwnCertificateCompletesTheHandshake() {
        val portal = E2eEnvironment.requirePortal()
        val pin = leafDigest(portal)
        connect(portal, pin).use { connection ->
            connection.completeHandshake()
            assertTrue("a correct pin refused the Portal's own certificate", connection.handshakeCompleted)
        }
    }

    @Test
    fun oneWrongNibbleStopsTheHandshake() {
        // The whole test. Everything else here would pass with verification
        // switched off entirely.
        val portal = E2eEnvironment.requirePortal()
        val pin = leafDigest(portal).copyOf()
        pin[0] = (pin[0].toInt() xor 0x01).toByte()

        val failure =
            runCatching {
                connect(portal, pin).use { it.completeHandshake() }
            }.exceptionOrNull()

        assertNotNull("a QUIC connection accepted a certificate the pin does not name", failure)
    }

    @Test
    fun aPinOfTheWrongLengthIsRefusedBeforeAnySocketIsOpened() {
        // Neither padded nor truncated. Either would produce a connection
        // verifying against something nobody asked for, which is worse than
        // not connecting.
        val portal = E2eEnvironment.requirePortal()
        val refused =
            runCatching { connect(portal, ByteArray(16)) }.exceptionOrNull()
        assertTrue(
            "a 16-byte pin should be refused at the boundary, not carried",
            refused is IllegalArgumentException,
        )
    }

    @Test
    fun aPinIsThirtyTwoBytesBecauseItIsASha256() {
        assertEquals(32, QuicConnection.PIN_LENGTH)
    }

    /**
     * The SHA-256 of the leaf the Portal presents, over its TLS listener.
     *
     * The Portal serves both carriers from one certificate; if that ever stops
     * being true this test says so by failing, which is worth knowing.
     */
    private fun leafDigest(portal: String): ByteArray {
        val host = portal.substringBeforeLast(':')
        val port = portal.substringAfterLast(':').toInt()
        val context =
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(AcceptAnything), java.security.SecureRandom())
            }
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS)
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.soTimeout = PROBE_TIMEOUT_MILLIS
        return socket.use {
            it.startHandshake()
            val leaf = it.session.peerCertificates.first() as X509Certificate
            MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        }
    }

    private fun connect(
        portal: String,
        pin: ByteArray?,
    ): QuicConnection {
        val host = portal.substringBeforeLast(':')
        val port = portal.substringAfterLast(':').toInt()
        return QuicConnection.open(
            remote = InetSocketAddress(host, port),
            alpn = ALPN,
            serverName = null,
            pinSha256 = pin,
            // No tunnel is up in this test, so there is nothing to protect
            // against.
            protect = { true },
        )
    }

    /** Reads the certificate without judging it; the pin is the judgement. */
    private object AcceptAnything : X509TrustManager {
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

    private companion object {
        const val ALPN = "now/1"
        const val PROBE_TIMEOUT_MILLIS = 10_000
    }
}
