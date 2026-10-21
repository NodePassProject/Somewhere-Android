// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppListViewTest {
    private val browser = InstalledApp("org.example.browser", "Browser", system = false, installedAt = 300)
    private val mail = InstalledApp("org.example.mail", "Mail", system = false, installedAt = 100)
    private val dialer = InstalledApp("com.android.dialer", "Phone", system = true, installedAt = 0)
    private val apps = listOf(browser, mail, dialer)

    private fun names(list: List<InstalledApp>) = list.map { it.label }

    @Test
    fun `everything shows by default, in label order`() {
        assertEquals(listOf("Browser", "Mail", "Phone"), names(AppListView.arrange(apps, "", true, AppSort.Name, emptySet())))
    }

    @Test
    fun `system apps can be hidden, except a selected one`() {
        assertEquals(listOf("Browser", "Mail"), names(AppListView.arrange(apps, "", false, AppSort.Name, emptySet())))
        assertEquals(
            listOf("Browser", "Mail", "Phone"),
            names(AppListView.arrange(apps, "", false, AppSort.Name, setOf("com.android.dialer"))),
        )
    }

    @Test
    fun `newest first by install time`() {
        assertEquals(listOf("Browser", "Mail", "Phone"), names(AppListView.arrange(apps, "", true, AppSort.InstallTime, emptySet())))
        assertEquals(listOf("Mail", "Phone"), names(AppListView.arrange(listOf(dialer, mail), "", true, AppSort.InstallTime, emptySet())))
    }

    @Test
    fun `search matches label or package`() {
        assertEquals(listOf("Mail"), names(AppListView.arrange(apps, "mAi", true, AppSort.Name, emptySet())))
        assertEquals(listOf("Phone"), names(AppListView.arrange(apps, "android", true, AppSort.Name, emptySet())))
    }

    @Test
    fun `select all and none act on what is shown`() {
        val start = AppSelection(SelectionMode.OnlyThese, setOf("com.android.dialer"))
        val all = AppListView.selectAll(start, listOf(browser, mail))
        assertEquals(setOf("com.android.dialer", "org.example.browser", "org.example.mail"), all.packages)
        val none = AppListView.selectNone(all, listOf(browser))
        assertEquals(setOf("com.android.dialer", "org.example.mail"), none.packages)
        assertEquals(SelectionMode.OnlyThese, none.mode)
    }

    @Test
    fun `export and import round-trip`() {
        val selection = AppSelection(packages = setOf("b.example", "a.example"))
        val text = AppListView.export(selection)
        assertEquals("a.example\nb.example", text)
        assertEquals(selection.packages, AppListView.parseImport(text))
    }

    @Test
    fun `import accepts other clients' separators and drops what cannot be a package`() {
        assertEquals(setOf("a.example", "b.example", "c_d.e"), AppListView.parseImport("a.example, b.example c_d.e\n; rm -rf /\n"))
    }

    @Test
    fun `a paste with no package in it is no import`() {
        assertNull(AppListView.parseImport("hello! ?? ;;"))
        assertNull(AppListView.parseImport(""))
    }
}
