// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.apps.AppSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

/** How the node list is ordered. The user's own order is the default. */
enum class NodeSort {
    ListOrder,
    Latency,
    Name,
}

/** Which palette the app draws with. Following the system is the default. */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/**
 * The app's own preferences: how screens look and what the app does on its
 * own initiative.
 *
 * ## One file, unlike the rest
 *
 * The other preference files in this app are each read by something that runs
 * apart from the screen that writes them — a scheduled job, the VPN service —
 * and keeping them separate stops one from being a reason to reparse another.
 * Everything here is read by the activity and the tile, both in the app's own
 * process, so one file is the simpler truth.
 *
 * `key=value`, one per line. A key this version does not know is ignored and a
 * value it cannot read is its default, so a file written by a newer version, or
 * edited by hand, still produces a working app.
 *
 * ## Defaults are the behaviour before the setting existed
 *
 * Every default here is what the app did before the switch was added, so an
 * upgrade changes nothing until the user does.
 */
data class AppSettings(
    val nodeSort: NodeSort = NodeSort.ListOrder,
    val hideUnreachable: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    /** Connect when the app is opened. Off: opening an app is not consent to a tunnel. */
    val connectOnLaunch: Boolean = false,
    /** Keep the app out of the recent-tasks list. */
    val hideFromRecents: Boolean = false,
    /**
     * Whether other apps may start and stop the tunnel by intent.
     *
     * **Off by default.** An exported stop lets any installed app take the
     * tunnel down without a word on screen, and a tunnel another app can drop
     * silently is a leak with a convenience attached. The user turns it on for
     * an automation app they trust.
     */
    val automation: Boolean = false,
    /** Whether the per-app list shows apps that came with the device. */
    val appsShowSystem: Boolean = true,
    val appsSort: AppSort = AppSort.Name,
) {
    internal fun render(): String =
        buildString {
            append("nodeSort=").append(nodeSort.name).append('\n')
            append("hideUnreachable=").append(hideUnreachable).append('\n')
            append("theme=").append(theme.name).append('\n')
            append("connectOnLaunch=").append(connectOnLaunch).append('\n')
            append("hideFromRecents=").append(hideFromRecents).append('\n')
            append("automation=").append(automation).append('\n')
            append("appsShowSystem=").append(appsShowSystem).append('\n')
            append("appsSort=").append(appsSort.name).append('\n')
        }

    companion object {
        internal fun parse(text: String): AppSettings {
            val fields =
                text
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val at = line.indexOf('=')
                        if (at <= 0) null else line.take(at).trim() to line.substring(at + 1).trim()
                    }.toMap()
            val defaults = AppSettings()
            return AppSettings(
                nodeSort = NodeSort.entries.firstOrNull { it.name == fields["nodeSort"] } ?: defaults.nodeSort,
                hideUnreachable = fields["hideUnreachable"].asBoolean(defaults.hideUnreachable),
                theme = ThemeMode.entries.firstOrNull { it.name == fields["theme"] } ?: defaults.theme,
                connectOnLaunch = fields["connectOnLaunch"].asBoolean(defaults.connectOnLaunch),
                hideFromRecents = fields["hideFromRecents"].asBoolean(defaults.hideFromRecents),
                automation = fields["automation"].asBoolean(defaults.automation),
                appsShowSystem = fields["appsShowSystem"].asBoolean(defaults.appsShowSystem),
                appsSort = AppSort.entries.firstOrNull { it.name == fields["appsSort"] } ?: defaults.appsSort,
            )
        }

        /** Only the two words are read. Anything else is the default, not false. */
        private fun String?.asBoolean(default: Boolean): Boolean =
            when (this) {
                "true" -> true
                "false" -> false
                else -> default
            }
    }
}

/**
 * [AppSettings] on disk, published as state.
 *
 * A null [file] keeps the settings in memory only, for previews and tests that
 * should not write wherever the working directory happens to be.
 */
class AppSettingsStore(
    private val file: File?,
) {
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = mutable.asStateFlow()

    /** Re-reads the file, after a restore replaced it. */
    fun reload() {
        mutable.value = load()
    }

    /** Applies [change] and writes the result. Returns whether it reached the disk. */
    fun update(change: (AppSettings) -> AppSettings): Boolean {
        mutable.update(change)
        return save(mutable.value)
    }

    private fun load(): AppSettings =
        try {
            if (file != null && file.exists()) AppSettings.parse(file.readText()) else AppSettings()
        } catch (_: IOException) {
            AppSettings()
        }

    private fun save(settings: AppSettings): Boolean {
        val file = file ?: return true
        return try {
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
}
