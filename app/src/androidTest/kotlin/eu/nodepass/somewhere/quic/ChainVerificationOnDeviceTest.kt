// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.net.DialReason
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verification by name, against the platform's own trust store, on a device.
 *
 * The JVM tests exercise [eu.nodepass.somewhere.protocol.tls.PeerVerification]
 * with a test anchor; what only a device can run is the platform half —
 * `X509TrustManagerExtensions` with the host — because on a JVM that branch is
 * not taken. These cases are the refusal: a Portal on its generated
 * certificate, dialled by name with no pin, must be refused on both carriers,
 * and refused *as a trust failure* rather than by an exception from somewhere
 * else, which is what an authentication type the platform did not accept would
 * look like.
 *
 * The acceptance half needs a certificate this device trusts, which means a
 * CA whose key exists somewhere a test can sign with. Not done: committing
 * such a key would make every debug build trust whoever reads this repository.
 */
@RunWith(AndroidJUnit4::class)
class ChainVerificationOnDeviceTest {
    private fun byName(net: String) =
        E2eEnvironment.node(net = net).let { it.copy(certificateVerification = CertificateVerification.Sni(it.host)) }

    @Test
    fun aGeneratedCertificateIsRefusedOverTls() {
        val result = NowhereDialer(protect = { true }).connect(byName("tcp"))
        val reason = result.reasonOrNull()
        assertTrue("expected a trust refusal, got ${reason?.detail}", reason is DialReason.Untrusted)
    }

    @Test
    fun aGeneratedCertificateIsRefusedOverQuic() {
        val failure =
            runCatching { E2eEnvironment.openQuic(byName("udp")).use { it.completeHandshake() } }.exceptionOrNull()
        assertTrue("expected a QUIC failure, got $failure", failure is QuicException)
        val reason = (failure as QuicException).failure
        assertTrue("expected a trust refusal, got ${reason.detail}", reason is QuicFailure.Untrusted)
    }

    @Test
    fun aRefusedConnectionIsNotLeftOpen() {
        val before = QuicConnection.liveConnections()
        runCatching { E2eEnvironment.openQuic(byName("udp")).use { it.completeHandshake() } }
        assertEquals(before, QuicConnection.liveConnections())
    }
}
