// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

/** Why a datagram was refused. */
sealed interface QuicDatagramReason : DecodeReason {
    data object Truncated : QuicDatagramReason {
        override val detail: String = "the datagram is shorter than its own header"
    }

    data object ReservedType : QuicDatagramReason {
        override val detail: String = "type 3 does not exist"
    }

    data object ReservedBits : QuicDatagramReason {
        override val detail: String = "the six reserved bits of the flags byte must be zero"
    }

    data object ZeroFlowId : QuicDatagramReason {
        override val detail: String = "flow_id is nonzero"
    }

    data object ZeroPacketId : QuicDatagramReason {
        override val detail: String = "packet_id is nonzero"
    }

    data object CloseCarriesPayload : QuicDatagramReason {
        override val detail: String = "a CLOSE datagram is exactly five bytes"
    }

    data class FragmentCount(
        val count: Int,
    ) : QuicDatagramReason {
        override val detail: String = "frag_count is 2..255, not $count"
    }

    data class FragmentIndex(
        val index: Int,
        val count: Int,
    ) : QuicDatagramReason {
        override val detail: String = "frag_ix $index is not below frag_count $count"
    }

    data object TotalLength : QuicDatagramReason {
        override val detail: String = "total_len is nonzero and at most 65535"
    }

    data object PayloadEmpty : QuicDatagramReason {
        override val detail: String = "a fragment carries at least one byte"
    }

    data class DoesNotFit(
        val maxDatagram: Int,
    ) : QuicDatagramReason {
        override val detail: String = "a datagram of $maxDatagram bytes cannot carry a fragment header and a byte"
    }
}

/**
 * One UDP packet inside one QUIC DATAGRAM. `docs/protocol.md` section 9.
 *
 * Every DATAGRAM carries exactly one frame, and the frame's type is the low two
 * bits of the first byte. DATA needs no length field because the DATAGRAM
 * boundary supplies it — which is the whole reason this framing is shorter than
 * UDP-over-stream's.
 */
sealed interface QuicDatagram {
    val flowId: UInt

    /** Payload for this flow. Zero length is a valid packet, not an absence. */
    data class Data(
        override val flowId: UInt,
        val payload: ByteArray,
    ) : QuicDatagram {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Data && flowId == other.flowId && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * flowId.hashCode() + payload.contentHashCode()
    }

    /** The route for this flow is gone. Five bytes, never more. */
    data class Close(
        override val flowId: UInt,
    ) : QuicDatagram

    /** One piece of a packet too large for a single DATAGRAM. */
    data class Fragment(
        override val flowId: UInt,
        val packetId: UInt,
        val index: Int,
        val count: Int,
        val totalLength: Int,
        val payload: ByteArray,
    ) : QuicDatagram {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is Fragment &&
                        flowId == other.flowId &&
                        packetId == other.packetId &&
                        index == other.index &&
                        count == other.count &&
                        totalLength == other.totalLength &&
                        payload.contentEquals(other.payload)
                )

        override fun hashCode(): Int {
            var result = flowId.hashCode()
            result = 31 * result + packetId.hashCode()
            result = 31 * result + index
            result = 31 * result + count
            result = 31 * result + totalLength
            return 31 * result + payload.contentHashCode()
        }
    }

    fun encode(): ByteArray =
        when (this) {
            is Data -> byteArrayOf(TYPE_DATA.toByte()) + flowId.toBytes() + payload
            is Close -> byteArrayOf(TYPE_CLOSE.toByte()) + flowId.toBytes()
            is Fragment ->
                byteArrayOf(TYPE_FRAGMENT.toByte()) +
                    flowId.toBytes() +
                    packetId.toBytes() +
                    byteArrayOf(index.toByte(), count.toByte()) +
                    byteArrayOf((totalLength shr 8).toByte(), totalLength.toByte()) +
                    payload
        }

    companion object {
        const val TYPE_DATA = 0
        const val TYPE_FRAGMENT = 1
        const val TYPE_CLOSE = 2

        /** flags(1) + flow_id(4). */
        const val COMMON_HEADER_SIZE = 5

        /** flags(1) + flow_id(4) + packet_id(4) + ix(1) + count(1) + total_len(2). */
        const val FRAGMENT_HEADER_SIZE = 13

        const val MIN_FRAGMENT_COUNT = 2
        const val MAX_FRAGMENT_COUNT = 255

        /** `total_len` is a u16, so a packet larger than this cannot be described. */
        const val MAX_PACKET_LENGTH = 65_535

        fun decode(bytes: ByteArray): DecodeResult<QuicDatagram> {
            if (bytes.size < COMMON_HEADER_SIZE) return invalid(QuicDatagramReason.Truncated)
            val flags = bytes[0].toInt() and 0xFF
            if (flags and 0xFC != 0) return invalid(QuicDatagramReason.ReservedBits)

            val flowId = bytes.uint(1)
            if (flowId == 0u) return invalid(QuicDatagramReason.ZeroFlowId)

            return when (flags and 0x03) {
                TYPE_DATA -> Data(flowId, bytes.copyOfRange(COMMON_HEADER_SIZE, bytes.size)).ok()

                TYPE_CLOSE ->
                    if (bytes.size != COMMON_HEADER_SIZE) {
                        invalid(QuicDatagramReason.CloseCarriesPayload)
                    } else {
                        Close(flowId).ok()
                    }

                TYPE_FRAGMENT -> decodeFragment(bytes, flowId)

                else -> invalid(QuicDatagramReason.ReservedType)
            }
        }

        private fun decodeFragment(
            bytes: ByteArray,
            flowId: UInt,
        ): DecodeResult<QuicDatagram> {
            if (bytes.size <= FRAGMENT_HEADER_SIZE) return invalid(QuicDatagramReason.PayloadEmpty)
            val packetId = bytes.uint(5)
            if (packetId == 0u) return invalid(QuicDatagramReason.ZeroPacketId)

            val index = bytes[9].toInt() and 0xFF
            val count = bytes[10].toInt() and 0xFF
            if (count < MIN_FRAGMENT_COUNT) return invalid(QuicDatagramReason.FragmentCount(count))
            if (index >= count) return invalid(QuicDatagramReason.FragmentIndex(index, count))

            val totalLength = ((bytes[11].toInt() and 0xFF) shl 8) or (bytes[12].toInt() and 0xFF)
            if (totalLength == 0) return invalid(QuicDatagramReason.TotalLength)

            return Fragment(
                flowId = flowId,
                packetId = packetId,
                index = index,
                count = count,
                totalLength = totalLength,
                payload = bytes.copyOfRange(FRAGMENT_HEADER_SIZE, bytes.size),
            ).ok()
        }

        /**
         * How many fragments a packet of [length] needs at [maxDatagram].
         *
         * `fragmentPayloadMax = maxDatagram - 13`, and the count must land in
         * `2..255`. A packet that fits whole is one DATA frame and is never
         * fragmented — the specification says so, and a client that fragmented
         * anyway would pay thirteen bytes and a reassembly slot for nothing.
         */
        fun plan(
            length: Int,
            maxDatagram: Int,
        ): DecodeResult<Int> {
            // Zero is a valid packet and not an absence — `docs/protocol.md`
            // section 9 says so of DATA explicitly, and the fixture has a case
            // for it. The nonzero rule belongs to `total_len`, which only a
            // *fragment* carries; applying it here refused an empty packet that
            // the specification accepts, and the vectors caught it.
            if (length < 0 || length > MAX_PACKET_LENGTH) return invalid(QuicDatagramReason.TotalLength)
            if (COMMON_HEADER_SIZE + length <= maxDatagram) return 1.ok()

            val perFragment = maxDatagram - FRAGMENT_HEADER_SIZE
            if (perFragment <= 0) return invalid(QuicDatagramReason.DoesNotFit(maxDatagram))
            val count = (length + perFragment - 1) / perFragment
            if (count > MAX_FRAGMENT_COUNT) return invalid(QuicDatagramReason.FragmentCount(count))
            // A packet that did not fit whole always needs at least two pieces,
            // so a count of one here would mean the arithmetic above disagreed
            // with the test above it.
            if (count < MIN_FRAGMENT_COUNT) return invalid(QuicDatagramReason.FragmentCount(count))
            return count.ok()
        }

        /**
         * Splits [payload] into the frames that carry it, DATA or FRAGMENT.
         *
         * One place, so that the decision "does this fit" and the arithmetic
         * that follows from it cannot be made differently in two callers.
         */
        fun frames(
            flowId: UInt,
            packetId: UInt,
            payload: ByteArray,
            maxDatagram: Int,
        ): DecodeResult<List<QuicDatagram>> {
            if (flowId == 0u) return invalid(QuicDatagramReason.ZeroFlowId)
            val count =
                when (val planned = plan(payload.size, maxDatagram)) {
                    is DecodeResult.Ok -> planned.value
                    is DecodeResult.Invalid -> return planned
                }
            if (count == 1) return listOf<QuicDatagram>(Data(flowId, payload)).ok()
            if (packetId == 0u) return invalid(QuicDatagramReason.ZeroPacketId)

            val perFragment = maxDatagram - FRAGMENT_HEADER_SIZE
            val pieces =
                (0 until count).map { index ->
                    val from = index * perFragment
                    val to = minOf(from + perFragment, payload.size)
                    Fragment(
                        flowId = flowId,
                        packetId = packetId,
                        index = index,
                        count = count,
                        totalLength = payload.size,
                        payload = payload.copyOfRange(from, to),
                    )
                }
            return pieces.ok()
        }

        private fun ByteArray.uint(offset: Int): UInt =
            ((this[offset].toUInt() and 0xFFu) shl 24) or
                ((this[offset + 1].toUInt() and 0xFFu) shl 16) or
                ((this[offset + 2].toUInt() and 0xFFu) shl 8) or
                (this[offset + 3].toUInt() and 0xFFu)

        private fun UInt.toBytes(): ByteArray =
            byteArrayOf(
                (this shr 24).toByte(),
                (this shr 16).toByte(),
                (this shr 8).toByte(),
                this.toByte(),
            )
    }
}
