// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `every default is the behaviour before the setting existed`() {
        val defaults = AppSettings()
        assertEquals(NodeSort.ListOrder, defaults.nodeSort)
        assertFalse(defaults.hideUnreachable)
        assertEquals(ThemeMode.System, defaults.theme)
        assertFalse("opening the app is not consent to a tunnel", defaults.connectOnLaunch)
        assertFalse(defaults.hideFromRecents)
        assertFalse("other apps may not stop the tunnel unless the user says so", defaults.automation)
    }

    @Test
    fun `settings survive a restart`() {
        val file = File(folder.root, "settings/app.txt")
        val changed =
            AppSettings(
                nodeSort = NodeSort.Latency,
                hideUnreachable = true,
                theme = ThemeMode.Dark,
                connectOnLaunch = true,
                hideFromRecents = true,
                automation = true,
            )
        assertTrue(AppSettingsStore(file).update { changed })
        assertEquals(changed, AppSettingsStore(file).settings.value)
    }

    @Test
    fun `an unknown key is ignored and an unreadable value is its default`() {
        val parsed = AppSettings.parse("nodeSort=Sideways\nhideUnreachable=yes\nfutureThing=1\ntheme=Dark\n# comment\nbroken\n")
        assertEquals(NodeSort.ListOrder, parsed.nodeSort)
        assertFalse(parsed.hideUnreachable)
        assertEquals(ThemeMode.Dark, parsed.theme)
    }

    @Test
    fun `a missing file is the defaults`() {
        assertEquals(AppSettings(), AppSettingsStore(File(folder.root, "absent.txt")).settings.value)
    }

    @Test
    fun `a store with no file keeps settings in memory`() {
        val store = AppSettingsStore(null)
        assertTrue(store.update { it.copy(theme = ThemeMode.Light) })
        assertEquals(ThemeMode.Light, store.settings.value.theme)
    }
}
