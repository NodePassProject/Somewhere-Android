// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

enum class DatagramType(
    val value: Int,
) {
    Data(0),
    Fragment(1),
    Close(2),
    ;

    companion object {
        /** Type 3 exists in the encoding space but is not a frame; it is rejected. */
        const val INVALID_TYPE: Int = 3

        fun fromBits(bits: Int): DatagramType? = entries.firstOrNull { it.value == bits }
    }
}

sealed interface DatagramReason : DecodeReason {
    data class Truncated(
        val available: Int,
        val required: Int,
    ) : DatagramReason {
        override val detail: String = "datagram is $available bytes; $required are required"
    }

    data object InvalidType : DatagramReason {
        override val detail: String = "frame type 3 is not a valid datagram type"
    }

    data class ReservedBitsSet(
        val firstByte: Int,
    ) : DatagramReason {
        override val detail: String = "first byte 0x%02x sets reserved bits".format(firstByte)
    }

    data object FlowIdZero : DatagramReason {
        override val detail: String = "flow_id must be non-zero"
    }

    data object PacketIdZero : DatagramReason {
        override val detail: String = "packet_id must be non-zero"
    }

    data class FragmentIndexOutOfRange(
        val index: Int,
        val count: Int,
    ) : DatagramReason {
        override val detail: String = "fragment index $index is not below count $count"
    }

    data class FragmentCountOutOfRange(
        val count: Int,
    ) : DatagramReason {
        override val detail: String = "fragment count $count is outside 2..255"
    }

    data class PayloadTooLarge(
        val length: Int,
    ) : DatagramReason {
        override val detail: String = "UDP payload is $length bytes; the maximum is 65535"
    }

    data object MetadataConflict : DatagramReason {
        override val detail: String = "fragment metadata disagrees with the packet already being reassembled"
    }

    data object DuplicateFragmentDiffers : DatagramReason {
        override val detail: String = "a repeated fragment carried different bytes"
    }

    data class ReassembledLengthMismatch(
        val actual: Int,
        val declared: Int,
    ) : DatagramReason {
        override val detail: String = "reassembled $actual bytes, header declared $declared"
    }

    data object NotReady : DatagramReason {
        override val detail: String = "payload arrived before the control flow reported READY"
    }

    data class DatagramTooSmall(
        val maxDatagramSize: Int,
    ) : DatagramReason {
        override val detail: String = "max datagram size $maxDatagramSize leaves no room for a fragment header"
    }
}

/**
 * UDP carried inside QUIC DATAGRAM frames. NW-P-20, NW-P-21.
 *
 * ```
 * DATA      type(u8) || flow_id(u32) || payload        5-byte header
 * CLOSE     type(u8) || flow_id(u32)                   5-byte header
 * FRAGMENT  type(u8) || flow_id(u32) || packet_id(u32)
 *           || index(u8) || count(u8) || total_len(u16) 13-byte header
 * ```
 *
 * The frame type is the **low two bits** of the first byte. Every remaining bit
 * is reserved and must be zero — masking them away instead of rejecting would
 * accept frames a future version may define differently, and silently
 * misinterpret them.
 */
sealed interface DatagramFrame {
    val flowId: UInt

    data class Data(
        override val flowId: UInt,
        val payload: ByteArray,
    ) : DatagramFrame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Data && flowId == other.flowId && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * flowId.hashCode() + payload.contentHashCode()
    }

    data class Close(
        override val flowId: UInt,
    ) : DatagramFrame

    data class Fragment(
        override val flowId: UInt,
        val packetId: UInt,
        val index: Int,
        val count: Int,
        val totalLength: Int,
        val payload: ByteArray,
    ) : DatagramFrame {
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
            is Data -> header(DatagramType.Data, flowId) + payload
            is Close -> header(DatagramType.Close, flowId)
            is Fragment ->
                header(DatagramType.Fragment, flowId) +
                    byteArrayOf(
                        (packetId shr 24).toByte(),
                        (packetId shr 16).toByte(),
                        (packetId shr 8).toByte(),
                        packetId.toByte(),
                        index.toByte(),
                        count.toByte(),
                        ((totalLength shr 8) and 0xFF).toByte(),
                        (totalLength and 0xFF).toByte(),
                    ) + payload
        }

    companion object {
        const val HEADER_LENGTH: Int = 5
        const val FRAGMENT_HEADER_LENGTH: Int = 13
        const val PACKET_MAX: Int = 0xFFFF
        const val FRAGMENT_COUNT_MIN: Int = 2
        const val FRAGMENT_COUNT_MAX: Int = 255

        private const val TYPE_MASK = 0b0000_0011
        private const val RESERVED_MASK = 0b1111_1100

        private fun header(
            type: DatagramType,
            flowId: UInt,
        ): ByteArray =
            byteArrayOf(
                type.value.toByte(),
                (flowId shr 24).toByte(),
                (flowId shr 16).toByte(),
                (flowId shr 8).toByte(),
                flowId.toByte(),
            )

        fun decode(input: ByteArray): DecodeResult<DatagramFrame> {
            if (input.size < HEADER_LENGTH) {
                return invalid(DatagramReason.Truncated(input.size, HEADER_LENGTH))
            }

            val first = input[0].toInt() and 0xFF
            if (first and RESERVED_MASK != 0) return invalid(DatagramReason.ReservedBitsSet(first))

            val bits = first and TYPE_MASK
            val type = DatagramType.fromBits(bits) ?: return invalid(DatagramReason.InvalidType)

            val flowId = readUInt(input, 1)
            if (flowId == 0u) return invalid(DatagramReason.FlowIdZero)

            return when (type) {
                DatagramType.Data -> {
                    val payload = input.copyOfRange(HEADER_LENGTH, input.size)
                    if (payload.size > PACKET_MAX) {
                        invalid(DatagramReason.PayloadTooLarge(payload.size))
                    } else {
                        Data(flowId, payload).ok()
                    }
                }
                DatagramType.Close -> Close(flowId).ok()
                DatagramType.Fragment -> decodeFragment(input, flowId)
            }
        }

        private fun decodeFragment(
            input: ByteArray,
            flowId: UInt,
        ): DecodeResult<DatagramFrame> {
            if (input.size < FRAGMENT_HEADER_LENGTH) {
                return invalid(DatagramReason.Truncated(input.size, FRAGMENT_HEADER_LENGTH))
            }
            val packetId = readUInt(input, 5)
            if (packetId == 0u) return invalid(DatagramReason.PacketIdZero)

            val index = input[9].toInt() and 0xFF
            val count = input[10].toInt() and 0xFF
            val totalLength = ((input[11].toInt() and 0xFF) shl 8) or (input[12].toInt() and 0xFF)

            if (count !in FRAGMENT_COUNT_MIN..FRAGMENT_COUNT_MAX) {
                return invalid(DatagramReason.FragmentCountOutOfRange(count))
            }
            if (index >= count) return invalid(DatagramReason.FragmentIndexOutOfRange(index, count))

            return Fragment(
                flowId = flowId,
                packetId = packetId,
                index = index,
                count = count,
                totalLength = totalLength,
                payload = input.copyOfRange(FRAGMENT_HEADER_LENGTH, input.size),
            ).ok()
        }

        private fun readUInt(
            input: ByteArray,
            at: Int,
        ): UInt =
            ((input[at].toInt() and 0xFF).toUInt() shl 24) or
                ((input[at + 1].toInt() and 0xFF).toUInt() shl 16) or
                ((input[at + 2].toInt() and 0xFF).toUInt() shl 8) or
                (input[at + 3].toInt() and 0xFF).toUInt()

        /**
         * Splits a UDP payload into the frames that will carry it.
         *
         * Returns a single DATA frame whenever the payload fits, and only
         * fragments when it does not — a one-fragment "fragmentation" would cost
         * 8 extra header bytes for nothing.
         */
        fun plan(
            flowId: UInt,
            packetId: UInt,
            payload: ByteArray,
            maxDatagramSize: Int,
        ): DecodeResult<List<DatagramFrame>> {
            if (payload.size > PACKET_MAX) return invalid(DatagramReason.PayloadTooLarge(payload.size))
            if (flowId == 0u) return invalid(DatagramReason.FlowIdZero)

            if (payload.size + HEADER_LENGTH <= maxDatagramSize) {
                return listOf<DatagramFrame>(Data(flowId, payload)).ok()
            }

            if (packetId == 0u) return invalid(DatagramReason.PacketIdZero)
            val fragmentPayloadMax = maxDatagramSize - FRAGMENT_HEADER_LENGTH
            if (fragmentPayloadMax <= 0) return invalid(DatagramReason.DatagramTooSmall(maxDatagramSize))

            val count = (payload.size + fragmentPayloadMax - 1) / fragmentPayloadMax
            if (count !in FRAGMENT_COUNT_MIN..FRAGMENT_COUNT_MAX) {
                return invalid(DatagramReason.FragmentCountOutOfRange(count))
            }

            return (0 until count)
                .map { index ->
                    val start = index * fragmentPayloadMax
                    val end = minOf(start + fragmentPayloadMax, payload.size)
                    Fragment(
                        flowId = flowId,
                        packetId = packetId,
                        index = index,
                        count = count,
                        totalLength = payload.size,
                        payload = payload.copyOfRange(start, end),
                    )
                }.ok()
        }
    }
}
