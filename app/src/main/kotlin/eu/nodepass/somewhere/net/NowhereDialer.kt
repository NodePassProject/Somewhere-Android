// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.net

import android.os.Build
import androidx.annotation.RequiresApi
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.tls.ConscryptExporter
import eu.nodepass.somewhere.protocol.tls.KeyingMaterialExporter
import eu.nodepass.somewhere.protocol.tls.PlatformExporter
import eu.nodepass.somewhere.protocol.tls.TlsTransport
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.conscrypt.Conscrypt
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

sealed interface DialReason : DecodeReason {
    data object Unreachable : DialReason {
        override val detail: String = "the Portal did not answer"
    }

    /**
     * The handshake did not complete, and the stack will not say why.
     *
     * Carries the ALPN because that is by far the most likely cause and the
     * only one the user can act on — but it is offered as a thing to check,
     * not asserted as the reason. See [AlpnRejected] for why this cannot be
     * narrowed further.
     */
    data class HandshakeFailed(
        val requestedAlpn: String,
    ) : DialReason {
        override val detail: String =
            "the TLS handshake did not complete; this node asks for ALPN '$requestedAlpn'"
    }

    /**
     * The Portal completed a handshake but did not select this node's ALPN.
     *
     * **This is narrower than it looks, on purpose.** A Portal that refuses the
     * protocol usually aborts with a `no_application_protocol` alert instead,
     * and Conscrypt surfaces that as `SSLProtocolException: Failure in SSL
     * library, usually a protocol error` — no alert code, no mention of ALPN.
     * The string `TLS alert, no application protocol` is what `openssl
     * s_client` prints; matching on it would produce a client that names this
     * failure correctly at a shell prompt and never on a device.
     *
     * So the aborting case is reported as [HandshakeFailed] with the ALPN
     * attached, and this reason is reserved for the case that can actually be
     * observed: a completed handshake carrying the wrong protocol.
     */
    data class AlpnRejected(
        val requested: String,
        val negotiated: String?,
    ) : DialReason {
        override val detail: String =
            "this node asks for ALPN '$requested'; the Portal selected " +
                (negotiated?.let { "'$it'" } ?: "none")
    }

    /**
     * The socket could not be kept out of our own tunnel.
     *
     * Only reachable with a VpnService running. It is a distinct reason rather
     * than folded into [Unreachable] because the two need opposite responses:
     * an unreachable Portal is worth retrying, and a Portal that cannot be
     * dialled without looping through our own TUN is not.
     */
    data object Unprotected : DialReason {
        override val detail: String = "the connection to the Portal could not be kept outside the tunnel"
    }

    data class PinMismatch(
        val expected: String,
        val actual: String,
    ) : DialReason {
        override val detail: String = "the certificate fingerprint is $actual, not the pinned $expected"
    }

    data object NoCertificate : DialReason {
        override val detail: String = "the Portal presented no certificate to pin against"
    }
}

/**
 * Opens an authenticated-capable transport to a Portal.
 *
 * ## The pairing this type exists to make impossible
 *
 * `PlatformExporter` rejects a socket Conscrypt made and `ConscryptExporter`
 * rejects a platform one — each correctly, since neither can read the other's
 * key schedule. That means the socket factory and the exporter are **one
 * decision, not two**, and splitting them across two call sites produces a
 * client that authenticates on some API levels and reports "unsupported socket"
 * on others. So both come from [carrier] and nothing else chooses either.
 *
 * The boundary is the same one `Exporters` already draws: the platform exposes
 * an exporter from API 31, and Conscrypt carries the same call down to 26.
 *
 * ## Certificate verification
 *
 * Three modes, from the URL, matching upstream (NW-P-09):
 *
 * - [CertificateVerification.Pin] — the leaf's SHA-256 must equal the pin. The
 *   chain is not consulted, and this **takes priority over `sni`** when a URL
 *   carries both, which is what upstream does.
 * - [CertificateVerification.Sni] — ordinary chain and hostname verification
 *   against that name, which is deliberately not the host dialled: the point of
 *   `sni` is to verify a name other than the address.
 * - [CertificateVerification.Skipped] — no verification at all. This is what
 *   every URL NowhereDash currently emits, so it is supported rather than
 *   refused (D-11) — but it is spelled out here, in one branch, rather than
 *   being what happens when nothing else matched.
 */
class NowhereDialer(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val protect: (Socket) -> Boolean = { true },
) {
    /**
     * One TLS stack, and everything that has to come from the same one.
     *
     * The socket factory, the ALPN accessor and the exporter are grouped
     * because they are not independently choosable: `PlatformExporter` cannot
     * read a Conscrypt socket's key schedule and `ConscryptExporter` cannot
     * read a platform one. An earlier version of this class chose them through
     * an injectable API level, which was convenient to test and hid the version
     * guard from lint — the same mistake `Exporters` already documents, made
     * one layer up. Lint caught it again. There is now no seam: [stack] reads
     * `Build.VERSION.SDK_INT` directly, and everything version-specific lives
     * behind `@RequiresApi` where the tooling can check it.
     *
     * On a JVM unit test `SDK_INT` reads as 0, which selects Conscrypt — the
     * only stack a JVM has, so the tests exercise the real path rather than a
     * substituted one.
     */
    private interface TlsStack {
        fun newContext(): SSLContext

        fun applyAlpn(
            socket: SSLSocket,
            alpn: String,
        )

        fun negotiated(socket: SSLSocket): String?

        fun exporter(): KeyingMaterialExporter
    }

    private object ConscryptStack : TlsStack {
        override fun newContext(): SSLContext = SSLContext.getInstance(TLS_VERSION, Conscrypt.newProvider())

        override fun applyAlpn(
            socket: SSLSocket,
            alpn: String,
        ) = Conscrypt.setApplicationProtocols(socket, arrayOf(alpn))

        override fun negotiated(socket: SSLSocket): String? = Conscrypt.getApplicationProtocol(socket)

        override fun exporter(): KeyingMaterialExporter = ConscryptExporter()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object PlatformStack : TlsStack {
        override fun newContext(): SSLContext = SSLContext.getInstance(TLS_VERSION)

        override fun applyAlpn(
            socket: SSLSocket,
            alpn: String,
        ) {
            val parameters = socket.sslParameters
            parameters.applicationProtocols = arrayOf(alpn)
            socket.sslParameters = parameters
        }

        override fun negotiated(socket: SSLSocket): String? = socket.applicationProtocol

        override fun exporter(): KeyingMaterialExporter = PlatformExporter()
    }

    private val stack: TlsStack
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PlatformStack
            } else {
                ConscryptStack
            }

    fun connect(node: NowhereUrl): DecodeResult<Transport> {
        val tls = stack
        val verification = node.certificateVerification
        val pinned = verification as? CertificateVerification.Pin

        val socket =
            when (val opened = openSocket(tls, node, verification)) {
                is DecodeResult.Invalid -> return opened
                is DecodeResult.Ok -> opened.value
            }

        if (pinned != null) {
            // Checked before a single Nowhere byte is written: a pin that is
            // verified after the authentication frame has gone out is not a pin.
            when (val checked = checkPin(socket, pinned.sha256)) {
                is DecodeResult.Invalid -> {
                    socket.close()
                    return checked
                }

                is DecodeResult.Ok -> Unit
            }
        }

        return when (val transport = TlsTransport.over(socket, tls.exporter())) {
            is DecodeResult.Invalid -> {
                socket.close()
                transport
            }

            is DecodeResult.Ok -> transport
        }
    }

    private fun openSocket(
        tls: TlsStack,
        node: NowhereUrl,
        verification: CertificateVerification,
    ): DecodeResult<SSLSocket> {
        val context = tls.newContext()

        // A pin does not consult the chain, so the trust manager must not
        // either — otherwise a self-signed certificate that matches the pin
        // exactly would be rejected by the chain check before the pin is ever
        // compared, and pinning would only work for certificates that did not
        // need it.
        val trustAll = verification !is CertificateVerification.Sni
        context.init(null, if (trustAll) arrayOf(AcceptAnyCertificate) else null, SecureRandom())

        // The name the certificate is checked against. For `sni` that is the
        // name from the URL, not the address dialled — verifying the address
        // would defeat the parameter's whole purpose.
        val verifyAs = (verification as? CertificateVerification.Sni)?.host ?: node.host

        return try {
            val raw = Socket()

            // Bind before protecting, and protect before connecting.
            //
            // `Socket()` in Java does not create the underlying file
            // descriptor: it is materialised on the first bind or connect. And
            // `VpnService.protect` protects a *descriptor*, so calling it on a
            // fresh socket protects nothing and returns false. Binding to an
            // ephemeral local address is the cheapest way to make the
            // descriptor exist without choosing a destination yet.
            //
            // Found on a device, and it presented as the tunnel refusing every
            // flow the moment it came up. Nothing else could have found it: on
            // a JVM there is no VpnService, so `protect` is the default no-op
            // and the socket connects perfectly well unbound.
            raw.bind(InetSocketAddress(0))

            // Keep this socket out of our own tunnel.
            //
            // With a VpnService up and a default route into it, an unprotected
            // socket to the Portal is routed into the TUN, arrives back at
            // lwIP, and is dialled again — a loop that presents as a hang
            // rather than as an error, because every layer involved is working
            // exactly as told. `protect` must be called here, before `connect`:
            // afterwards the routing decision has already been made.
            //
            // Outside a VpnService the default is a no-op, which is why the
            // unit tests dial normally.
            if (!protect(raw)) {
                raw.close()
                return DecodeResult.Invalid(DialReason.Unprotected)
            }

            raw.connect(InetSocketAddress(node.host, node.port), connectTimeoutMillis)
            val socket = context.socketFactory.createSocket(raw, verifyAs, node.port, true) as SSLSocket
            socket.soTimeout = readTimeoutMillis

            if (verification is CertificateVerification.Sni) {
                val parameters = socket.sslParameters
                parameters.serverNames = listOf(SNIHostName(verification.host))
                // Verified during the handshake rather than afterwards: a check
                // that runs after `startHandshake()` returns is a check that
                // runs after the first application byte could have been written.
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                socket.sslParameters = parameters
            }

            // NW-P-08: the ALPN comes from the node, never from a constant here.
            tls.applyAlpn(socket, node.alpn)
            socket.startHandshake()

            val negotiated = tls.negotiated(socket)?.takeIf { it.isNotEmpty() }
            if (negotiated != node.alpn) {
                socket.close()
                return invalid(DialReason.AlpnRejected(node.alpn, negotiated))
            }
            DecodeResult.Ok(socket)
        } catch (_: SSLException) {
            invalid(DialReason.HandshakeFailed(node.alpn))
        } catch (_: IOException) {
            invalid(DialReason.Unreachable)
        }
    }

    private fun checkPin(
        socket: SSLSocket,
        expected: String,
    ): DecodeResult<Unit> {
        val leaf =
            socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return invalid(DialReason.NoCertificate)
        val actual = fingerprint(leaf)
        return if (MessageDigest.isEqual(actual.toByteArray(), expected.lowercase().toByteArray())) {
            DecodeResult.Ok(Unit)
        } else {
            invalid(DialReason.PinMismatch(expected.lowercase(), actual))
        }
    }

    private companion object {
        const val TLS_VERSION = "TLSv1.3"

        /** Lower-case hex SHA-256 of the DER encoding, as `pin=` carries it. */
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }

        /**
         * Accepts every chain.
         *
         * Used for the pinned and unverified modes only. Named for what it does
         * rather than something reassuring, so that reading the call site tells
         * you what it means.
         */
        object AcceptAnyCertificate : X509TrustManager {
            @Throws(CertificateException::class)
            override fun checkClientTrusted(
                chain: Array<X509Certificate>,
                authType: String,
            ) = Unit

            @Throws(CertificateException::class)
            override fun checkServerTrusted(
                chain: Array<X509Certificate>,
                authType: String,
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
