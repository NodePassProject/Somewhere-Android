// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

sealed interface SetupResultReason : DecodeReason {
    data class OutOfRange(
        val value: Int,
    ) : SetupResultReason {
        override val detail: String = "setup byte $value is outside 0..${SetupResult.MAX_VALUE} and is a protocol error"
    }

    data object Missing : SetupResultReason {
        override val detail: String = "no setup byte was received"
    }
}

/**
 * The single byte a Portal answers a flow with before payload relay starts,
 * per `docs/protocol.md` § Setup results.
 *
 * **Each rejection is a distinct value on purpose.** The specification gives
 * each one its own meaning, and a client that collapses them into "connection
 * failed" makes the difference between "this Portal is at its flow limit" and
 * "the target could not be reached" invisible — which is precisely the
 * information someone needs to fix their own problem.
 *
 * **The numbering changed in v3, and the set shrank.** v1 had eight values;
 * `METADATA_CONFLICT`, `PAIR_TIMEOUT` and `SESSION_REPLACED` described split
 * flows and sessions, which no longer exist, and the survivors were renumbered.
 * A v1 byte 4 meant `FLOW_LIMIT`; a v3 byte 4 means `INTERNAL_ERROR`.
 */
enum class SetupResult(
    val byte: Int,
) {
    /** The flow is established; payload relay begins. */
    Ready(0),

    /** The request itself was malformed — a bad target, or a request that does not parse. */
    InvalidRequest(1),

    /** The Portal is at a flow or resource limit and refused promptly. */
    FlowLimit(2),

    /** The target could not be reached, or the Portal's Egress is offline. */
    DialFailed(3),

    /** The Portal failed for a reason it does not attribute further. */
    InternalError(4),
    ;

    val isReady: Boolean get() = this == Ready

    /** True when the flow was refused, which is every value except [Ready]. */
    val isRejection: Boolean get() = this != Ready

    companion object {
        const val LENGTH: Int = 1
        const val MAX_VALUE: Int = 4

        fun decode(byte: Byte): DecodeResult<SetupResult> {
            val value = byte.toInt() and 0xFF
            return entries.firstOrNull { it.byte == value }?.ok()
                ?: invalid(SetupResultReason.OutOfRange(value))
        }

        fun decode(
            input: ByteArray,
            offset: Int = 0,
        ): DecodeResult<SetupResult> = if (offset >= input.size) invalid(SetupResultReason.Missing) else decode(input[offset])
    }
}
