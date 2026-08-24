// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

/** How the two directions of a flow are arranged across carriers. */
enum class FlowRole(
    val bits: Int,
) {
    /** Both directions on one carrier. */
    Duplex(0),

    /** The upstream half, carrying the Target. */
    Open(1),

    /** The downstream half, paired by the Portal on (session_id, flow_id). */
    Attach(2),
    ;

    companion object {
        /**
         * `0b11` is reserved and never decodes.
         *
         * That reservation is load-bearing elsewhere: it is what lets `0xff`
         * serve as the TLS Mux mode marker without ever colliding with a real
         * FlowHeader first byte.
         */
        const val RESERVED_BITS: Int = 3

        fun fromBits(bits: Int): FlowRole? = entries.firstOrNull { it.bits == bits }
    }
}

enum class FlowKind(
    val bit: Int,
) {
    Tcp(0),
    Udp(1),
    ;

    companion object {
        fun fromBit(bit: Int): FlowKind = if (bit == 0) Tcp else Udp
    }
}

/** Which transport a direction rides on. */
enum class FlowCarrier(
    val bit: Int,
) {
    TlsTcp(0),
    Quic(1),
    ;

    companion object {
        fun fromBit(bit: Int): FlowCarrier = if (bit == 0) TlsTcp else Quic
    }
}

/** Whether a header was produced here or received from the peer. */
enum class FlowOrigin {
    Client,
    Peer,
}

sealed interface FlowHeaderReason : DecodeReason {
    data class WrongLength(
        val actual: Int,
    ) : FlowHeaderReason {
        override val detail: String = "FlowHeader is $actual bytes; exactly 5 are required"
    }

    data object ReservedRole : FlowHeaderReason {
        override val detail: String = "role bits 0b11 are reserved"
    }

    data object ZeroFlowId : FlowHeaderReason {
        override val detail: String = "flow_id must be non-zero"
    }

    data class HopsOutOfRange(
        val hops: Int,
    ) : FlowHeaderReason {
        override val detail: String = "hops is $hops; the field holds 0..7"
    }

    data class ClientFlowWithHops(
        val hops: Int,
    ) : FlowHeaderReason {
        override val detail: String = "client-originated flows must carry hops 0, got $hops"
    }

    data object DuplexCarriersDiffer : FlowHeaderReason {
        override val detail: String = "DUPLEX requires the same carrier in both directions"
    }

    data object SplitCarriersMatch : FlowHeaderReason {
        override val detail: String = "OPEN and ATTACH require different carriers per direction"
    }
}

/**
 * The 5-byte header that opens every logical flow. NW-P-03.
 *
 * ```
 * flags(u8) || flow_id(u32 big-endian)
 * flags: role 2b | kind 1b | up 1b | down 1b | hops 3b
 *        7..5 hops | 4 down | 3 up | 2 kind | 1..0 role
 * ```
 */
data class FlowHeader(
    val role: FlowRole,
    val kind: FlowKind,
    val up: FlowCarrier,
    val down: FlowCarrier,
    val hops: Int,
    val flowId: UInt,
) {
    fun encode(): ByteArray {
        val flags =
            (role.bits and ROLE_MASK) or
                (kind.bit shl KIND_SHIFT) or
                (up.bit shl UP_SHIFT) or
                (down.bit shl DOWN_SHIFT) or
                ((hops and HOPS_MASK) shl HOPS_SHIFT)
        return byteArrayOf(
            flags.toByte(),
            (flowId shr 24).toByte(),
            (flowId shr 16).toByte(),
            (flowId shr 8).toByte(),
            flowId.toByte(),
        )
    }

    companion object {
        const val LENGTH: Int = 5

        private const val ROLE_MASK = 0b11
        private const val KIND_SHIFT = 2
        private const val UP_SHIFT = 3
        private const val DOWN_SHIFT = 4
        private const val HOPS_SHIFT = 5
        private const val HOPS_MASK = 0b111

        /**
         * Builds a header for a flow this client is opening.
         *
         * `hops` is fixed at 0 rather than defaulted, because the specification
         * makes it a requirement rather than a convention: a client-originated
         * flow with non-zero hops is invalid, and offering the parameter would
         * make it possible to construct one.
         */
        fun forClient(
            role: FlowRole,
            kind: FlowKind,
            up: FlowCarrier,
            down: FlowCarrier,
            flowId: UInt,
        ): DecodeResult<FlowHeader> = validate(FlowHeader(role, kind, up, down, hops = 0, flowId = flowId), FlowOrigin.Client)

        fun decode(
            bytes: ByteArray,
            origin: FlowOrigin = FlowOrigin.Peer,
        ): DecodeResult<FlowHeader> {
            if (bytes.size != LENGTH) return invalid(FlowHeaderReason.WrongLength(bytes.size))

            val flags = bytes[0].toInt() and 0xFF
            val role =
                FlowRole.fromBits(flags and ROLE_MASK)
                    ?: return invalid(FlowHeaderReason.ReservedRole)

            val flowId =
                ((bytes[1].toInt() and 0xFF).toUInt() shl 24) or
                    ((bytes[2].toInt() and 0xFF).toUInt() shl 16) or
                    ((bytes[3].toInt() and 0xFF).toUInt() shl 8) or
                    (bytes[4].toInt() and 0xFF).toUInt()

            return validate(
                FlowHeader(
                    role = role,
                    kind = FlowKind.fromBit((flags shr KIND_SHIFT) and 1),
                    up = FlowCarrier.fromBit((flags shr UP_SHIFT) and 1),
                    down = FlowCarrier.fromBit((flags shr DOWN_SHIFT) and 1),
                    hops = (flags shr HOPS_SHIFT) and HOPS_MASK,
                    flowId = flowId,
                ),
                origin,
            )
        }

        private fun validate(
            header: FlowHeader,
            origin: FlowOrigin,
        ): DecodeResult<FlowHeader> =
            when {
                header.flowId == 0u -> invalid(FlowHeaderReason.ZeroFlowId)
                header.hops !in 0..HOPS_MASK -> invalid(FlowHeaderReason.HopsOutOfRange(header.hops))
                origin == FlowOrigin.Client && header.hops != 0 ->
                    invalid(FlowHeaderReason.ClientFlowWithHops(header.hops))
                header.role == FlowRole.Duplex && header.up != header.down ->
                    invalid(FlowHeaderReason.DuplexCarriersDiffer)
                header.role != FlowRole.Duplex && header.up == header.down ->
                    invalid(FlowHeaderReason.SplitCarriersMatch)
                else -> header.ok()
            }
    }
}
