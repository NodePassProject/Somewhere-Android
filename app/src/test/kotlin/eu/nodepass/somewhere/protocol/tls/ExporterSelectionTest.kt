// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API-level boundary from ADR-0001, asserted rather than remembered.
 *
 * The exact number matters: `exportKeyingMaterial` is absent at 28, present
 * without the method at 29, and public at 31 — verified with `javap` against the
 * platform jars. Getting it wrong by one release means either a crash on a device
 * that lacks the call, or carrying Conscrypt on devices that never needed it.
 */
class ExporterSelectionTest {
    @Test
    fun theBoundaryIsApiThirtyOne() {
        assertEquals("ADR-0001: the platform exporter is public from API 31", 31, Exporters.PLATFORM_MIN_API)
    }

    @Test
    fun everySupportedApiLevelGetsAnExporter() {
        // minSdk 26 through the current target: no gap may exist, because a
        // device without an exporter cannot authenticate at all.
        for (sdk in 26..36) {
            val usesPlatform = Exporters.usesPlatform(sdk)
            assertEquals("API $sdk should use the platform exporter only from 31", sdk >= 31, usesPlatform)
        }
    }

    @Test
    fun conscryptCoversExactlyTheRangeThePlatformDoesNot() {
        val conscryptRange = (26..36).filterNot { Exporters.usesPlatform(it) }
        assertEquals("Conscrypt covers 26 through 30", (26..30).toList(), conscryptRange)
    }

    @Test
    fun theSelectionRuleIsSeparateFromTheConstruction() {
        // usesPlatform is pure and testable at every level; forDevice takes no
        // parameter so the version check is one lint can see. An earlier version
        // combined them, which allowed forDevice(31) on an API 26 device — a
        // crash reachable only on the oldest phones.
        assertTrue(Exporters.usesPlatform(31))
        assertTrue(!Exporters.usesPlatform(30))
    }

    @Test
    fun theConscryptPathIsReachableOnTheJvm() {
        // Guards the test setup itself: if Conscrypt were missing from the test
        // classpath, ExporterAgainstPortalTest would skip for the wrong reason
        // and nobody would notice.
        val exporter = ConscryptExporter()
        assertEquals("conscrypt", exporter.name)
        assertTrue(runCatching { org.conscrypt.Conscrypt.newProvider() }.isSuccess)
    }
}
