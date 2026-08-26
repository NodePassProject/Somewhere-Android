// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import java.io.File
import java.io.IOException

/**
 * The per-application selection, on disk.
 *
 * The same shape as `NodeStore` and for the same reasons: a text file of
 * lines, written to a temporary file and renamed over the target so that a
 * process death mid-write leaves the previous selection rather than half of a
 * new one.
 *
 * ## What an unreadable file means
 *
 * [SelectionMode.Everything], the default, which is what the app did before
 * this existed. That direction matters: the two failure modes here are "carry
 * more than the user asked" and "carry less", and a corrupt file that silently
 * became [SelectionMode.OnlyThese] with nothing in it would produce a tunnel
 * carrying nothing at all, which looks exactly like a broken tunnel. Falling
 * back to the mode that carries everything is wrong in the direction the user
 * can see.
 */
class AppSelectionStore(
    private val file: File,
) {
    /** Reads the selection, or the default when there is nothing readable. */
    fun load(): AppSelection {
        if (!file.exists()) return AppSelection()
        return try {
            val lines =
                file
                    .readLines()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            val mode = lines.firstOrNull()?.let(::modeFor) ?: return AppSelection()
            AppSelection(mode, lines.drop(1).filter(::isPlausiblePackageName).toSet())
        } catch (_: IOException) {
            AppSelection()
        }
    }

    /** Replaces the selection. Returns false when nothing was written. */
    fun save(selection: AppSelection): Boolean {
        val body =
            (listOf(selection.mode.name) + selection.packages.filter(::isPlausiblePackageName).sorted())
                .joinToString("\n")
        return try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(body + "\n")
            if (!temporary.renameTo(file)) {
                file.writeText(body + "\n")
                temporary.delete()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun modeFor(marker: String): SelectionMode? = SelectionMode.entries.firstOrNull { it.name == marker }

    /**
     * Cheap shape check, not validation.
     *
     * A package name that does not look like one cannot be installed, so it
     * would be dropped by [ruleFor] anyway. This keeps arbitrary file content —
     * a line of binary, a whole other document pasted in — from being carried
     * around as if it were a selection.
     */
    private fun isPlausiblePackageName(text: String): Boolean =
        text.isNotEmpty() &&
            text.length <= MAX_PACKAGE_NAME_LENGTH &&
            text.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private companion object {
        /** Longer than any real package name and short enough to bound a bad file. */
        const val MAX_PACKAGE_NAME_LENGTH = 255
    }
}
