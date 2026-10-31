// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

/** How the application list is ordered. */
enum class AppSort {
    Name,

    /** Newest first: the app just installed is usually the one being looked for. */
    InstallTime,
}

/**
 * The application list as the screen shows it, and what its bulk actions do.
 *
 * Presentation and bookkeeping only. What is *routed* is decided by
 * [ruleFor] from the stored selection; nothing here can put this client inside
 * its own tunnel, because nothing here sees it — [AppInventory] removed it.
 */
object AppListView {
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun arrange(
        apps: List<InstalledApp>,
        query: String,
        showSystem: Boolean,
        sort: AppSort,
        /**
         * Selected applications are kept even when they are system apps and
         * system apps are hidden: a choice the list no longer shows is one the
         * user cannot see or undo.
         */
        selected: Set<String>,
    ): List<InstalledApp> {
        val needle = query.trim()
        val visible =
            apps.filter { app ->
                (showSystem || !app.system || app.packageName in selected) &&
                    (
                        needle.isEmpty() ||
                            app.label.contains(needle, ignoreCase = true) ||
                            app.packageName.contains(needle, ignoreCase = true)
                    )
            }
        return when (sort) {
            // Already in label order from the inventory.
            AppSort.Name -> visible
            AppSort.InstallTime -> visible.sortedByDescending { it.installedAt }
        }
    }

    /** [selection] with every one of [shown] added. Only the shown ones: a search narrows "all". */
    fun selectAll(
        selection: AppSelection,
        shown: List<InstalledApp>,
    ): AppSelection = selection.copy(packages = selection.packages + shown.map { it.packageName })

    /** [selection] with every one of [shown] removed. */
    fun selectNone(
        selection: AppSelection,
        shown: List<InstalledApp>,
    ): AppSelection = selection.copy(packages = selection.packages - shown.map { it.packageName }.toSet())

    /** One package per line, sorted, for the clipboard. */
    fun export(selection: AppSelection): String = selection.packages.sorted().joinToString("\n")

    /**
     * Package names out of whatever was pasted: one per line, or separated by
     * commas or spaces, as other clients export them. Anything that cannot be
     * a package name is dropped, as the store drops it.
     *
     * Null when nothing in it could be one — a paste of the wrong thing should
     * not empty the user's selection.
     */
    fun parseImport(text: String): Set<String>? =
        text
            .split('\n', '\r', ',', ' ', '\t')
            .map(String::trim)
            .filter(AppSelectionStore::isPlausiblePackageName)
            // A package name is Java-identifier segments, at least two: not
            // "rm", not "1.2", not a word with an accent in it.
            .filter { PACKAGE_NAME.matches(it) }
            .toSet()
            .takeIf { it.isNotEmpty() }
}
