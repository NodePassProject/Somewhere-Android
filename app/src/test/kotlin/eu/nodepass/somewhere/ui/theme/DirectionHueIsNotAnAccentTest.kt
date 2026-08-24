// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The direction hues are readable only through [direction].
 *
 * This is a source-level rule because the defect it prevents is a source-level
 * one and nothing else can see it: `colors.upstream` compiles wherever it is
 * written, renders a perfectly pleasant teal, and passes every contrast
 * assertion — while quietly telling the reader that a tab, a button or a border
 * has something to do with the direction traffic travels.
 *
 * It had happened twenty-two times before anyone counted. `upstream` was the
 * active tab, the add button, a reachable node's border, the tunnel action, a
 * subscription usage meter *and* the upstream direction, so the node list
 * showed a teal `UP TCP` chip beside a teal border that meant "this node
 * answers". Anything that was an accent is now `brand`; anything that was a
 * state is now that state's own hue; what is genuinely a direction goes through
 * [direction], which cannot be called without saying which one.
 */
class DirectionHueIsNotAnAccentTest {
    private companion object {
        /** The tokens that spell a direction, in the form they appear in code. */
        val DIRECTION_TOKENS =
            listOf(
                "colors.upstream",
                "colors.downstream",
                "colors.upstreamTint",
                "colors.downstreamTint",
                "colors.upstreamLine",
                "colors.downstreamLine",
            )

        /** Where the tokens are allowed to be read: the palette and the accessor. */
        val SANCTIONED = setOf("Color.kt", "Direction.kt")
    }

    private fun uiSources(): List<File> =
        File("src/main/kotlin/eu/nodepass/somewhere/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun theUiPackageIsNotEmptySoThisTestCannotPassVacuously() {
        // A path typo would make every assertion below hold over nothing.
        assertTrue("no UI sources found; the scan path is wrong", uiSources().size >= 8)
    }

    @Test
    fun nothingOutsideTheThemeReadsADirectionHueDirectly() {
        val offences = mutableListOf<String>()
        uiSources()
            .filterNot { it.name in SANCTIONED }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (DIRECTION_TOKENS.any { code.contains(it) }) {
                        offences += "${file.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "the direction hues carry protocol meaning and are read only through " +
                "SomewhereColors.direction(upstream = …). If one of these really is a " +
                "direction, call the accessor; if it is an accent use `brand`, and if it " +
                "is a state use that state's own hue:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun theAccessorReturnsADifferentSetForEachDirection() {
        listOf(LightColors, DarkColors).forEach { colors ->
            val up = colors.direction(upstream = true)
            val down = colors.direction(upstream = false)
            assertTrue("figure must differ per direction", up.figure != down.figure)
            assertTrue("tint must differ per direction", up.tint != down.tint)
            assertTrue("line must differ per direction", up.line != down.line)
        }
    }
}
