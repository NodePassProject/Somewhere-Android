// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.net.DialReason
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The node list's QUIC probe, against a real Portal.
 *
 * The probe exists because the list used to measure QUIC nodes over TCP. What
 * has to hold is that it now reports what QUIC says: an answer when the Portal
 * answers, a refusal — not "unreachable" — when the pin is wrong, and silence
 * when nothing is listening on UDP even though TCP would answer.
 */
@RunWith(AndroidJUnit4::class)
class QuicProbeTest {
    @Test
    fun aPortalThatAnswersOverQuicIsReachable() {
        assertNull(QuicProbe.probe(E2eEnvironment.node(net = "udp")))
    }

    @Test
    fun aWrongPinIsARefusalRatherThanUnreachability() {
        // The difference decides the message on the card: "check the pin", not
        // "check the network".
        val node = E2eEnvironment.node(net = "udp")
        val pin = (node.certificateVerification as CertificateVerification.Pin).bytes.also { it[0] = (it[0].toInt() xor 1).toByte() }
        val wrong = node.copy(certificateVerification = CertificateVerification.Pin(pin.joinToString("") { "%02x".format(it) }))
        val reason = QuicProbe.probe(wrong)
        assertTrue("a wrong pin should read as untrusted, got $reason", reason is DialReason.Untrusted)
    }

    @Test
    fun aWrongPinSurvivesTheTransportErrorThatFollowsIt() {
        // The bridge records the refusal and the TLS stack then fails the
        // handshake with ERR_CRYPTO, which used to overwrite it. The tunnel
        // reads this message to decide between "refused, stop" and
        // "unreachable, fail over", so losing it walked the node list on a
        // wrong pin.
        val node = E2eEnvironment.node(net = "udp")
        val pin = (node.certificateVerification as CertificateVerification.Pin).bytes.also { it[31] = (it[31].toInt() xor 1).toByte() }
        val wrong = node.copy(certificateVerification = CertificateVerification.Pin(pin.joinToString("") { "%02x".format(it) }))
        val failure = runCatching { QuicDial.open(wrong, protect = { true }).use { it.completeHandshake() } }.exceptionOrNull()
        assertTrue(
            "expected the refusal in the message, got ${failure?.message}",
            failure?.message?.contains("does not match the pin") == true,
        )
    }

    @Test
    fun aPortWithNothingOnUdpIsUnreachable() {
        // Port 1 on the host: nothing listens there on either transport, and
        // the probe must not fall back to anything that would say otherwise.
        val node = E2eEnvironment.node(net = "udp")
        val closed = node.copy(port = 1)
        assertEquals(DialReason.Unreachable, QuicProbe.probe(closed))
    }
}
