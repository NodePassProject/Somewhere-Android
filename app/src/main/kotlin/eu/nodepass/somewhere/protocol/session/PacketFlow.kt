// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

/**
 * A flow whose unit is a packet rather than a byte.
 *
 * ## Why this exists rather than reusing `write`
 *
 * UDP over a TLS carrier is length-prefixed inside a byte stream — `UdpOverTcp`
 * frames each packet and the caller writes the framing. UDP over QUIC is not:
 * section 9 puts each packet in its own DATAGRAM, unreliable and unordered,
 * which is what UDP is. A packet boundary is real there, and expressing it as
 * "some bytes" would mean framing it twice: once for a stream that is not being
 * used, and once for the datagram that is.
 *
 * So a caller that has a packet asks for a packet flow, and only falls back to
 * framing when it does not get one. The alternative — teaching every caller
 * which carrier it is on — is the coupling the [Flow] interface exists to
 * prevent.
 */
interface PacketFlow : Flow {
    /**
     * Sends one packet, fragmenting it if the path cannot carry it whole.
     *
     * Lossy by design. The transport underneath neither retransmits nor orders,
     * which is what UDP asks for; a packet that cannot be sent is dropped and
     * the caller is told nothing, exactly as a UDP socket would.
     */
    fun sendPacket(payload: ByteArray)

    /**
     * Waits up to [timeoutMillis] for one packet.
     *
     * @return the packet, or null when none arrived. A zero-length packet is a
     *   real packet — section 9 says an empty DATA is valid — so an empty array
     *   cannot mean "nothing".
     */
    fun receivePacket(timeoutMillis: Long): ByteArray?
}
