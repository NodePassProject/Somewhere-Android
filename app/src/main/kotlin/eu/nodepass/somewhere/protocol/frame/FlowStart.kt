// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

/** Whether a flow carries a TCP byte stream or a UDP association. */
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

sealed interface FlowStartReason : DecodeReason {
    data object Missing : FlowStartReason {
        override val detail: String = "no FlowStart byte was received"
    }
}

/**
 * The one byte that opens a flow, per `docs/protocol.md` § FlowStart and Target.
 *
 * ```
 * +-----------------------------------+-------------+
 * |             Bits 7..1             |    Bit 0    |
 * | Reserved: send zero, ignore on RX | TCP=0 UDP=1 |
 * +-----------------------------------+-------------+
 * ```
 *
 * Target follows immediately. This replaces v1's five-byte FlowHeader, whose
 * role, carrier and hop bits and whose flow id all existed to pair split flows
 * and to place them on Mux carriers. v3 has neither, so the flow id is gone
 * from the header entirely: over QUIC it is derived from the stream id
 * ([eu.nodepass.somewhere.protocol.quic.QuicFlowId]), and over TLS a flow is
 * its connection.
 *
 * **Reserved bits are ignored on receipt, not rejected.** The specification
 * says so in as many words, and upstream changed its own decoder to match in
 * the 3.0.1 cycle. A decoder that rejected them would turn a future extension
 * into an interoperability failure.
 */
@JvmInline
value class FlowStart(
    val kind: FlowKind,
) {
    fun encode(): Byte = kind.bit.toByte()

    companion object {
        const val LENGTH: Int = 1

        private const val KIND_MASK = 0x01

        fun decode(byte: Byte): FlowStart = FlowStart(FlowKind.fromBit(byte.toInt() and KIND_MASK))

        fun decode(
            input: ByteArray,
            offset: Int = 0,
        ): DecodeResult<FlowStart> = if (offset >= input.size) invalid(FlowStartReason.Missing) else decode(input[offset]).ok()
    }
}
