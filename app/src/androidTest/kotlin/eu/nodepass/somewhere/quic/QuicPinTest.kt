// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/**
 * `pin` on the QUIC carrier, checked against the certificate a real Portal
 * actually presents.
 *
 * ## Why the pin is the Portal's own word
 *
 * A test that pins a value it also configured proves that the client agrees
 * with itself. The pin here is the fingerprint the Portal logged when it
 * generated its certificate, passed to the device by the script — a value no
 * code on this side produced.
 *
 * ## The control is the finding
 *
 * A matching pin completing the handshake proves little on its own. What has
 * to be true is the other half: that one wrong nibble stops it. Both are here,
 * and the second is the one worth reading.
 */
@RunWith(AndroidJUnit4::class)
class QuicPinTest {
    private fun pinned(): ByteArray = (E2eEnvironment.node(net = "udp").certificateVerification as CertificateVerification.Pin).bytes

    @Test
    fun thePortalsOwnFingerprintCompletesTheHandshake() {
        E2eEnvironment.openQuic().use { connection ->
            connection.completeHandshake()
            assertTrue("a correct pin refused the Portal's own certificate", connection.handshakeCompleted)
        }
    }

    @Test
    fun oneWrongNibbleStopsTheHandshake() {
        // The whole test. Everything else here would pass with verification
        // switched off entirely.
        val node = E2eEnvironment.node(net = "udp")
        val pin = pinned().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val wrong = node.copy(certificateVerification = CertificateVerification.Pin(pin.joinToString("") { "%02x".format(it) }))

        val failure = runCatching { E2eEnvironment.openQuic(wrong).use { it.completeHandshake() } }.exceptionOrNull()
        assertNotNull("a QUIC connection accepted a certificate the pin does not name", failure)
    }

    @Test
    fun aPinOfTheWrongLengthIsRefusedBeforeAnySocketIsOpened() {
        // Neither padded nor truncated. Either would produce a connection
        // verifying against something nobody asked for.
        val portal = E2eEnvironment.requirePortal()
        val refused =
            runCatching {
                QuicConnection.open(
                    remote = InetSocketAddress(portal.substringBeforeLast(':'), portal.substringAfterLast(':').toInt()),
                    serverName = null,
                    pinSha256 = ByteArray(16),
                    protect = { true },
                )
            }.exceptionOrNull()
        assertTrue("a 16-byte pin should be refused at the boundary, not carried", refused is IllegalArgumentException)
    }

    @Test
    fun neitherAPinNorAChainCheckIsRefused() {
        // v3 has no unverified mode, and the connection says so at the
        // boundary rather than connecting to whatever answers.
        val refused =
            runCatching {
                QuicConnection.open(remote = InetSocketAddress("127.0.0.1", 1), serverName = null, protect = { true })
            }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }

    @Test
    fun aPinIsThirtyTwoBytesBecauseItIsASha256() {
        assertEquals(32, QuicConnection.PIN_LENGTH)
    }
}
