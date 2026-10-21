// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import java.security.SecureRandom

/**
 * Morph for QUIC's UDP datagrams, per `docs/protocol.md` § Morph, UDP encapsulation.
 *
 * ```
 * +-------------------+-------------------------------------------+
 * | nonce: 12         | ChaCha20-XOR(one QUIC UDP datagram)       |
 * +-------------------+-------------------------------------------+
 * ```
 *
 * Every datagram gets a fresh unpredictable nonce and a cipher started at block
 * zero, so datagrams are independent of each other: loss and reordering, which
 * UDP does all the time, cannot desynchronise anything.
 *
 * **Twelve bytes come out of the path MTU.** The nonce sits outside the QUIC
 * packet, so a QUIC stack that thinks it has 1452 bytes produces 1464 on the
 * wire. [OVERHEAD] is what the QUIC layer must subtract; the bridge does.
 *
 * Masking, not protection: QUIC still authenticates and encrypts everything
 * inside, and a datagram altered in flight fails there rather than here.
 */
class MorphDatagram(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val nonces: (ByteArray) -> Unit = ::secureFill,
) {
    /** Masks one outgoing QUIC datagram. */
    fun seal(
        packet: ByteArray,
        offset: Int = 0,
        length: Int = packet.size - offset,
    ): ByteArray {
        val wire = ByteArray(OVERHEAD + length)
        val nonce = ByteArray(OVERHEAD).also(nonces)
        nonce.copyInto(wire)
        packet.copyInto(wire, OVERHEAD, offset, offset + length)
        ChaCha20(sendKey, nonce).apply(wire, OVERHEAD, length)
        return wire
    }

    /**
     * Unmasks one incoming datagram, or returns null for one too short to be
     * Morph.
     *
     * A packet no longer than the nonce is discarded, as the specification
     * says: there is nothing in it for QUIC, and handing QUIC an empty packet
     * would be inventing one.
     */
    fun open(
        wire: ByteArray,
        offset: Int = 0,
        length: Int = wire.size - offset,
    ): ByteArray? {
        if (length <= OVERHEAD) return null
        val nonce = wire.copyOfRange(offset, offset + OVERHEAD)
        val packet = wire.copyOfRange(offset + OVERHEAD, offset + length)
        ChaCha20(receiveKey, nonce).apply(packet)
        return packet
    }

    companion object {
        /** The nonce prefix, and therefore what Morph costs every datagram. */
        const val OVERHEAD: Int = ChaCha20.NONCE_BYTES

        /** As the client of a connection: send c2s, receive s2c. */
        fun forClient(keys: MorphKeys): MorphDatagram = MorphDatagram(keys.udpClientToServer(), keys.udpServerToClient())

        private val random = SecureRandom()

        private fun secureFill(bytes: ByteArray) = random.nextBytes(bytes)
    }
}
