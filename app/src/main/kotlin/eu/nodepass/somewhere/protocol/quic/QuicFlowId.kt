// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

sealed interface QuicFlowIdReason : DecodeReason {
    data class NotBidirectional(
        val streamId: Long,
    ) : QuicFlowIdReason {
        override val detail: String = "stream $streamId is unidirectional; a business flow needs a bidirectional stream"
    }

    data class WrongInitiator(
        val streamId: Long,
    ) : QuicFlowIdReason {
        override val detail: String = "stream $streamId was not opened by this client"
    }

    data class Exhausted(
        val streamId: Long,
    ) : QuicFlowIdReason {
        override val detail: String =
            "stream $streamId derives a flow id beyond 2^30 - 1; this connection must drain and be replaced"
    }
}

/**
 * The UDP flow id a QUIC stream derives, per `docs/protocol.md` § UDP flows.
 *
 * ```
 * flow_id = (quic_stream_id >> 2) + 1
 * ```
 *
 * **This replaces allocation.** v1 had the client choose an id and carry it in
 * the FlowHeader, scoped to a session, with a released-id set to manage. v3
 * derives the id from the control stream's own number, so both ends compute
 * the same value without sending it and there is nothing to allocate, release
 * or collide. A Portal derives its own id independently for its Egress side.
 *
 * The low two bits of a stream id are its type. They are checked before the
 * shift rather than shifted away: a unidirectional or server-initiated stream
 * derives a perfectly plausible number, and the rule is that it must not.
 */
object QuicFlowId {
    /** 30 bits, because the datagram base word has 30 bits for it. */
    const val MAX: UInt = 0x3FFF_FFFFu

    /** The last client bidirectional stream id that derives a usable flow id. */
    const val LAST_CLIENT_STREAM: Long = 4_294_967_288L

    private const val SERVER_INITIATED = 0x1L
    private const val UNIDIRECTIONAL = 0x2L

    /** The flow id a client-initiated bidirectional stream derives. */
    fun forClientStream(streamId: Long): DecodeResult<UInt> {
        require(streamId >= 0) { "stream ids are non-negative, got $streamId" }
        if (streamId and UNIDIRECTIONAL != 0L) return invalid(QuicFlowIdReason.NotBidirectional(streamId))
        if (streamId and SERVER_INITIATED != 0L) return invalid(QuicFlowIdReason.WrongInitiator(streamId))
        // Computed wide, then range-checked. Never truncated: a wrapped id
        // would collide with a live flow's.
        val derived = (streamId ushr 2) + 1
        if (derived > MAX.toLong()) return invalid(QuicFlowIdReason.Exhausted(streamId))
        return derived.toUInt().ok()
    }
}
