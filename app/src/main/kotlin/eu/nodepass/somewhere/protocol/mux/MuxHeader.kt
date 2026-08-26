// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

enum class MuxKind(
    val byte: Int,
) {
    Stream(0x01),
    Window(0x02),

    /**
     * Recognised, never supported.
     *
     * Upstream's runtime closes the carrier on receipt rather than ignoring the
     * frame, so this decodes to a value the carrier can act on rather than being
     * rejected outright — the difference matters, because "close the carrier" is
     * a specific behaviour and not the same as "bad frame".
     */
    Datagram(0x03),
    ;

    companion object {
        fun fromByte(value: Int): MuxKind? = entries.firstOrNull { it.byte == value }
    }
}

sealed interface MuxReason : DecodeReason {
    data class Truncated(
        val available: Int,
    ) : MuxReason {
        override val detail: String = "Mux header needs 8 bytes, $available available"
    }

    data class UnknownKind(
        val kind: Int,
    ) : MuxReason {
        override val detail: String = "unknown Mux kind 0x%02x".format(kind)
    }

    data object DatagramUnsupported : MuxReason {
        override val detail: String = "DATAGRAM frames are not supported; the carrier must close"
    }

    data class ReservedFlagBits(
        val flags: Int,
    ) : MuxReason {
        override val detail: String = "flags 0x%02x sets bits outside SYN, FIN and RST".format(flags)
    }

    data object ResetNotAlone : MuxReason {
        override val detail: String = "RST must be the only flag set"
    }

    data class ResetWithValue(
        val value: Int,
    ) : MuxReason {
        override val detail: String = "RST must carry value 0, got $value"
    }

    data object StreamFlowIdZero : MuxReason {
        override val detail: String = "STREAM requires a non-zero flow_id"
    }

    data class StreamPayloadTooLarge(
        val value: Int,
    ) : MuxReason {
        override val detail: String = "STREAM payload is $value bytes; the maximum is 32768"
    }

    data class WindowWithFlags(
        val flags: Int,
    ) : MuxReason {
        override val detail: String = "WINDOW must carry flags 0, got 0x%02x".format(flags)
    }

    data object WindowZeroCredit : MuxReason {
        override val detail: String = "WINDOW must return a non-zero credit"
    }

    data class CreditExceedsWindow(
        val credit: Long,
        val window: Int,
    ) : MuxReason {
        override val detail: String = "credit $credit exceeds the configured window $window"
    }

    data class UnknownFlow(
        val flowId: UInt,
    ) : MuxReason {
        override val detail: String = "STREAM data for flow $flowId, which is not open"
    }
}

/**
 * The 8-byte header on every Mux frame. NW-P-13, NW-P-14.
 *
 * ```
 * kind(u8) || flags(u8) || value(u16) || flow_id(u32 big-endian)
 * ```
 *
 * The `value` field means different things per kind: for STREAM it is the
 * payload length that follows, for WINDOW it is the credit being returned.
 */
data class MuxHeader(
    val kind: MuxKind,
    val flags: Int,
    val value: Int,
    val flowId: UInt,
) {
    val isSyn: Boolean get() = flags and FLAG_SYN != 0
    val isFin: Boolean get() = flags and FLAG_FIN != 0
    val isReset: Boolean get() = flags and FLAG_RST != 0

    /** WINDOW with flow 0 replenishes the connection rather than one stream. */
    val isConnectionLevel: Boolean get() = kind == MuxKind.Window && flowId == 0u

    fun encode(): ByteArray =
        byteArrayOf(
            kind.byte.toByte(),
            flags.toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
            (flowId shr 24).toByte(),
            (flowId shr 16).toByte(),
            (flowId shr 8).toByte(),
            flowId.toByte(),
        )

    companion object {
        const val LENGTH: Int = 8

        /** Written once after the AuthFrame to switch a TLS carrier into Mux mode. */
        const val MODE_MARKER: Byte = 0xff.toByte()

        const val FLAG_SYN: Int = 0x01
        const val FLAG_FIN: Int = 0x02
        const val FLAG_RST: Int = 0x04
        const val FLAG_MASK: Int = FLAG_SYN or FLAG_FIN or FLAG_RST

        const val MAX_STREAM_PAYLOAD: Int = 32768

        /**
         * The most credit one WINDOW frame can return.
         *
         * Not a policy: `value` is a u16, so this is what the field holds. It
         * matters because the receive windows are 512 KiB — eight times this —
         * so credit for a window's worth of consumed bytes does not fit in one
         * frame and has to be returned in several. Encoding it into one would
         * truncate silently, and the peer would stall a long way from the end
         * of a transfer that looked healthy.
         */
        const val MAX_WINDOW_CREDIT: Int = 0xFFFF
        const val DEFAULT_STREAM_CREDIT: Int = 524288
        const val DEFAULT_CONNECTION_CREDIT: Int = 524288
        const val MAX_ACTIVE_STREAMS: Int = 256
        const val OUTBOUND_QUEUE_SLOTS: Int = 512

        /**
         * A new Shard opens once every live one in the set holds this many
         * active flows (`docs/protocol.md` section 3).
         *
         * Runtime placement, not a wire field: nothing on the connection says
         * which Shard a flow landed on. It is here beside the other bounds
         * because it is pinned by the same fixture and moved once already —
         * v1.8.1 changed it, and nothing was reading it at the time.
         */
        const val SHARD_FLOW_THRESHOLD: Int = 4

        /** A Shard with no flows at all closes after this long. */
        const val SHARD_IDLE_CLOSE_SECONDS: Int = 30

        fun decode(
            input: ByteArray,
            offset: Int = 0,
        ): DecodeResult<MuxHeader> {
            if (input.size - offset < LENGTH) return invalid(MuxReason.Truncated(input.size - offset))

            val kindByte = input[offset].toInt() and 0xFF
            val kind = MuxKind.fromByte(kindByte) ?: return invalid(MuxReason.UnknownKind(kindByte))
            val flags = input[offset + 1].toInt() and 0xFF
            val value = ((input[offset + 2].toInt() and 0xFF) shl 8) or (input[offset + 3].toInt() and 0xFF)
            val flowId =
                ((input[offset + 4].toInt() and 0xFF).toUInt() shl 24) or
                    ((input[offset + 5].toInt() and 0xFF).toUInt() shl 16) or
                    ((input[offset + 6].toInt() and 0xFF).toUInt() shl 8) or
                    (input[offset + 7].toInt() and 0xFF).toUInt()

            val header = MuxHeader(kind, flags, value, flowId)
            return validate(header) ?: header.ok()
        }

        /** Frame-level rules — everything decidable from the header alone. */
        private fun validate(header: MuxHeader): DecodeResult<Nothing>? {
            if (header.kind == MuxKind.Datagram) return invalid(MuxReason.DatagramUnsupported)

            if (header.flags and FLAG_MASK.inv() and 0xFF != 0) {
                return invalid(MuxReason.ReservedFlagBits(header.flags))
            }

            return when (header.kind) {
                MuxKind.Stream -> validateStream(header)
                MuxKind.Window -> validateWindow(header)
                MuxKind.Datagram -> null // handled above
            }
        }

        private fun validateStream(header: MuxHeader): DecodeResult<Nothing>? =
            when {
                header.isReset && header.flags != FLAG_RST -> invalid(MuxReason.ResetNotAlone)
                header.isReset && header.value != 0 -> invalid(MuxReason.ResetWithValue(header.value))
                header.flowId == 0u -> invalid(MuxReason.StreamFlowIdZero)
                header.value > MAX_STREAM_PAYLOAD -> invalid(MuxReason.StreamPayloadTooLarge(header.value))
                else -> null
            }

        private fun validateWindow(header: MuxHeader): DecodeResult<Nothing>? =
            when {
                header.flags != 0 -> invalid(MuxReason.WindowWithFlags(header.flags))
                header.value == 0 -> invalid(MuxReason.WindowZeroCredit)
                else -> null
            }
    }
}

/**
 * The rules a Mux frame can only be judged against with carrier state.
 *
 * Kept separate from [MuxHeader.decode] and expressed as pure functions over
 * explicitly supplied state, so the rules are testable now while the state that
 * feeds them belongs to the carrier implementation at L2.
 */
object MuxCarrierRules {
    /**
     * Whether returned credit stays within the configured receive window.
     *
     * Credit beyond the window closes the carrier: a peer that inflates credit
     * is either broken or trying to make this side over-commit memory.
     */
    fun checkCredit(
        outstanding: Long,
        returned: Int,
        window: Int,
    ): DecodeResult<Long> {
        val total = outstanding + returned
        return if (total > window) {
            invalid(MuxReason.CreditExceedsWindow(total, window))
        } else {
            total.ok()
        }
    }

    /**
     * Whether a STREAM frame names a flow that is open.
     *
     * A SYN opens a flow, so it is the one case where an unknown id is expected.
     * Data for an id that was never opened is a carrier error rather than
     * something to ignore — ignoring it would desynchronise the stream, since
     * the payload still has to be consumed.
     */
    fun checkFlowKnown(
        header: MuxHeader,
        openFlows: Set<UInt>,
    ): DecodeResult<Unit> =
        when {
            header.kind != MuxKind.Stream -> Unit.ok()
            header.isSyn -> Unit.ok()
            header.flowId in openFlows -> Unit.ok()
            else -> invalid(MuxReason.UnknownFlow(header.flowId))
        }
}
