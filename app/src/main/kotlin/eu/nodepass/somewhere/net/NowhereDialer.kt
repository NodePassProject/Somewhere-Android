// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.net

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.Nw3
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.morph.MorphKeys
import eu.nodepass.somewhere.protocol.morph.MorphSocket
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.tls.ConscryptExporter
import eu.nodepass.somewhere.protocol.tls.PeerVerification
import eu.nodepass.somewhere.protocol.tls.ServerTrust
import eu.nodepass.somewhere.protocol.tls.TlsTransport
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.conscrypt.Conscrypt
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
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
     * The likeliest causes are named as things to check rather than asserted.
     * A Portal refusing the ALPN aborts with an alert Conscrypt reports as a
     * generic protocol error; a Morph mismatch — one side masking, the other
     * not — makes each side read the other's bytes as noise, which ends the
     * same way. Neither is observable as itself from here.
     */
    data object HandshakeFailed : DialReason {
        override val detail: String =
            "the TLS handshake did not complete; check that the Portal speaks ${Nw3.ALPN} and that both sides agree on morph"
    }

    /**
     * The Portal completed a handshake but did not select `nw3`.
     *
     * **Narrower than it looks, on purpose.** A Portal that refuses the
     * protocol usually aborts with a `no_application_protocol` alert, and
     * Conscrypt surfaces that as `SSLProtocolException: Failure in SSL library,
     * usually a protocol error` — no alert code, no mention of ALPN. Matching
     * on `openssl s_client`'s wording would name this correctly at a shell
     * prompt and never on a device. So the aborting case is [HandshakeFailed],
     * and this is reserved for what can be observed: a completed handshake
     * carrying the wrong protocol.
     */
    data class AlpnRejected(
        val negotiated: String?,
    ) : DialReason {
        override val detail: String =
            "the Portal selected " + (negotiated?.let { "ALPN '$it'" } ?: "no ALPN") + " rather than ${Nw3.ALPN}"
    }

    /**
     * The socket could not be kept out of our own tunnel.
     *
     * Only reachable with a VpnService running. Distinct from [Unreachable]
     * because the two need opposite responses: an unreachable Portal is worth
     * retrying, and one that cannot be dialled without looping through our own
     * TUN is not.
     */
    data object Unprotected : DialReason {
        override val detail: String = "the connection to the Portal could not be kept outside the tunnel"
    }

    /** The certificate did not satisfy the node's policy. [why] says how. */
    data class Untrusted(
        val why: String,
    ) : DialReason {
        override val detail: String = why
    }
}

/**
 * Opens an authenticated-capable TLS transport to a Portal, Morph and all.
 *
 * ## What sits under TLS
 *
 * With Morph on — the default — the TCP connection opens with a 64-byte
 * prelude and a 12-byte nonce, and every TLS byte in both directions is XORed
 * with a ChaCha20 keystream ([MorphSocket]). TLS therefore has to be layered
 * over something that is not a plain socket, and **this is why the stack is
 * Conscrypt in engine-socket mode on every API level.** Conscrypt's other
 * layered mode looks for the wrapped socket's file descriptor and, finding
 * one, reads and writes it directly: the handshake would go out unmasked to a
 * Portal expecting masked bytes, and fail as noise. Engine mode goes through
 * the wrapper's streams, and [Conscrypt.setUseEngineSocket] is how it is
 * chosen — which is also why the platform provider, whose factory cannot be
 * told, is no longer used here. See ADR-0003.
 *
 * ## Certificate verification
 *
 * The handshake accepts any chain, and [PeerVerification] then decides, before
 * a single Nowhere byte is written: the leaf's SHA-256 against `pin`, or the
 * platform trust store and the hostname against `sni` (the endpoint host when
 * the link names none). The QUIC carrier hands its chain to the same object,
 * so both carriers agree on what a match is.
 *
 * The SNI sent in the ClientHello is the name being verified, which is the
 * point of `sni`: to verify a name other than the address dialled.
 */
class NowhereDialer(
    private val connectTimeoutMillis: Int = 10_000,
    /**
     * The setup deadline. Twenty seconds, the Portal's own flow-setup limit:
     * its target dial gives up at fifteen and answers `DIAL_FAILED`, and a
     * client deadline at fifteen would race that answer and report silence.
     */
    private val readTimeoutMillis: Int = 20_000,
    private val protect: (Socket) -> Boolean = { true },
    private val trust: ServerTrust = PlatformServerTrust,
) {
    fun connect(node: NowhereUrl): DecodeResult<Transport> {
        val socket =
            when (val opened = openSocket(node)) {
                is DecodeResult.Invalid -> return opened
                is DecodeResult.Ok -> opened.value
            }

        val chain = socket.session.peerCertificates.map { it.encoded }
        PeerVerification.refusal(chain, node.certificateVerification, trust)?.let { refusal ->
            socket.close()
            return invalid(DialReason.Untrusted(refusal))
        }

        return when (val transport = TlsTransport.over(socket, ConscryptExporter())) {
            is DecodeResult.Invalid -> {
                socket.close()
                transport
            }

            is DecodeResult.Ok -> transport
        }
    }

    private fun openSocket(node: NowhereUrl): DecodeResult<SSLSocket> {
        val context = SSLContext.getInstance(TLS_VERSION, Conscrypt.newProvider())
        // Every chain is accepted here and judged by PeerVerification once the
        // handshake is done; see the class comment for why that is not later
        // than it sounds.
        context.init(null, arrayOf(AcceptForLaterVerification), SecureRandom())
        val factory = context.socketFactory
        Conscrypt.setUseEngineSocket(factory, true)

        val verifyAs = (node.certificateVerification as? CertificateVerification.Sni)?.host ?: node.host

        var raw: Socket? = null
        return try {
            val tcp = Socket().also { raw = it }

            // Bind before protecting, and protect before connecting.
            //
            // `Socket()` does not create its file descriptor until the first
            // bind or connect, and `VpnService.protect` protects a descriptor,
            // so protecting a fresh socket protects nothing and returns false.
            // Found on a device, where it presented as the tunnel refusing
            // every flow the moment it came up; on a JVM `protect` is a no-op
            // and the socket connects perfectly well unbound.
            tcp.bind(InetSocketAddress(0))

            // Keep this socket out of our own tunnel. Afterwards the routing
            // decision is already made, so this must come before `connect`.
            if (!protect(tcp)) {
                tcp.close()
                return DecodeResult.Invalid(DialReason.Unprotected)
            }

            tcp.connect(InetSocketAddress(node.host, node.port), connectTimeoutMillis)
            tcp.tcpNoDelay = true
            val carrier: Socket =
                if (node.morph) {
                    MorphSocket.over(tcp, MorphKeys.derive(node.sharedKey))
                } else {
                    tcp
                }

            val socket = factory.createSocket(carrier, verifyAs, node.port, true) as SSLSocket
            socket.soTimeout = readTimeoutMillis
            if (!isIpLiteral(verifyAs)) {
                val parameters = socket.sslParameters
                parameters.serverNames = listOf(SNIHostName(verifyAs))
                socket.sslParameters = parameters
            }
            Conscrypt.setApplicationProtocols(socket, arrayOf(Nw3.ALPN))
            socket.startHandshake()

            val negotiated = Conscrypt.getApplicationProtocol(socket)?.takeIf { it.isNotEmpty() }
            if (negotiated != Nw3.ALPN) {
                socket.close()
                return invalid(DialReason.AlpnRejected(negotiated))
            }
            DecodeResult.Ok(socket)
        } catch (_: SSLException) {
            runCatching { raw?.close() }
            invalid(DialReason.HandshakeFailed)
        } catch (_: IOException) {
            runCatching { raw?.close() }
            invalid(DialReason.Unreachable)
        }
    }

    private companion object {
        const val TLS_VERSION = "TLSv1.3"

        /** An IP literal is not a valid SNI value (RFC 6066 § 3), so none is sent for one. */
        fun isIpLiteral(host: String): Boolean = host.all { it.isDigit() || it == '.' } || ':' in host

        /**
         * Accepts every chain *during the handshake*, because the decision is
         * made after it by [PeerVerification] and must be made in one place.
         * Named for what it does and what it relies on, so that reading the
         * call site does not suggest that nothing is verified.
         */
        object AcceptForLaterVerification : X509TrustManager {
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
