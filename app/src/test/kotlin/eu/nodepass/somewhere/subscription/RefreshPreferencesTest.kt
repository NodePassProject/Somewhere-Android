// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RefreshPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun preferences(name: String = "refresh.txt") = RefreshPreferences(File(folder.root, name))

    @Test
    fun `automatic refresh is off until somebody asks for it`() {
        // A refresh sends a bearer token to a dashboard. A client that started
        // doing that on a schedule because it was installed would be deciding
        // something that belongs to the user.
        assertFalse(preferences().load().automatic)
    }

    @Test
    fun `a saved setting comes back`() {
        val store = preferences()
        assertTrue(store.save(RefreshPreferences.Settings(automatic = true, intervalHours = 12)))
        assertEquals(RefreshPreferences.Settings(automatic = true, intervalHours = 12), store.load())
    }

    @Test
    fun `an unreadable file reads as the default rather than throwing`() {
        // A preference that could crash the screen that shows it would be worse
        // than one that forgets.
        File(folder.root, "refresh.txt").writeText("nonsense that is not a setting")
        assertEquals(RefreshPreferences.Settings(), preferences().load())
    }

    @Test
    fun `an interval outside what a scheduler honours is clamped on the way out`() {
        // Clamped when read rather than refused when written, so a file edited
        // by hand — or written by a version that allowed something this one
        // does not — produces a working schedule instead of a job that never
        // runs.
        val store = preferences()
        store.save(RefreshPreferences.Settings(automatic = true, intervalHours = 0))
        assertEquals(RefreshPreferences.MINIMUM_INTERVAL_HOURS, store.load().effectiveIntervalHours)

        store.save(RefreshPreferences.Settings(automatic = true, intervalHours = 10_000))
        assertEquals(RefreshPreferences.MAXIMUM_INTERVAL_HOURS, store.load().effectiveIntervalHours)
    }

    @Test
    fun `the default interval is the one the screen used to claim`() {
        // Six hours is what the Settings row said before anything did it. Kept,
        // so that turning the switch on does what the label always promised.
        assertEquals(6, RefreshPreferences.DEFAULT_INTERVAL_HOURS)
        assertEquals(6, preferences().load().effectiveIntervalHours)
    }

    @Test
    fun `turning it off and on again does not lose the interval`() {
        val store = preferences()
        store.save(RefreshPreferences.Settings(automatic = true, intervalHours = 3))
        store.save(store.load().copy(automatic = false))
        assertEquals(3, store.load().intervalHours)
    }
}
