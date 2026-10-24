// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import java.io.File
import java.io.IOException

/**
 * Whether rules are consulted, and what an unmentioned destination means.
 *
 * Its own file, one line, kept apart from the rule document: the document is
 * something the user imported and may replace wholesale, and losing the mode
 * every time a rule set is swapped would be a surprise nobody asked for.
 *
 * Both defaults are the behaviour the app had before routing existed —
 * [RoutingMode.Everything], everything through the Portal — so a device that
 * has never opened the routing screen is routed exactly as it was.
 */
class RoutingPreferences(
    private val file: File,
) {
    data class Settings(
        val mode: RoutingMode = RoutingMode.Everything,
        val fallback: RouteAction = RouteAction.Tunnel,
        /**
         * Whether Rules mode sends mainland-China destinations direct.
         *
         * On by default: someone who chose Rules mode with this set shipped
         * is, almost always, someone for whom it is the point. It changes
         * nothing in Everything mode, which stays the app's default.
         */
        val mainlandChina: Boolean = true,
    ) {
        internal fun render(): String = "${mode.name},${fallback.name},${if (mainlandChina) "china" else "nochina"}\n"
    }

    fun load(): Settings {
        if (!file.exists()) return Settings()
        return try {
            val fields = file.readText().trim().split(',')
            val mode = RoutingMode.entries.firstOrNull { it.name == fields.getOrNull(0) } ?: return Settings()
            // A fallback of Reject would make an unreadable file into a device
            // with no network, so an unrecognised one reads as Tunnel.
            val fallback = RouteAction.entries.firstOrNull { it.name == fields.getOrNull(1) } ?: RouteAction.Tunnel
            // Absent in files written before the set existed: the default.
            Settings(mode, fallback, mainlandChina = fields.getOrNull(2) != "nochina")
        } catch (_: IOException) {
            Settings()
        }
    }

    fun save(settings: Settings): Boolean =
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(settings.render())
            if (!temporary.renameTo(file)) {
                file.writeText(settings.render())
                temporary.delete()
            }
            true
        } catch (_: IOException) {
            false
        }
}
