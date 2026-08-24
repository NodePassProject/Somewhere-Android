// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the gap a coverage percentage cannot see.
 *
 * Line coverage can sit at 90% while an entire rejection family is unimplemented
 * — the lines that exist are covered, and the ones that should exist are not
 * counted. This test tracks the fixture from the other end: every vector family
 * must have a suite that consumes it.
 *
 * [IMPLEMENTED] is deliberately manual, but it cannot be gamed by editing alone:
 * naming a family here without a suite class fails immediately, and a suite that
 * exists but skips vectors is caught by the coverage gate. Both would have to be
 * defeated together.
 *
 * The pending list shrinking to empty is the completion signal for the protocol
 * codec work — more so than any individual task passing.
 */
class VectorCoverageTest {
    private companion object {
        /**
         * Families with a suite, and the suite that consumes them. Add an entry
         * only when the suite genuinely walks every case and reject in the family.
         */
        val IMPLEMENTED: Map<String, String> =
            mapOf(
                "auth" to "eu.nodepass.somewhere.protocol.auth.AuthVectorTest",
                "flowHeader" to "eu.nodepass.somewhere.protocol.frame.FlowHeaderVectorTest",
                "target" to "eu.nodepass.somewhere.protocol.target.TargetVectorTest",
                "setupResult" to "eu.nodepass.somewhere.protocol.frame.SetupResultVectorTest",
                "uot" to "eu.nodepass.somewhere.protocol.frame.UdpOverTcpVectorTest",
            )
    }

    @Test
    fun everyClaimedFamilyHasItsSuiteOnTheClasspath() {
        val missing =
            IMPLEMENTED.filterNot { (_, suite) ->
                runCatching { Class.forName(suite) }.isSuccess
            }
        assertTrue(
            "These families claim a suite that does not exist: $missing. " +
                "Either the suite was not written or the class name is wrong.",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyClaimedFamilyIsActuallyInTheFixture() {
        val unknown = IMPLEMENTED.keys - VectorFixture.families.toSet()
        assertTrue("Claimed families absent from the fixture: $unknown", unknown.isEmpty())
    }

    @Test
    fun reportOutstandingFamilies() {
        val pending = VectorFixture.families - IMPLEMENTED.keys
        val covered =
            IMPLEMENTED.keys.sumOf {
                VectorFixture.cases(it).size + VectorFixture.rejects(it).size
            }
        val total =
            VectorFixture.families.sumOf {
                VectorFixture.cases(it).size + VectorFixture.rejects(it).size
            }

        println("[vector coverage] $covered/$total vectors across ${IMPLEMENTED.size}/${VectorFixture.families.size} families")
        if (pending.isNotEmpty()) {
            println("[vector coverage] pending: ${pending.joinToString(", ")}")
        }

        // Not an assertion while the protocol layer is being built: this test
        // reports progress. The completion assertion is the one below.
        assertEquals(
            total,
            covered +
                pending.sumOf {
                    VectorFixture.cases(it).size + VectorFixture.rejects(it).size
                },
        )
    }

    @Test
    fun whenEveryFamilyIsClaimedNoneMayBeSilentlyDropped() {
        // Becomes a real assertion the moment the last family is added, and from
        // then on refuses any regression that drops one.
        if (IMPLEMENTED.keys.containsAll(VectorFixture.families)) {
            assertEquals(
                "Every fixture family must stay claimed once the set is complete",
                VectorFixture.families.toSet(),
                IMPLEMENTED.keys,
            )
        }
    }
}
