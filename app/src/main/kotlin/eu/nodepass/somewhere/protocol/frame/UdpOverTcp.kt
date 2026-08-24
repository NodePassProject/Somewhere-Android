// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

sealed interface UotReason : DecodeReason {
    data class PayloadTooLarge(
        val length: Int,
    ) : UotReason {
        override val detail: String = "UoT payload is $length bytes; the maximum is 65535"
    }

    /** The stream ended part-way through a length field. */
    data object TruncatedLength : UotReason {
        override val detail: String = "stream ended inside the 2-byte length prefix"
    }

    /** The length field was complete but the payload it declared was not. */
    data class TruncatedPayload(
        val declared: Int,
        val available: Int,
    ) : UotReason {
        override val detail: String = "declared $declared payload bytes, $available available"
    }
}

/**
 * UDP packets framed onto a TLS/TCP carrier. NW-P-07.
 *
 * ```
 * payload_len(u16 big-endian) || payload
 * ```
 *
 * Consecutive packets sit back to back with no type field. A **zero length is a
 * legal empty packet**, not a terminator — UDP genuinely carries empty
 * datagrams, and treating one as end-of-stream would silently drop it.
 *
 * A clean EOF *before* the next length field closes that half of the flow, which
 * is ordinary shutdown. An EOF *inside* a length field, or before a declared
 * payload completes, is a protocol error — hence two distinct reasons rather than
 * one "truncated": the first means the peer stopped mid-header, the second means
 * it announced more than it sent.
 */
object UdpOverTcp {
    const val LENGTH_PREFIX_SIZE: Int = 2
    const val PACKET_MAX: Int = 0xFFFF

    /** A decoded packet and the total bytes it occupied, prefix included. */
    data class Packet(
        val payload: ByteArray,
        val consumed: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Packet && consumed == other.consumed && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * payload.contentHashCode() + consumed
    }

    /** Outcome of asking for the next packet in a buffer. */
    sealed interface Next {
        data class Ready(
            val packet: Packet,
        ) : Next

        /** The buffer ended cleanly on a packet boundary: this half is closed. */
        data object EndOfStream : Next

        data class Invalid(
            val reason: UotReason,
        ) : Next
    }

    fun encode(payload: ByteArray): DecodeResult<ByteArray> {
        if (payload.size > PACKET_MAX) return invalid(UotReason.PayloadTooLarge(payload.size))
        val out = ByteArray(LENGTH_PREFIX_SIZE + payload.size)
        out[0] = ((payload.size shr 8) and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, LENGTH_PREFIX_SIZE)
        return out.ok()
    }

    /**
     * Reads the packet starting at [offset].
     *
     * Distinguishes "nothing left, cleanly" from "stopped part-way", because
     * only the second is an error. The caller decides what to do with a clean
     * end; the decoder must not conflate the two.
     */
    fun next(
        input: ByteArray,
        offset: Int = 0,
    ): Next {
        val remaining = input.size - offset
        if (remaining == 0) return Next.EndOfStream
        if (remaining < LENGTH_PREFIX_SIZE) return Next.Invalid(UotReason.TruncatedLength)

        val declared =
            ((input[offset].toInt() and 0xFF) shl 8) or (input[offset + 1].toInt() and 0xFF)
        val available = remaining - LENGTH_PREFIX_SIZE
        if (available < declared) {
            return Next.Invalid(UotReason.TruncatedPayload(declared, available))
        }

        val start = offset + LENGTH_PREFIX_SIZE
        return Next.Ready(
            Packet(
                payload = input.copyOfRange(start, start + declared),
                consumed = LENGTH_PREFIX_SIZE + declared,
            ),
        )
    }

    /** Reads every packet in a buffer, stopping at the first problem. */
    fun decodeAll(input: ByteArray): DecodeResult<List<ByteArray>> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (true) {
            when (val next = next(input, offset)) {
                is Next.EndOfStream -> return packets.toList().ok()
                is Next.Invalid -> return invalid(next.reason)
                is Next.Ready -> {
                    packets += next.packet.payload
                    offset += next.packet.consumed
                }
            }
        }
    }
}
