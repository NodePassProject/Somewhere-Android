// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import eu.nodepass.somewhere.protocol.url.CertificateVerification
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Whether a chain is anchored in something this device trusts.
 *
 * Path building, expiry, constraints and revocation policy — everything except
 * the hostname, which [PeerVerification] matches itself so that both carriers
 * match it the same way. The production implementation is the platform's
 * trust manager; a test supplies its own anchor.
 */
fun interface ServerTrust {
    /** @throws CertificateException when the chain is not trusted. */
    fun check(
        chain: Array<X509Certificate>,
        host: String,
    )
}

/**
 * The certificate decision for both carriers, per `docs/security.md` §
 * Certificate trust: "the presented certificate matches the configured SHA-256
 * fingerprint", or system CA trust and the hostname.
 *
 * ## One decision, two carriers
 *
 * The TLS carrier used to verify inside the handshake and the QUIC carrier
 * inside its TLS callback, so a chain was accepted by two different pieces of
 * code that could disagree about what a hostname match is. Both now hand the
 * presented chain here once the handshake completes, and neither writes a
 * Nowhere byte until it has an answer. The ordering is the security property:
 * a check that runs before the AuthFrame is a check, one that runs after is a
 * log entry.
 *
 * ## The two policies
 *
 * - [CertificateVerification.Pin]: the leaf's DER SHA-256 must equal the pin,
 *   in constant time, and nothing else is consulted — a pinned certificate is
 *   usually self-signed, and checking its chain first would reject the very
 *   certificate the pin names. Upstream and the Apple client do the same.
 * - [CertificateVerification.Sni]: the chain must be trusted by [ServerTrust]
 *   and the leaf must name the host. v3's default, with the host being the
 *   endpoint's own when the link carries no `sni`.
 */
object PeerVerification {
    /**
     * Null when [chain] satisfies [policy]; otherwise a sentence for the user.
     *
     * @param chain DER encodings, leaf first, as the peer presented them.
     */
    fun refusal(
        chain: List<ByteArray>,
        policy: CertificateVerification,
        trust: ServerTrust,
    ): String? {
        val leaf = chain.firstOrNull() ?: return "the Portal presented no certificate"
        return when (policy) {
            is CertificateVerification.Pin -> pinRefusal(leaf, policy)
            is CertificateVerification.Sni -> nameRefusal(chain, policy.host, trust)
        }
    }

    /** Lower-case hex SHA-256 of a DER encoding, as `pin=` carries it. */
    fun fingerprint(der: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(it) }

    private fun pinRefusal(
        leaf: ByteArray,
        pin: CertificateVerification.Pin,
    ): String? {
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf)
        if (MessageDigest.isEqual(actual, pin.bytes)) return null
        // The presented digest goes into the sentence. A mismatch has two
        // different causes — a wrong `pin=`, or a Portal serving a different
        // certificate on this listener than the one the pin was taken from —
        // and they cannot be told apart without it.
        return "the certificate fingerprint is ${fingerprint(leaf)}, not the pinned ${pin.sha256}"
    }

    private fun nameRefusal(
        chain: List<ByteArray>,
        host: String,
        trust: ServerTrust,
    ): String? {
        val certificates =
            try {
                val factory = CertificateFactory.getInstance("X.509")
                chain.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
            } catch (_: CertificateException) {
                return "the Portal presented a certificate that does not parse"
            }
        try {
            trust.check(certificates.toTypedArray(), host)
        } catch (failure: CertificateException) {
            return "the certificate is not trusted by this device (${failure.message ?: failure.javaClass.simpleName}); " +
                "a Portal on a generated certificate needs a pin"
        }
        if (!HostnameMatch.matches(certificates.first(), host)) {
            return "the certificate does not name $host"
        }
        return null
    }
}

/**
 * RFC 6125 hostname matching against a leaf's subjectAltName, and nothing else.
 *
 * - A DNS name matches a `dNSName` entry case-insensitively. A wildcard is
 *   honoured only as the whole leftmost label, and stands for exactly one
 *   label: `*.example.com` matches `a.example.com`, not `example.com` and not
 *   `a.b.example.com`.
 * - An IP literal matches an `iPAddress` entry by its bytes, never a DNS name
 *   that happens to spell it.
 * - No fallback to the subject's common name. Browsers and Android's own
 *   verifier dropped it; a client that kept it would accept certificates they
 *   refuse.
 */
object HostnameMatch {
    private const val DNS_NAME = 2
    private const val IP_ADDRESS = 7

    fun matches(
        certificate: X509Certificate,
        host: String,
    ): Boolean {
        val names = certificate.subjectAlternativeNames ?: return false
        val literal = ipLiteral(host)
        return names.any { entry ->
            val type = entry[0] as? Int
            val value = entry[1] as? String ?: return@any false
            when {
                literal != null && type == IP_ADDRESS -> ipLiteral(value)?.contentEquals(literal) == true
                literal == null && type == DNS_NAME -> dnsMatches(value, host)
                else -> false
            }
        }
    }

    private fun dnsMatches(
        pattern: String,
        host: String,
    ): Boolean {
        val wanted = host.lowercase().trimEnd('.')
        val offered = pattern.lowercase().trimEnd('.')
        if (!offered.startsWith("*.")) return offered == wanted
        val suffix = offered.substring(1) // ".example.com"
        if (!wanted.endsWith(suffix)) return false
        val label = wanted.substring(0, wanted.length - suffix.length)
        return label.isNotEmpty() && '.' !in label
    }

    /**
     * The address bytes when [text] is an IPv4 or IPv6 literal, else null.
     *
     * Checked by shape before [InetAddress] sees it, because given a name
     * `InetAddress.getByName` resolves it — a DNS query from inside a
     * certificate check.
     */
    private fun ipLiteral(text: String): ByteArray? {
        val bare = text.removeSurrounding("[", "]")
        val looksV4 = bare.isNotEmpty() && bare.all { it.isDigit() || it == '.' } && bare.count { it == '.' } == 3
        val looksV6 = ':' in bare && bare.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!looksV4 && !looksV6) return null
        return runCatching { InetAddress.getByName(bare).address }.getOrNull()
    }
}
