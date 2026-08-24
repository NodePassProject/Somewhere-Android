// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The contrast figures in `docs/design-system.md` as a gate rather than a claim.
 *
 * A ratio written in a document drifts the moment someone nudges a hex value; a
 * ratio asserted here cannot. This exists because measuring caught a real defect
 * the eye did not: the `faint` token — timestamps, package names, quota subtext —
 * shipped at 2.87:1 in light and 3.65:1 in dark, both below AA, on text people
 * are expected to read.
 */
class ColorContrastTest {
    private companion object {
        /** WCAG AA for body text. */
        const val AA = 4.5

        /** WCAG AA for large text and UI components. */
        const val AA_LARGE = 3.0
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertReadable(
        name: String,
        foreground: Color,
        background: Color,
        minimum: Double = AA,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$name is %.2f:1, below the required %.1f:1".format(ratio, minimum),
            ratio >= minimum,
        )
    }

    private fun eachTheme(block: (String, SomewhereColors) -> Unit) {
        block("light", LightColors)
        block("dark", DarkColors)
    }

    @Test
    fun everyTextTokenMeetsAaOnTheGround() {
        eachTheme { theme, c ->
            assertReadable("$theme ink", c.ink, c.ground)
            assertReadable("$theme inkMuted", c.inkMuted, c.ground)
            assertReadable("$theme muted", c.muted, c.ground)
            assertReadable("$theme faint", c.faint, c.ground)
        }
    }

    @Test
    fun everyTextTokenMeetsAaOnEverySurface() {
        // A token is not readable "in the theme" — it is readable on a surface.
        // The faint defect was only visible once every surface was checked.
        eachTheme { theme, c ->
            listOf("surface" to c.surface, "surfaceAlt" to c.surfaceAlt).forEach { (surfaceName, surface) ->
                assertReadable("$theme ink on $surfaceName", c.ink, surface)
                assertReadable("$theme inkMuted on $surfaceName", c.inkMuted, surface)
                assertReadable("$theme muted on $surfaceName", c.muted, surface)
                assertReadable("$theme faint on $surfaceName", c.faint, surface)
            }
        }
    }

    @Test
    fun directionColoursAreReadableOnTheGround() {
        eachTheme { theme, c ->
            assertReadable("$theme upstream", c.upstream, c.ground)
            assertReadable("$theme downstream", c.downstream, c.ground)
        }
    }

    @Test
    fun stateColoursAreReadableOnTheGround() {
        eachTheme { theme, c ->
            assertReadable("$theme good", c.good, c.ground)
            assertReadable("$theme warn", c.warn, c.ground)
            assertReadable("$theme critical", c.critical, c.ground)
        }
    }

    @Test
    fun everyColourIsReadableOnItsOwnTint() {
        // Chips put the colour on its tint, not on the ground.
        eachTheme { theme, c ->
            assertReadable("$theme upstream on tint", c.upstream, c.upstreamTint, AA_LARGE)
            assertReadable("$theme downstream on tint", c.downstream, c.downstreamTint, AA_LARGE)
            assertReadable("$theme good on tint", c.good, c.goodTint, AA_LARGE)
            assertReadable("$theme critical on tint", c.critical, c.criticalTint, AA_LARGE)
        }
    }

    @Test
    fun everyActionFillCarriesReadableText() {
        // The four filled actions are the one place the design deliberately
        // differs by theme — tinted on dark, solid on light — so each carries
        // its own foreground rather than reusing `ink`. Which means each is a
        // pair that has to be measured as a pair: change one half and the other
        // silently stops meeting AA.
        eachTheme { theme, c ->
            assertReadable("$theme onPrimaryAction", c.onPrimaryAction, c.primaryAction)
            assertReadable("$theme onWarnAction", c.onWarnAction, c.warnAction)
            assertReadable("$theme onCriticalAction", c.onCriticalAction, c.criticalAction)
        }
    }

    @Test
    fun textOnAPanelIsAsReadableAsTextOnASurface() {
        // A panel is a third ground, distinct from `ground` and `surface`.
        // `faint` failed on `surfaceAlt` once already, having passed on
        // `ground`; this is the same defect waiting on the new token.
        eachTheme { theme, c ->
            assertReadable("$theme ink on panel", c.ink, c.panel)
            assertReadable("$theme inkMuted on panel", c.inkMuted, c.panel)
            assertReadable("$theme muted on panel", c.muted, c.panel)
            assertReadable("$theme faint on panel", c.faint, c.panel)
        }
    }

    @Test
    fun aSelectionBorderIsNeverTheOnlyCue() {
        // Measured, not assumed: the selected border against the unselected one
        // is 1.11:1 in light and 1.35:1 in dark. A 1 dp hairline at that
        // separation is not perceivable, so **selection must always carry a
        // second cue** — the radio on the routing modes, the status dot and
        // latency on a node card. This test pins the measurement so that nobody
        // later "simplifies" a screen down to the border alone believing it
        // carries the state.
        eachTheme { _, c ->
            val separation = contrast(c.upstreamLine, c.line)
            assertTrue(
                "the selection border is now %.2f:1 against the unselected border. If it has ".format(separation) +
                    "genuinely reached 3.0:1 it can stand alone and this test should be replaced by " +
                    "assertReadable; until then every selected state needs a second cue.",
                separation < AA_LARGE,
            )
        }
    }

    @Test
    fun theTwoDirectionLinesCannotBeConfusedWithEachOther() {
        // The selected upstream carrier and the selected downstream carrier sit
        // side by side in the node editor. Whatever else is true of them, they
        // must not be the same fill — that is the one rule of this design.
        eachTheme { theme, c ->
            assertNotEquals("$theme direction fills must differ", c.upstreamLine, c.downstreamLine)
        }
    }

    @Test
    fun theLightThemeIsNotAnInversionOfTheDark() {
        // The whole reason two palettes exist. If someone ever "simplifies" this
        // by reusing one set, this fails and says why.
        assertNotEquals(LightColors.upstream, DarkColors.upstream)
        assertNotEquals(LightColors.downstream, DarkColors.downstream)

        val darkTealOnLightGround = contrast(DarkColors.upstream, LightColors.ground)
        assertTrue(
            "dark upstream on the light ground measures %.2f:1 — reusing it would be unreadable, which is why the light theme darkens the hue instead"
                .format(darkTealOnLightGround),
            darkTealOnLightGround < AA_LARGE,
        )
    }

    /** Hue angle in degrees, for judging whether two colours read as different. */
    private fun hueDegrees(color: Color): Double {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0.0
        val hue =
            when (max) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
        return ((hue + 360f) % 360f).toDouble()
    }

    private fun hueSeparation(
        one: Color,
        other: Color,
    ): Double {
        val diff = kotlin.math.abs(hueDegrees(one) - hueDegrees(other))
        return minOf(diff, 360.0 - diff)
    }

    @Test
    fun theTwoDirectionsAreNeverTheSameColour() {
        // The organising rule of the whole design, asserted so a well-meaning
        // palette tidy-up cannot quietly erase it.
        //
        // Judged by HUE separation, not contrast ratio. Contrast measures a
        // lightness difference: the two directions are deliberately close in
        // lightness so neither shouts over the other, and a contrast test reads
        // that as "indistinguishable" while the eye has no trouble at all. What
        // makes them tell apart is being on opposite sides of the wheel — teal
        // near 186 degrees, amber near 28.
        eachTheme { theme, c ->
            assertNotEquals("$theme: the two directions must differ", c.upstream, c.downstream)
            val separation = hueSeparation(c.upstream, c.downstream)
            assertTrue(
                "$theme: the directions are %.0f degrees apart in hue; they must be clearly different colours, not two shades of one"
                    .format(separation),
                separation >= 60.0,
            )
        }
    }

    @Test
    fun eachDirectionKeepsItsHueAcrossThemes() {
        // Hue is the identity; lightness belongs to the theme. If a theme ever
        // shifted a direction's hue, the same channel would read as a different
        // thing depending on the time of day.
        assertTrue(
            "upstream hue moved between themes: %.0f vs %.0f"
                .format(hueDegrees(LightColors.upstream), hueDegrees(DarkColors.upstream)),
            hueSeparation(LightColors.upstream, DarkColors.upstream) <= 15.0,
        )
        assertTrue(
            "downstream hue moved between themes: %.0f vs %.0f"
                .format(hueDegrees(LightColors.downstream), hueDegrees(DarkColors.downstream)),
            hueSeparation(LightColors.downstream, DarkColors.downstream) <= 15.0,
        )
    }

    @Test
    fun theTwoThemesDeclareThemselvesCorrectly() {
        assertTrue(DarkColors.isDark)
        assertTrue(!LightColors.isDark)
    }
}
