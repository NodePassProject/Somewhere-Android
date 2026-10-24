// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * When to ask GitHub for a newer release, and what the answer was.
 *
 * Automatic checks run at most once a day, when the app is opened, and only
 * while Settings says so (on by default, as in FlClash). A version the user
 * dismissed is not offered again; a newer one is.
 */
class UpdateController(
    private val checker: UpdateChecker,
    /** One line: the last automatic check's time, and the tag the user dismissed. */
    private val file: File?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the screen shows: nothing yet, a check in flight, or its answer. */
    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class Done(
            val result: UpdateCheck,
        ) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutableOffer = MutableStateFlow<Release?>(null)

    /** A release to offer in a dialog, from an automatic check. Cleared when answered. */
    val offer: StateFlow<Release?> = mutableOffer.asStateFlow()

    /** The check behind Settings' "Check for updates": always runs, never shows the dialog. */
    fun checkNow() {
        if (mutableState.value == State.Checking) return
        mutableState.value = State.Checking
        scope.launch { mutableState.value = State.Done(withContext(io) { checker.check() }) }
    }

    /** On app start. Does nothing when [enabled] is off or the last check was under a day ago. */
    fun checkIfDue(enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            val (last, dismissed) = withContext(io) { read() }
            if (clock() - last < DAY_MILLIS) return@launch
            val result = withContext(io) { checker.check() }
            withContext(io) { write(clock(), dismissed) }
            mutableState.value = State.Done(result)
            val release = (result as? UpdateCheck.Available)?.release ?: return@launch
            if (release.tag != dismissed) mutableOffer.value = release
        }
    }

    /** "Later": not offered again until a newer release exists. */
    fun dismiss(release: Release) {
        mutableOffer.value = null
        scope.launch { withContext(io) { write(read().first, release.tag) } }
    }

    private fun read(): Pair<Long, String> {
        val text = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty().trim()
        val fields = text.split(',')
        return (fields.getOrNull(0)?.toLongOrNull() ?: 0L) to fields.getOrNull(1).orEmpty()
    }

    private fun write(
        checkedAt: Long,
        dismissed: String,
    ) {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            target.writeText("$checkedAt,$dismissed\n")
        } catch (_: IOException) {
            // A check that cannot remember itself checks again tomorrow-or-sooner;
            // nothing worse follows.
        }
    }

    companion object {
        const val DAY_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
