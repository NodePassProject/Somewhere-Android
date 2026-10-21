// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import eu.nodepass.somewhere.protocol.url.CertificateVerification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The certificate decision both carriers hand their chain to.
 *
 * Over fixed certificates in `src/test/resources/certificates/` — a test CA, a
 * leaf it signed, and a self-signed leaf — with the CA as the only anchor, so
 * that every refusal here is the code's and not the JVM's trust store being
 * different on another machine.
 */
class PeerVerificationTest {
    private fun pem(name: String): X509Certificate =
        PeerVerificationTest::class.java.getResourceAsStream("/certificates/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val ca = pem("ca.pem")
    private val leaf = pem("leaf.pem")
    private val selfSigned = pem("self.pem")

    /** The test CA and nothing else, through the same trust-manager path the platform takes. */
    private val trustTheTestCa =
        ServerTrust { chain, _ ->
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            store.setCertificateEntry("ca", ca)
            TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(store) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
                .checkServerTrusted(chain, "UNKNOWN")
        }

    private val trustNothing = ServerTrust { _, _ -> throw CertificateException("no anchor") }

    private fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private fun chainOf(vararg certificates: X509Certificate) = certificates.map { it.encoded }

    // ── pin ─────────────────────────────────────────────────────────────────

    @Test
    fun theRightPinIsAcceptedWithoutConsultingTheChain() {
        // A pinned certificate is usually self-signed; checking its chain first
        // would refuse the very certificate the pin names.
        val refusal =
            PeerVerification.refusal(chainOf(selfSigned), CertificateVerification.Pin(sha256(selfSigned)), trustNothing)
        assertNull(refusal)
    }

    @Test
    fun aWrongPinIsRefusedAndTheRefusalNamesWhatWasPresented() {
        val refusal =
            PeerVerification.refusal(chainOf(selfSigned), CertificateVerification.Pin("00".repeat(32)), trustTheTestCa)
        assertNotNull(refusal)
        assertTrue("a mismatch must say what was presented: $refusal", refusal!!.contains(sha256(selfSigned)))
    }

    @Test
    fun thePinIsTheLeafsAndNotAnIntermediates() {
        // Pinning the CA by mistake must not accept the leaf it signed.
        val refusal = PeerVerification.refusal(chainOf(leaf, ca), CertificateVerification.Pin(sha256(ca)), trustTheTestCa)
        assertNotNull(refusal)
    }

    // ── chain and name ──────────────────────────────────────────────────────

    @Test
    fun aTrustedChainNamingTheHostIsAccepted() {
        assertNull(PeerVerification.refusal(chainOf(leaf, ca), CertificateVerification.Sni("portal.example"), trustTheTestCa))
    }

    @Test
    fun anUntrustedChainIsRefusedEvenWhenTheNameMatches() {
        // What a Portal on its generated certificate looks like to a link with
        // no pin: the name is right and nothing vouches for it.
        val refusal = PeerVerification.refusal(chainOf(selfSigned), CertificateVerification.Sni("portal.example"), trustTheTestCa)
        assertNotNull(refusal)
        assertTrue("the refusal should point at the pin: $refusal", refusal!!.contains("pin"))
    }

    @Test
    fun aTrustedChainForAnotherNameIsRefused() {
        val refusal = PeerVerification.refusal(chainOf(leaf, ca), CertificateVerification.Sni("elsewhere.example"), trustTheTestCa)
        assertTrue("$refusal", refusal!!.contains("elsewhere.example"))
    }

    @Test
    fun theCommonNameIsNeverConsulted() {
        // The leaf's CN is not-the-name.example and its SANs do not include it.
        // A verifier that fell back to the CN would accept this.
        val refusal = PeerVerification.refusal(chainOf(leaf, ca), CertificateVerification.Sni("not-the-name.example"), trustTheTestCa)
        assertNotNull(refusal)
    }

    @Test
    fun anEmptyChainIsRefused() {
        assertNotNull(PeerVerification.refusal(emptyList(), CertificateVerification.Sni("portal.example"), trustTheTestCa))
        assertNotNull(PeerVerification.refusal(emptyList(), CertificateVerification.Pin("00".repeat(32)), trustTheTestCa))
    }

    @Test
    fun bytesThatAreNotACertificateAreRefusedRatherThanThrown() {
        val refusal = PeerVerification.refusal(listOf(byteArrayOf(1, 2, 3)), CertificateVerification.Sni("portal.example"), trustTheTestCa)
        assertNotNull(refusal)
    }

    // ── hostname matching ───────────────────────────────────────────────────

    @Test
    fun anExactNameMatchesCaseInsensitively() {
        assertTrue(HostnameMatch.matches(leaf, "portal.example"))
        assertTrue(HostnameMatch.matches(leaf, "PORTAL.Example"))
        assertTrue("a trailing dot is the same name", HostnameMatch.matches(leaf, "portal.example."))
    }

    @Test
    fun aWildcardStandsForExactlyOneLeftmostLabel() {
        assertTrue(HostnameMatch.matches(leaf, "a.wild.example"))
        assertFalse("not zero labels", HostnameMatch.matches(leaf, "wild.example"))
        assertFalse("not two labels", HostnameMatch.matches(leaf, "a.b.wild.example"))
        assertFalse("not a suffix of a label", HostnameMatch.matches(leaf, "awild.example"))
    }

    @Test
    fun anIpLiteralMatchesAnIpEntryByItsBytes() {
        assertTrue(HostnameMatch.matches(leaf, "192.0.2.1"))
        assertTrue("any spelling of the same v6 address", HostnameMatch.matches(leaf, "2001:db8::1"))
        assertTrue(HostnameMatch.matches(leaf, "2001:0db8:0:0:0:0:0:1"))
        assertTrue("with URL brackets", HostnameMatch.matches(leaf, "[2001:db8::1]"))
        assertFalse(HostnameMatch.matches(leaf, "192.0.2.2"))
    }

    @Test
    fun anIpLiteralIsNotMatchedAgainstDnsNames() {
        // A dNSName that happens to spell an address is not an iPAddress entry.
        assertFalse(HostnameMatch.matches(selfSigned, "192.0.2.1"))
    }

    @Test
    fun aCertificateWithNoSubjectAltNamesMatchesNothing() {
        assertFalse(HostnameMatch.matches(ca, "Somewhere Test CA"))
    }

    @Test
    fun theFingerprintIsLowerCaseHexOfTheDer() {
        assertTrue(PeerVerification.fingerprint(leaf.encoded).matches(Regex("[0-9a-f]{64}")))
    }
}
