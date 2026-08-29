// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import java.io.File
import java.io.IOException

/**
 * Whether the subscription refreshes on its own, and how often.
 *
 * Its own file for the reason every other preference here has one: it is read
 * by a scheduled job in a process the screen that writes it may not be part of,
 * and a shared file would make one a reason to reparse the other.
 *
 * **Off by default.** A subscription fetch reaches a dashboard over the network
 * carrying a bearer token, and a client that started doing that on a schedule
 * because it was installed would be making a decision that is the user's. The
 * screen offers it; nothing turns it on.
 */
class RefreshPreferences(
    private val file: File,
) {
    data class Settings(
        val automatic: Boolean = false,
        val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    ) {
        /**
         * The interval, clamped to what a scheduler will actually honour.
         *
         * Read rather than validated on write, so that a file edited by hand —
         * or written by a version that allowed something this one does not —
         * produces a working schedule instead of a job that never runs.
         */
        val effectiveIntervalHours: Int get() = intervalHours.coerceIn(MINIMUM_INTERVAL_HOURS, MAXIMUM_INTERVAL_HOURS)
    }

    fun load(): Settings {
        if (!file.exists()) return Settings()
        return try {
            val fields = file.readText().trim().split(',')
            Settings(
                automatic = fields.getOrNull(0) == "on",
                intervalHours = fields.getOrNull(1)?.toIntOrNull() ?: DEFAULT_INTERVAL_HOURS,
            )
        } catch (_: IOException) {
            Settings()
        }
    }

    fun save(settings: Settings): Boolean =
        try {
            file.parentFile?.mkdirs()
            val line = "${if (settings.automatic) "on" else "off"},${settings.intervalHours}\n"
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(line)
            if (!temporary.renameTo(file)) {
                file.writeText(line)
                temporary.delete()
            }
            true
        } catch (_: IOException) {
            false
        }

    companion object {
        /**
         * Six hours, which is what the Settings screen used to claim before
         * anything did it.
         *
         * A subscription changes when a dashboard operator changes it, which is
         * rarely and unpredictably. More often costs battery and a request per
         * device per interval against somebody's server; less often means a
         * revoked node stays in the list for a working day.
         */
        const val DEFAULT_INTERVAL_HOURS: Int = 6

        /**
         * The platform will not schedule a periodic job more often than fifteen
         * minutes, and a client asking for less would be asking for a schedule
         * it does not get. An hour is the floor offered here.
         */
        const val MINIMUM_INTERVAL_HOURS: Int = 1

        /** A day. Beyond it, a manual refresh is the honest mechanism. */
        const val MAXIMUM_INTERVAL_HOURS: Int = 24
    }
}
