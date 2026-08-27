// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import android.graphics.drawable.Drawable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the per-application screen reads, writes, and warns about. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppsControllerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeApps(
        private val apps: List<InstalledApp>,
        private val unroutable: Int = 0,
        override val listIsPartial: Boolean = false,
    ) : AppSource {
        override fun inventory() = AppInventory(apps, unroutable)

        override suspend fun icon(packageName: String): Drawable? = null
    }

    private fun controller(
        scope: TestScope,
        engaged: Boolean = false,
        apps: AppSource = FakeApps(listOf(InstalledApp("com.example.one", "One"))),
        store: AppSelectionStore = AppSelectionStore(File(folder.root, "selection.txt")),
    ) = AppsController(
        store = store,
        apps = apps,
        scope = scope,
        io = StandardTestDispatcher(scope.testScheduler),
        engaged = { engaged },
    )

    @Test
    fun refreshingReadsTheListAndTheStoredSelection() =
        runTest {
            val store = AppSelectionStore(File(folder.root, "selection.txt"))
            store.save(AppSelection(SelectionMode.OnlyThese, setOf("com.example.one")))
            val controller = controller(this, store = store)

            assertTrue("nothing is known before a refresh", controller.loading.value)
            controller.refresh()
            advanceUntilIdle()

            assertFalse(controller.loading.value)
            assertEquals(listOf("com.example.one"), controller.installed.value.map { it.packageName })
            assertEquals(SelectionMode.OnlyThese, controller.selection.value.mode)
        }

    @Test
    fun stillReadingIsNotTheSameAsNothingToShow() =
        runTest {
            // A screen that renders the two the same way tells the user their
            // device has no applications on it.
            val controller = controller(this, apps = FakeApps(emptyList()))
            assertTrue(controller.loading.value)
            assertTrue(controller.installed.value.isEmpty())
            controller.refresh()
            advanceUntilIdle()
            assertFalse("the list is now known, and it is empty", controller.loading.value)
        }

    @Test
    fun aToggleIsSavedWithoutWaitingForTheScreenToClose() =
        runTest {
            val file = File(folder.root, "selection.txt")
            val controller = controller(this, store = AppSelectionStore(file))
            controller.refresh()
            advanceUntilIdle()

            controller.toggle("com.example.one")
            advanceUntilIdle()

            assertEquals(
                setOf("com.example.one"),
                AppSelectionStore(file).load().packages,
            )
        }

    @Test
    fun togglingTwiceLeavesNothingSelected() =
        runTest {
            val controller = controller(this)
            controller.refresh()
            advanceUntilIdle()
            controller.toggle("com.example.one")
            controller.toggle("com.example.one")
            advanceUntilIdle()
            assertTrue(
                controller.selection.value.packages
                    .isEmpty(),
            )
        }

    @Test
    fun changingTheSelectionWhileConnectedAsksForARebuild() =
        runTest {
            val controller = controller(this, engaged = true)
            controller.refresh()
            advanceUntilIdle()
            assertFalse(controller.restartNeeded.value)

            controller.toggle("com.example.one")
            advanceUntilIdle()
            assertTrue("the tunnel still carries what it was built with", controller.restartNeeded.value)

            controller.restarted()
            assertFalse(controller.restartNeeded.value)
        }

    @Test
    fun changingItWhileDisconnectedAsksForNothing() =
        runTest {
            val controller = controller(this, engaged = false)
            controller.refresh()
            advanceUntilIdle()
            controller.setMode(SelectionMode.OnlyThese)
            advanceUntilIdle()
            assertFalse(controller.restartNeeded.value)
        }

    @Test
    fun settingTheModeItAlreadyHasIsNotAChange() =
        runTest {
            val controller = controller(this, engaged = true)
            controller.refresh()
            advanceUntilIdle()
            controller.setMode(SelectionMode.AllButThese)
            advanceUntilIdle()
            assertFalse("a screen opened and closed must not rebuild a tunnel", controller.restartNeeded.value)
        }

    @Test
    fun aPartialListIsReportedRatherThanPresentedAsTheWholeOne() =
        runTest {
            val controller =
                controller(this, apps = FakeApps(listOf(InstalledApp("com.example.one", "One")), listIsPartial = true))
            assertTrue(controller.listIsPartial)
        }

    @Test
    fun applicationsTheSystemNeverRoutesAreCountedForTheScreen() =
        runTest {
            val controller =
                controller(this, apps = FakeApps(listOf(InstalledApp("com.example.one", "One")), unroutable = 7))
            controller.refresh()
            advanceUntilIdle()
            assertEquals(7, controller.unroutableCount.value)
        }
}
