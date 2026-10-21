// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import eu.nodepass.somewhere.net.DialReason
import eu.nodepass.somewhere.protocol.url.NowhereUrl

/**
 * Whether a QUIC node answers: a handshake, the certificate check, and a close.
 *
 * The node list once probed every node over TLS on TCP, QUIC ones included. A
 * Portal listens on both at the same port, so on a test Portal the two answers
 * agreed — and in the field they need not: a container that publishes only
 * UDP, or a network that blocks it, makes the TCP answer about a path the node
 * never uses. A QUIC node is probed over QUIC.
 *
 * Like the TLS probe, this proves the address, the port, the ALPN and the
 * certificate, and not the key: authentication rides the first flow, and a
 * probe opens none.
 */
object QuicProbe {
    /** Null when the node answered and its certificate was accepted. */
    fun probe(node: NowhereUrl): DialReason? =
        try {
            // Probes run in the app's own process, which is outside its own
            // tunnel in every mode (fact 28), so there is nothing to protect
            // the socket from.
            QuicDial.open(node, protect = { true }).use { it.completeHandshake() }
            null
        } catch (error: QuicException) {
            reasonFor(error.failure)
        } catch (_: Exception) {
            // An unresolvable name, a socket that would not open.
            DialReason.Unreachable
        } catch (_: UnsatisfiedLinkError) {
            // No QUIC library in this process — a JVM test, or a build that
            // dropped it. Saying the node did not answer is the truth as far as
            // this process can tell it, and better than taking the list down.
            DialReason.Unreachable
        }

    /**
     * A failure as the node list words failures.
     *
     * A pin that does not match is refused inside the handshake, and the
     * bridge's message ("the certificate does not match the pin; …") can arrive
     * as a timeout, a transport error or a close, depending on which side gave
     * up first. Reading the message is how a wrong pin is told apart from a
     * Portal that is down — the same test the tunnel makes when it decides
     * whether to fail over.
     */
    internal fun reasonFor(failure: QuicFailure): DialReason =
        when {
            failure is QuicFailure.Untrusted -> DialReason.Untrusted(failure.detail)
            failure is QuicFailure.NotProtected -> DialReason.Unprotected
            failure.detail.contains("pin", ignoreCase = true) -> DialReason.Untrusted(failure.detail)
            else -> DialReason.Unreachable
        }
}
