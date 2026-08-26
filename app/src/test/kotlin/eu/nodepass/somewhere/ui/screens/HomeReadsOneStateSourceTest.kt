// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Everything the home screen draws comes from one snapshot.
 *
 * The screen has already contradicted itself once, on a device, in the way this
 * rule forbids: the header read "Not connected" while the button below it
 * offered to disconnect. Neither was wrong on its own. The header read the
 * snapshot it was handed and the button read `TunnelController` directly, and
 * the two were sampled at different moments.
 *
 * The instrumentation test asserts that the elements agree in each state. It
 * cannot assert *why* they agree, and the reason is the durable part: a second
 * reader added later would pass the instrumentation test on the day it was
 * written and start disagreeing on a device weeks afterwards, under a timing
 * nobody reproduces.
 *
 * So the boundary is enforced where it lives. `HomeScreen` — the composable
 * that collects state — may read the controller. `Home` and everything below it
 * take a `SessionSnapshot` and may not, which is also what lets the design
 * previews and the instrumentation tests render the screen at all.
 */
class HomeReadsOneStateSourceTest {
    private companion object {
        val SOURCE = File("src/main/kotlin/eu/nodepass/somewhere/ui/screens/HomeScreen.kt")

        /** The state sources that must not be reached for below the seam. */
        val FORBIDDEN = listOf("TunnelController", "TunnelState")

        /** Where the seam is: this declaration, and everything after it. */
        const val SEAM = "internal fun Home("
    }

    private fun lines(): List<String> = SOURCE.readLines()

    private fun isComment(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    @Test
    fun theScreenAndItsSeamBothStillExist() {
        assertTrue("$SOURCE is missing; this test is looking in the wrong place", SOURCE.isFile)
        val text = SOURCE.readText()
        assertTrue(
            "the `$SEAM` declaration has gone. If the screen was restructured, this rule has to be " +
                "restated against whatever now separates the state collector from the drawing",
            SEAM in text,
        )
        assertTrue(
            "nothing in this file reads the controller at all, which means the collector moved and " +
                "this test is now guarding an empty region",
            FORBIDDEN.any { it in text },
        )
    }

    @Test
    fun nothingBelowTheSeamReadsTheTunnelDirectly() {
        val all = lines()
        val seamAt = all.indexOfFirst { SEAM in it }
        val offences =
            all.drop(seamAt).withIndex().mapNotNull { (offset, line) ->
                if (isComment(line)) return@mapNotNull null
                val code = line.substringBefore("//")
                val found = FORBIDDEN.firstOrNull { it in code } ?: return@mapNotNull null
                "HomeScreen.kt:${seamAt + offset + 1} reads $found — ${code.trim()}"
            }

        assertEquals(
            "below `$SEAM` the screen must draw only what it was handed. A second reader here is how " +
                "the header once said \"Not connected\" above a button offering to disconnect:\n" +
                offences.joinToString("\n"),
            emptyList<String>(),
            offences,
        )
    }

    @Test
    fun theStateCollectorAboveTheSeamIsTheOneThatReadsIt() {
        // The other half. Forbidding the reads below is only meaningful if they
        // are happening somewhere, and this names where.
        val all = lines()
        val seamAt = all.indexOfFirst { SEAM in it }
        val above = all.take(seamAt).filterNot(::isComment).joinToString("\n")
        FORBIDDEN.forEach { source ->
            assertTrue(
                "$source is no longer read above the seam either — the screen is getting its state " +
                    "from somewhere this rule does not know about",
                source in above,
            )
        }
    }
}
