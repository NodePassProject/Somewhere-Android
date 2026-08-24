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
        override val detail: String = "setup byte $value is outside 0..7 and is a protocol error"
    }

    data object Missing : SetupResultReason {
        override val detail: String = "no setup byte was received"
    }
}

/**
 * The single byte a Portal returns on the logical downlink before payload relay
 * starts. NW-P-06, `docs/protocol.md` section 6.
 *
 * **Each rejection is a distinct type on purpose.** The specification requires
 * all seven to reach the user as different messages, and a client that collapses
 * them into "connection failed" makes the difference between "this Portal is at
 * its flow limit" and "your session was replaced elsewhere" invisible — which is
 * precisely the information someone needs to fix their own problem. Modelling
 * them as one error carrying a number would make that collapse the path of least
 * resistance; modelling them as an enum with per-value meaning makes the
 * distinction survive refactoring.
 *
 * For a split flow only the downlink half receives this byte, and the result is
 * authoritative for the whole logical flow.
 */
enum class SetupResult(
    val byte: Int,
) {
    /** The flow is established; payload relay begins. */
    Ready(0),

    /** The request itself was malformed — a bad target, or a header that does not parse. */
    InvalidRequest(1),

    /** The two halves of a split flow disagreed on kind, carrier or hops. */
    MetadataConflict(2),

    /** The other half of a split flow never arrived within the pairing deadline. */
    PairTimeout(3),

    /** The Portal is at its configured flow limit. */
    FlowLimit(4),

    /** The Portal could not reach the target. */
    DialFailed(5),

    /** This session id was taken over by another connection. */
    SessionReplaced(6),

    /** The Portal failed for a reason it does not attribute further. */
    InternalError(7),
    ;

    val isReady: Boolean get() = this == Ready

    /** True when the flow was refused, which is every value except [Ready]. */
    val isRejection: Boolean get() = this != Ready

    companion object {
        const val LENGTH: Int = 1
        const val MAX_VALUE: Int = 7

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
