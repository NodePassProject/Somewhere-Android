// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.protocol.frame.SetupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NW-P-06 at the presentation boundary.
 *
 * `StringResourceTest` already proves the seven explanations exist and differ in
 * every locale. This proves the other half: that each outcome actually reaches
 * one of them, and that the wire name a user pastes into an issue is the wire
 * name the specification uses. Between the two, a rejection cannot silently
 * collapse into its neighbour.
 */
class SetupResultTextTest {
    @Test
    fun everyOutcomeHasItsOwnWireName() {
        val names = SetupResult.entries.map { it.identifier }
        assertEquals("no two outcomes may share a name", names.size, names.toSet().size)
    }

    @Test
    fun theWireNameIsTheSpecificationsName() {
        // Transcribed from `docs/protocol.md` section 6, not derived from the
        // Kotlin enum names — deriving would make a rename in the client
        // silently rename the thing users search for.
        assertEquals("READY", SetupResult.Ready.identifier)
        assertEquals("INVALID_REQUEST", SetupResult.InvalidRequest.identifier)
        assertEquals("METADATA_CONFLICT", SetupResult.MetadataConflict.identifier)
        assertEquals("PAIR_TIMEOUT", SetupResult.PairTimeout.identifier)
        assertEquals("FLOW_LIMIT", SetupResult.FlowLimit.identifier)
        assertEquals("DIAL_FAILED", SetupResult.DialFailed.identifier)
        assertEquals("SESSION_REPLACED", SetupResult.SessionReplaced.identifier)
        assertEquals("INTERNAL_ERROR", SetupResult.InternalError.identifier)
    }

    @Test
    fun aWireNameIsScreamingSnakeCaseAndNothingElse() {
        // A user pastes this into an issue and expects it to match the spec, the
        // source and someone else's log. Any stray character breaks all three.
        SetupResult.entries.forEach { result ->
            assertTrue(
                "${result.identifier} is not a bare wire name",
                result.identifier.matches(Regex("[A-Z]+(_[A-Z]+)*")),
            )
        }
    }

    @Test
    fun theSevenRejectionsReachSevenDifferentExplanations() {
        val rejections = SetupResult.entries.filter { it.isRejection }
        assertEquals(7, rejections.size)
        assertEquals(
            "two rejections point at the same message, which is the collapse NW-P-06 forbids",
            7,
            rejections.map { it.explanation }.toSet().size,
        )
    }

    @Test
    fun severityDistinguishesBusyFromBroken() {
        // A Portal at its flow limit is busy, not broken. Drawing it in the same
        // red as an unreachable target teaches the reader to ignore both.
        assertEquals(LogSeverity.Good, SetupResult.Ready.severity)
        assertEquals(LogSeverity.Warn, SetupResult.FlowLimit.severity)
        assertEquals(LogSeverity.Critical, SetupResult.DialFailed.severity)
    }
}
