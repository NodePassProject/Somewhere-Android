// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class RoutingPreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun preferences(name: String = "routing.txt") = RoutingPreferences(File(folder.root, name))

    @Test
    fun nothingStoredIsTheBehaviourTheAppHadBeforeRulesExisted() {
        assertEquals(
            RoutingPreferences.Settings(RoutingMode.Everything, RouteAction.Tunnel),
            preferences().load(),
        )
    }

    @Test
    fun everyCombinationRoundTrips() {
        for (mode in RoutingMode.entries) {
            for (fallback in RouteAction.entries) {
                val store = preferences("$mode-$fallback.txt")
                val settings = RoutingPreferences.Settings(mode, fallback)
                store.save(settings)
                assertEquals(settings, store.load())
            }
        }
    }

    @Test
    fun anUnreadableFileNeverLeavesTheDeviceWithoutANetwork() {
        // A fallback of Reject read out of a corrupt file would mean every
        // unmatched destination refused, which looks exactly like a device
        // with no connectivity at all.
        val file = File(folder.root, "routing.txt")
        file.writeText("Rules,Nonsense\n")
        assertEquals(RouteAction.Tunnel, RoutingPreferences(file).load().fallback)
    }

    @Test
    fun arbitraryBytesLoadAsTheDefault() {
        val random = Random(20260827)
        repeat(300) { iteration ->
            val file = File(folder.root, "fuzz-$iteration.txt")
            file.writeBytes(ByteArray(random.nextInt(0, 64)) { random.nextInt(0, 256).toByte() })
            val loaded = RoutingPreferences(file).load()
            // Written as a direct assertion after the first version compared a
            // value with itself through a conditional and could not fail.
            assertNotEquals("random bytes must never mean Reject", RouteAction.Reject, loaded.fallback)
        }
    }
}
