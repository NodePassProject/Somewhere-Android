// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import androidx.annotation.StringRes
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.protocol.frame.SetupResult

/*
 * How each of the eight outcomes reaches the user.
 *
 * NW-P-06 requires all seven rejections to be distinguishable, so this is an
 * exhaustive `when` over the enum in both directions: adding an outcome to the
 * protocol stops compiling here until someone decides what it says and how loud
 * it is. A map with a default would have silently absorbed it.
 */

/**
 * The wire name, never translated.
 *
 * Spelled out rather than derived from the enum name by regex: a user pasting
 * `DIAL_FAILED` into an issue has to match the specification and the source
 * exactly, and a clever transformation is one rename away from producing
 * `DIALFAILED` in a locale nobody tests.
 */
val SetupResult.identifier: String
    get() =
        when (this) {
            SetupResult.Ready -> "READY"
            SetupResult.InvalidRequest -> "INVALID_REQUEST"
            SetupResult.MetadataConflict -> "METADATA_CONFLICT"
            SetupResult.PairTimeout -> "PAIR_TIMEOUT"
            SetupResult.FlowLimit -> "FLOW_LIMIT"
            SetupResult.DialFailed -> "DIAL_FAILED"
            SetupResult.SessionReplaced -> "SESSION_REPLACED"
            SetupResult.InternalError -> "INTERNAL_ERROR"
        }

/** The sentence beneath the identifier. This one translates; the identifier does not. */
@get:StringRes
val SetupResult.explanation: Int
    get() =
        when (this) {
            SetupResult.Ready -> R.string.setup_ready
            SetupResult.InvalidRequest -> R.string.setup_invalid_request
            SetupResult.MetadataConflict -> R.string.setup_metadata_conflict
            SetupResult.PairTimeout -> R.string.setup_pair_timeout
            SetupResult.FlowLimit -> R.string.setup_flow_limit
            SetupResult.DialFailed -> R.string.setup_dial_failed
            SetupResult.SessionReplaced -> R.string.setup_session_replaced
            SetupResult.InternalError -> R.string.setup_internal_error
        }

/**
 * How loudly a log line is drawn.
 *
 * Severity is not the same as rejection: a Portal at its flow limit is busy, not
 * broken, and drawing it in the same red as an unreachable target would teach
 * the reader to ignore both.
 */
enum class LogSeverity {
    Good,
    Warn,
    Critical,
    Neutral,
}

val SetupResult.severity: LogSeverity
    get() =
        when (this) {
            SetupResult.Ready -> LogSeverity.Good
            SetupResult.DialFailed -> LogSeverity.Critical
            SetupResult.FlowLimit -> LogSeverity.Warn
            SetupResult.SessionReplaced -> LogSeverity.Warn
            SetupResult.InvalidRequest -> LogSeverity.Neutral
            SetupResult.MetadataConflict -> LogSeverity.Neutral
            SetupResult.PairTimeout -> LogSeverity.Neutral
            SetupResult.InternalError -> LogSeverity.Neutral
        }
