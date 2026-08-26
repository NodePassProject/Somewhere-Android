// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** The selection survives a process death, and a bad file costs nothing. */
class AppSelectionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(name: String = "apps.txt") = AppSelectionStore(File(folder.root, name))

    @Test
    fun aSelectionRoundTrips() {
        val store = store()
        val selection = AppSelection(SelectionMode.OnlyThese, setOf("com.example.one", "com.example.two"))
        assertTrue(store.save(selection))
        assertEquals(selection, store.load())
    }

    @Test
    fun everyModeRoundTripsRatherThanJustTheOneThatWasTested() {
        for (mode in SelectionMode.entries) {
            val store = store("apps-$mode.txt")
            val selection = AppSelection(mode, setOf("com.example.one"))
            store.save(selection)
            assertEquals("mode $mode", selection, store.load())
        }
    }

    @Test
    fun anEmptySelectionRoundTripsAsItselfAndNotAsAMissingFile() {
        val store = store()
        val empty = AppSelection(SelectionMode.OnlyThese, emptySet())
        store.save(empty)
        assertEquals(empty, store.load())
    }

    @Test
    fun nothingStoredIsEverythingCarried() {
        assertEquals(AppSelection(SelectionMode.Everything, emptySet()), store().load())
    }

    @Test
    fun anUnreadableFileFallsBackToCarryingEverythingRatherThanToCarryingNothing() {
        // The direction is the point. A corrupt file that became OnlyThese with
        // an empty set would produce a tunnel carrying nothing, which is
        // indistinguishable from a broken tunnel.
        val file = File(folder.root, "apps.txt")
        file.writeText("not-a-mode\ncom.example.one\n")
        assertEquals(AppSelection(SelectionMode.Everything, emptySet()), AppSelectionStore(file).load())
    }

    @Test
    fun aLineThatCannotBeAPackageNameIsDropped() {
        val file = File(folder.root, "apps.txt")
        file.writeText("OnlyThese\ncom.example.one\nnot a package name\n\ncom.example.two\n")
        assertEquals(
            AppSelection(SelectionMode.OnlyThese, setOf("com.example.one", "com.example.two")),
            AppSelectionStore(file).load(),
        )
    }

    @Test
    fun arbitraryBytesNeitherCrashNorProduceASelectionOutOfNothing() {
        val random = Random(20260827)
        repeat(500) { iteration ->
            val file = File(folder.root, "fuzz-$iteration.txt")
            file.writeBytes(ByteArray(random.nextInt(0, 512)) { random.nextInt(0, 256).toByte() })
            val loaded = AppSelectionStore(file).load()
            assertTrue(
                "a package name out of random bytes must at least look like one",
                loaded.packages.all { it.isNotEmpty() && it.length <= 255 },
            )
        }
    }

    @Test
    fun aWriteThatIsInterruptedLeavesThePreviousSelectionRatherThanHalfOfANewOne() {
        // The temporary file is what makes this true; the test proves the
        // target is never the half-written one by checking that a leftover
        // temporary changes nothing about what loads.
        val file = File(folder.root, "apps.txt")
        val store = AppSelectionStore(file)
        val first = AppSelection(SelectionMode.AllButThese, setOf("com.example.one"))
        store.save(first)

        File(folder.root, "apps.txt.tmp").writeText("OnlyThese\ncom.example.interrupted\n")
        assertEquals(first, store.load())
    }
}
