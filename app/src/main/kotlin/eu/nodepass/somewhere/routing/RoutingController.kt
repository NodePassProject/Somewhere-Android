// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the routing screen reads and writes.
 *
 * The same shape as `AppsController`, and for the same reason: the tunnel
 * reads these files when it is built, so a change made while one is up does
 * not reach it. [restartNeeded] says so rather than letting the screen imply
 * otherwise.
 */
class RoutingController(
    private val rules: RuleStore,
    private val preferences: RoutingPreferences,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val engaged: () -> Boolean,
) {
    private val mutableSettings = MutableStateFlow(RoutingPreferences.Settings())
    val settings: StateFlow<RoutingPreferences.Settings> = mutableSettings.asStateFlow()

    private val mutableLoaded = MutableStateFlow(RuleStore.Loaded.NONE)

    /** The rule set as stored: how many rules, and what kinds were not carried. */
    val loaded: StateFlow<RuleStore.Loaded> = mutableLoaded.asStateFlow()

    private val mutableRestartNeeded = MutableStateFlow(false)
    val restartNeeded: StateFlow<Boolean> = mutableRestartNeeded.asStateFlow()

    private val mutableLastImportError = MutableStateFlow<String?>(null)

    /** Why the last import was refused, or null. Cleared by the next attempt. */
    val lastImportError: StateFlow<String?> = mutableLastImportError.asStateFlow()

    fun refresh() {
        scope.launch {
            mutableSettings.value = withContext(io) { preferences.load() }
            mutableLoaded.value = withContext(io) { rules.load() }
        }
    }

    fun setMode(mode: RoutingMode) {
        val before = mutableSettings.value
        if (before.mode == mode) return
        val after = before.copy(mode = mode)
        mutableSettings.value = after
        if (engaged()) mutableRestartNeeded.value = true
        scope.launch { withContext(io) { preferences.save(after) } }
    }

    /**
     * Replaces the rule set, or reports why it was refused.
     *
     * The error is kept rather than thrown: an import is something a person
     * did, and the reason it failed — a line number, a link where a rule
     * should be — is the whole of what they need to fix it.
     */
    fun import(text: String) {
        scope.launch {
            val outcome = withContext(io) { rules.import(text) }
            outcome
                .onSuccess {
                    mutableLoaded.value = it
                    mutableLastImportError.value = null
                    if (engaged()) mutableRestartNeeded.value = true
                }.onFailure { mutableLastImportError.value = it.message ?: it.javaClass.simpleName }
        }
    }

    fun clearRules() {
        scope.launch {
            withContext(io) { rules.clear() }
            mutableLoaded.value = RuleStore.Loaded.NONE
            mutableLastImportError.value = null
            if (engaged()) mutableRestartNeeded.value = true
        }
    }

    fun restarted() {
        mutableRestartNeeded.value = false
    }
}
