// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the bundled sets cost on a device: the policy set is 3.9 MB of text,
 * and a phone parses it several times slower than a desktop JVM. Loaded once
 * per process on the tunnel's control thread, so the figure is how much later
 * the first connect of a process comes up — logged, so a device pass records
 * it, and bounded loosely so a regression by an order of magnitude fails.
 */
@RunWith(AndroidJUnit4::class)
class BundledRulesOnDeviceTest {
    @Test
    fun theBundledSetsLoadInAFewSecondsAtMostAndAnswer() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val started = android.os.SystemClock.elapsedRealtime()
        val sets = BundledRules.load { assets.open(it).bufferedReader().use { reader -> reader.readText() } }
        // The rules are parsed on first use, so they are used before the clock
        // stops; listing the sets alone costs almost nothing and proves nothing.
        val count = sets.sumOf { it.loaded.count }
        val took = android.os.SystemClock.elapsedRealtime() - started
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        Log.i(
            "BundledRules",
            "loaded $count rules in $took ms; heap in use ${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576} MiB",
        )

        assertEquals(listOf(BundledRules.Tier.Structural, BundledRules.Tier.Policy), sets.map { it.tier })
        val china = sets.single { it.asset == BundledRules.MAINLAND_CHINA }.loaded.rules
        assertEquals(RouteAction.Direct, china.decide("www.baidu.com"))
        assertTrue("loading the bundled sets took $took ms", took < 15_000)
    }
}
