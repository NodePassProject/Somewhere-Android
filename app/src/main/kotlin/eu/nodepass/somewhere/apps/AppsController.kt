// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the per-application screen reads and writes.
 *
 * Every change is saved as it is made, because there is no other moment to
 * save at: the screen has no confirm button and a back press is not an event
 * this can rely on.
 *
 * ## The restart banner
 *
 * Android fixes the per-application set at `establish()`. A change made while
 * a tunnel is up therefore does nothing until the tunnel is rebuilt, and
 * showing it as applied would be a lie the traffic contradicts. So a change
 * made while engaged raises [restartNeeded], and the screen offers a
 * reconnect. The alternative — rebuilding the tunnel on every toggle — would
 * tear down live connections while somebody scrolled a list.
 */
class AppsController(
    private val store: AppSelectionStore,
    private val apps: AppSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val engaged: () -> Boolean,
) {
    private val mutableInstalled = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** Empty until [refresh] has finished; see [loading] for the difference. */
    val installed: StateFlow<List<InstalledApp>> = mutableInstalled.asStateFlow()

    private val mutableLoading = MutableStateFlow(true)

    /**
     * True while the list is being read.
     *
     * Distinct from an empty list on purpose: "still looking" and "nothing to
     * show" are different things, and a screen that renders them the same way
     * tells the user the device has no applications on it.
     */
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    private val mutableSelection = MutableStateFlow(AppSelection())
    val selection: StateFlow<AppSelection> = mutableSelection.asStateFlow()

    private val mutableRestartNeeded = MutableStateFlow(false)

    /** A tunnel is up and is still carrying the selection it was built with. */
    val restartNeeded: StateFlow<Boolean> = mutableRestartNeeded.asStateFlow()

    /**
     * Whether the platform is hiding applications this app has not asked to
     * see. D-16: the screen says so rather than presenting a partial list as
     * the whole one.
     */
    val listIsPartial: Boolean get() = apps.listIsPartial

    /** How many are installed and can never be routed whatever is chosen. */
    private val mutableUnroutable = MutableStateFlow(0)
    val unroutableCount: StateFlow<Int> = mutableUnroutable.asStateFlow()

    fun refresh() {
        scope.launch {
            mutableLoading.value = true
            mutableSelection.value = withContext(io) { store.load() }
            val inventory = withContext(io) { apps.inventory() }
            mutableInstalled.value = inventory.routable
            mutableUnroutable.value = inventory.unroutableCount
            mutableLoading.value = false
        }
    }

    fun setMode(mode: SelectionMode) = change { it.copy(mode = mode) }

    fun toggle(packageName: String) =
        change { current ->
            val packages =
                if (packageName in current.packages) {
                    current.packages - packageName
                } else {
                    current.packages + packageName
                }
            current.copy(packages = packages)
        }

    /** An application's icon. Straight through; the adapter caches and threads it. */
    suspend fun icon(packageName: String) = apps.icon(packageName)

    /** Called once a rebuild has actually happened, so the banner goes away. */
    fun restarted() {
        mutableRestartNeeded.value = false
    }

    private fun change(next: (AppSelection) -> AppSelection) {
        val before = mutableSelection.value
        val after = next(before)
        if (after == before) return
        mutableSelection.value = after
        if (changeOutcome(engaged(), before, after) == SelectionChange.NeedsRestart) {
            mutableRestartNeeded.value = true
        }
        scope.launch { withContext(io) { store.save(after) } }
    }
}
