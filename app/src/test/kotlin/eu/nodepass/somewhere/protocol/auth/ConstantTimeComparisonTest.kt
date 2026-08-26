// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The authentication tag is never compared with a short-circuiting equality.
 *
 * A source-level rule, because the defect is invisible to every other kind of
 * test. `expected.contentEquals(presented)` returns exactly the same answers as
 * `MessageDigest.isEqual` for every input a test could write — it simply returns
 * them sooner when the first bytes differ, and that timing is enough to forge a
 * tag one byte at a time. A functional test cannot see it, a fuzz test cannot
 * see it, and code review sees it only if somebody happens to look at that line
 * on the day it changes.
 *
 * Timing it instead was considered and rejected. A timing assertion on a JVM,
 * against a JIT, on a shared CI runner, is a flake generator that would be
 * deleted within a month — and its deletion would remove the only guard.
 *
 * The matrix lists this row as "code review". This is what a code-review rule
 * looks like once it is enforced by something that runs.
 */
class ConstantTimeComparisonTest {
    private companion object {
        val AUTH_SOURCES = File("src/main/kotlin/eu/nodepass/somewhere/protocol/auth")

        /**
         * Comparisons that return early on the first differing byte.
         *
         * `==` is not listed: it is unavoidable in ordinary code — lengths,
         * enums, flags — and a rule that flagged it would be turned off. What is
         * listed is the set of ways an author would plausibly compare two byte
         * arrays.
         */
        val SHORT_CIRCUITING = listOf("contentEquals", "Arrays.equals", ".equals(")
    }

    private fun sources(): List<File> = AUTH_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * The code on a line, with comments removed.
     *
     * KDoc as well as `//`, found the hard way: the first version of this rule
     * fired on `SharedKey.kt`'s own sentence explaining why `contentEquals` is
     * the wrong call. A rule that cannot be written about without tripping is a
     * rule people route around.
     */
    private fun codeOnly(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    @Test
    fun theAuthPackageIsNotEmptySoThisTestCannotPassVacuously() {
        val files = sources()
        assertTrue("no sources found under $AUTH_SOURCES", files.isNotEmpty())
        assertTrue(
            "Authentication.kt is not among them; this test is looking in the wrong place",
            files.any { it.name == "Authentication.kt" },
        )
    }

    @Test
    fun theAuthenticationTagIsNeverComparedWithAByteWiseEquality() {
        val offences =
            sources().flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val code = codeOnly(line)
                    val found = SHORT_CIRCUITING.firstOrNull { it in code } ?: return@mapNotNull null
                    "${file.name}:${index + 1} uses $found — ${code.trim()}"
                }
            }

        assertEquals(
            "a short-circuiting comparison in the authentication package leaks how many leading " +
                "bytes matched, which is enough to forge a tag one byte at a time:\n" +
                offences.joinToString("\n"),
            emptyList<String>(),
            offences,
        )
    }

    @Test
    fun theTagComparisonUsesAConstantTimeCall() {
        // The other half. Forbidding the wrong call is not the same as
        // requiring the right one — a comparison that was deleted altogether
        // would satisfy the rule above perfectly.
        val source = File(AUTH_SOURCES, "Authentication.kt").readText()
        assertTrue(
            "Authentication.kt no longer calls MessageDigest.isEqual; whatever replaced it must be " +
                "constant-time, and this test must be updated to say what it is",
            "MessageDigest.isEqual(" in source,
        )
    }

    @Test
    fun theRuleWouldCatchAViolation() {
        // A guard that has never been shown to fire is a guard nobody should
        // trust. This is the same check as above, run against a line that
        // breaks it, so the detection itself is exercised on every run rather
        // than only on the day somebody breaks the real thing.
        val violation = "        return if (expected.contentEquals(presented)) {"
        assertTrue(
            "the rule no longer detects the exact form it exists to forbid",
            SHORT_CIRCUITING.any { it in codeOnly(violation) },
        )

        // And the other way: prose about the defect must not fire it. Both
        // comment forms, because the KDoc one is what actually happened.
        listOf(
            "        // expected.contentEquals(presented) would leak the prefix length",
            "     * compared as different. [MessageDigest.isEqual] rather than `contentEquals`",
        ).forEach { prose ->
            assertTrue(
                "the rule reads a comment as code, so it fires on prose about the defect: ${'$'}prose",
                SHORT_CIRCUITING.none { it in codeOnly(prose) },
            )
        }
    }
}
