// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import eu.nodepass.somewhere.net.PlatformServerTrust
import eu.nodepass.somewhere.protocol.morph.MorphDatagram
import eu.nodepass.somewhere.protocol.morph.MorphKeys
import eu.nodepass.somewhere.protocol.tls.PeerVerification
import eu.nodepass.somewhere.protocol.tls.ServerTrust
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * A QUIC connection for a node, configured the one way a node says to.
 *
 * Kept in one place because there are three things to get right together and
 * every caller would otherwise get them right separately:
 *
 * - **Verification.** A pin is compared inside the handshake; otherwise the
 *   chain is checked against [trust] by name once the handshake completes. v3
 *   has no third option.
 * - **SNI.** The name being verified, unless it is an IP literal, which RFC
 *   6066 does not allow as SNI.
 * - **Morph.** The datagram mask, or none with `morph=0`.
 *
 * The tunnel and the device tests both open their connections here, so a test
 * that passes is a test of what the tunnel does.
 */
object QuicDial {
    fun open(
        node: NowhereUrl,
        remote: InetSocketAddress = InetSocketAddress(node.host, node.port),
        protect: (DatagramSocket) -> Boolean,
        trust: ServerTrust = PlatformServerTrust,
    ): QuicConnection {
        val policy = node.certificateVerification
        val name = (policy as? CertificateVerification.Sni)?.host ?: node.host
        val pin = (policy as? CertificateVerification.Pin)?.bytes
        return QuicConnection.open(
            remote = remote,
            serverName = name.takeUnless(::isIpLiteral),
            pinSha256 = pin,
            peerCheck = if (pin == null) QuicConnection.PeerCheck { PeerVerification.refusal(it, policy, trust) } else null,
            morph = if (node.morph) MorphDatagram.forClient(MorphKeys.derive(node.sharedKey)) else null,
            protect = protect,
        )
    }

    private fun isIpLiteral(host: String): Boolean = host.all { it.isDigit() || it == '.' } || ':' in host
}
