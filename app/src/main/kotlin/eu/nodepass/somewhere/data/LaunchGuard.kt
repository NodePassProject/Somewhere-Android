// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import java.io.File
import java.io.IOException

/**
 * Whether connecting on launch is safe to do this time.
 *
 * Connecting on launch has a failure mode that a person cannot escape: if the
 * connection is what crashes the app, every launch crashes, and the switch that
 * would stop it is behind the crash. So each automatic connect is counted
 * before it is attempted and the count is cleared once the app has survived
 * it. Two attempts in a row that never got cleared mean the app died during
 * them, and the third launch does not try — the user gets the app, and can
 * turn the switch off.
 *
 * A file, because the evidence has to survive the process dying.
 */
class LaunchGuard(
    private val file: File,
    private val limit: Int = DEFAULT_LIMIT,
) {
    /**
     * Records an automatic connect about to happen and says whether to go on.
     *
     * When it says no, the count starts again: the next launch tries once
     * more, so one bad night does not disable the feature for good.
     */
    fun beginAttempt(): Boolean {
        val attempts = read()
        if (attempts >= limit) {
            write(0)
            return false
        }
        write(attempts + 1)
        return true
    }

    /** The attempt survived. */
    fun survived() = write(0)

    private fun read(): Int =
        try {
            if (file.exists()) file.readText().trim().toIntOrNull() ?: 0 else 0
        } catch (_: IOException) {
            0
        }

    private fun write(count: Int) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(count.toString())
        } catch (_: IOException) {
            // A guard that cannot write fails open: the user still has the
            // switch, and an app that refused to connect because a counter
            // file was unwritable would be the stranger failure.
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 2

        /** How long the app must stay alive after an automatic connect for it to count as survived. */
        const val SURVIVAL_MILLIS = 15_000L
    }
}
