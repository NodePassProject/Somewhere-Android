// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import java.io.File
import java.io.IOException

/**
 * The rule document on disk, and the rule set built from it.
 *
 * The document is stored as the text it arrived as, and re-read by the same
 * parser that accepted it — the reason `NodeStore` keeps node URLs as text.
 * A parsed structure written out beside the parser is a second definition of
 * what a rule is, and the two drift.
 *
 * ## Nothing is replaced until everything parses
 *
 * [import] parses first and writes second. A document that fails leaves the
 * previous rule set exactly as it was, which is the only safe direction: a
 * half-applied rule set routes traffic somewhere nobody chose.
 *
 * The write itself goes to a temporary file and is renamed over the target, so
 * a process death between the two leaves the old document rather than a
 * truncated new one.
 */
class RuleStore(
    private val file: File,
) {
    /** A rule set that was successfully loaded, and what it could not carry. */
    data class Loaded(
        val rules: RoutingRules,
        val count: Int,
        val unsupported: Map<String, Int>,
    ) {
        companion object {
            val NONE = Loaded(RoutingRules.EMPTY, 0, emptyMap())
        }
    }

    /**
     * The stored rule set, or [Loaded.NONE].
     *
     * A stored document that no longer parses reads as none rather than as an
     * error: the only thing that could have changed it is this app, and the
     * safe reading of a file we cannot understand is that there are no rules —
     * which the caller's default handles, visibly.
     */
    fun load(): Loaded {
        if (!file.exists()) return Loaded.NONE
        val text =
            try {
                file.readText()
            } catch (_: IOException) {
                return Loaded.NONE
            }
        val parsed = RuleDocument.parse(text).getOrNull() ?: return Loaded.NONE
        val rules = RoutingRules.of(parsed.rules).getOrNull() ?: return Loaded.NONE
        return Loaded(rules, parsed.rules.size, parsed.unsupported)
    }

    /** Parses [text], and only then replaces what is stored. */
    fun import(text: String): Result<Loaded> {
        val parsed = RuleDocument.parse(text).getOrElse { return Result.failure(it) }
        val rules = RoutingRules.of(parsed.rules).getOrElse { return Result.failure(it) }
        return try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(file)) {
                file.writeText(text)
                temporary.delete()
            }
            Result.success(Loaded(rules, parsed.rules.size, parsed.unsupported))
        } catch (error: IOException) {
            Result.failure(error)
        }
    }

    /** Removes the stored document. Rules stop applying; nothing else changes. */
    fun clear(): Boolean = !file.exists() || file.delete()
}
