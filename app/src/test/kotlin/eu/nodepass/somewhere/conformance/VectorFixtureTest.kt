// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import eu.nodepass.somewhere.conformance.VectorFixture.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the fixture is reachable and is the one we think it is, before anything
 * relies on it.
 */
class VectorFixtureTest {
    @Test
    fun fixtureLoadsFromTheTestClasspath() {
        assertTrue("fixture should expose vector families", VectorFixture.families.isNotEmpty())
    }

    @Test
    fun fixtureCarriesTheSevenKnownFamilies() {
        assertEquals(
            listOf("auth", "flowHeader", "target", "setupResult", "uot", "quicDatagram", "tlsMux"),
            VectorFixture.families,
        )
    }

    @Test
    fun theAnchorVectorIsIntact() {
        // Confirmed three ways: the spec prose, an independent implementation, and
        // upstream's own Rust fixtures. If this value ever changes, the change is
        // either an upstream break or a corrupted fixture — never a local decision.
        val case = VectorFixture.cases("auth").first { it.str("name").contains("\"secret\"") }
        assertEquals("secret", case.str("sharedKeyUtf8"))
        assertEquals(
            "1076221669fa28bcf70aa8545bddd6f760dcefbe279c3f38a5ff5d925708f867",
            case.str("expectedAuthKeyHex"),
        )
    }

    @Test
    fun fixtureMatchesThePinnedProtocolBaseline() {
        // A fixture generated against a different snapshot than PROTOCOL_BASELINE
        // would validate the implementation against a spec nobody is targeting.
        val baselineFile =
            VectorFixtureTest::class.java.getResourceAsStream("/protocol-vectors.json")
        assertTrue("fixture resource must exist", baselineFile != null)
        baselineFile?.close()

        assertEquals("NodePassProject/Nowhere", VectorFixture.Baseline.repository)
        assertEquals("v1.8.2", VectorFixture.Baseline.tag)
        assertEquals("8807960c", VectorFixture.Baseline.commit)
    }

    @Test
    fun everyFamilyCarriesAtLeastOneVector() {
        VectorFixture.families.forEach { family ->
            val total = VectorFixture.cases(family).size + VectorFixture.rejects(family).size
            assertTrue("family '$family' carries no vectors at all", total > 0)
        }
    }

    @Test
    fun theFixtureHoldsTheExpectedVectorCount() {
        // Guards against a family being dropped: the count is asserted, so losing
        // vectors fails loudly instead of quietly reducing what is checked.
        val cases = VectorFixture.families.sumOf { VectorFixture.cases(it).size }
        val rejects = VectorFixture.families.sumOf { VectorFixture.rejects(it).size }
        assertEquals("positive cases", 25, cases)
        assertEquals("rejection cases", 34, rejects)
    }

    @Test
    fun hexDecodingRoundTrips() {
        assertEquals("01c000020101bb", "01c000020101bb".hexToByteArrayCompat().toHex())
        assertEquals(0, "".hexToByteArrayCompat().size)
    }
}
