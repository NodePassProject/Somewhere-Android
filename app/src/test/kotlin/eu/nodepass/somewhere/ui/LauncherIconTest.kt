// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launcher icon's geometry, which no screenshot can check.
 *
 * An adaptive icon is a 108×108 canvas that every launcher masks differently —
 * circle, squircle, rounded square, teardrop — and only the middle **66%** is
 * guaranteed to survive. A mark that looks right on this machine's launcher can
 * be clipped on a device nobody here owns, and the failure is silent: the icon
 * simply looks wrong, to someone else.
 *
 * So the safe zone is arithmetic, and arithmetic is checkable.
 */
class LauncherIconTest {
    private val drawables = File("src/main/res/drawable")

    /** The 108-unit canvas, and the central 66% that always survives a mask. */
    private val canvas = 108.0
    private val safeFrom = canvas * (1 - SAFE_FRACTION) / 2
    private val safeTo = canvas - safeFrom

    private fun coordinates(file: File): List<Pair<Double, Double>> =
        Regex("""android:pathData="([^"]+)"""")
            .findAll(file.readText())
            .flatMap { match ->
                Regex("""(-?[\d.]+),(-?[\d.]+)""")
                    .findAll(match.groupValues[1])
                    .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            }.toList()

    private fun strokeWidths(file: File): List<Double> =
        Regex("""android:strokeWidth="([\d.]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1].toDouble() }
            .toList()

    @Test
    fun theForegroundStaysInsideTheSafeZone() {
        listOf("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml").forEach { name ->
            val file = File(drawables, name)
            assertTrue("$name is missing", file.exists())

            // Half a stroke width extends past the coordinate on every side,
            // and a round cap adds the same again — measuring the path alone
            // would clear the zone while the ink did not.
            val margin = (strokeWidths(file).maxOrNull() ?: 0.0) / 2
            coordinates(file).forEach { (x, y) ->
                assertTrue(
                    "$name draws at ($x, $y), which with a ${margin * 2} stroke leaves the safe zone",
                    x - margin >= safeFrom &&
                        x + margin <= safeTo &&
                        y - margin >= safeFrom &&
                        y + margin <= safeTo,
                )
            }
        }
    }

    @Test
    fun theMarkIsCentred() {
        // An off-centre mark survives a circular mask and looks wrong under a
        // squircle, which is the mask most launchers use.
        val points = coordinates(File(drawables, "ic_launcher_foreground.xml"))
        val centreX = (points.minOf { it.first } + points.maxOf { it.first }) / 2
        val centreY = (points.minOf { it.second } + points.maxOf { it.second }) / 2
        assertEquals("the mark is not horizontally centred", canvas / 2, centreX, 1.0)
        assertEquals("the mark is not vertically centred", canvas / 2, centreY, 1.0)
    }

    @Test
    fun theMonochromeLayerHasTheSameGeometryAsTheColouredOne() {
        // A mark a user has learned should not change shape when they turn
        // themed icons on.
        assertEquals(
            coordinates(File(drawables, "ic_launcher_foreground.xml")),
            coordinates(File(drawables, "ic_launcher_monochrome.xml")),
        )
    }

    @Test
    fun theTwoDirectionsDifferInLengthAndNotOnlyInColour() {
        // The monochrome layer has one colour and cannot say which direction is
        // which. Length is what still does.
        val shafts =
            Regex("""android:pathData="M([\d.]+),([\d.]+) L([\d.]+),([\d.]+)"""")
                .findAll(File(drawables, "ic_launcher_foreground.xml").readText())
                .map { match ->
                    val (x1, _, x2, _) = match.destructured
                    kotlin.math.abs(x2.toDouble() - x1.toDouble())
                }.toList()
        assertEquals("expected two shafts", 2, shafts.size)

        // Not merely different: different enough to read at launcher size with
        // the colour gone. The first version differed by fifteen percent, which
        // is a difference a test can see and an eye cannot.
        val shorter = shafts.min()
        val longer = shafts.max()
        assertTrue(
            "the two directions are $shorter and $longer, too close to tell apart in monochrome",
            shorter <= longer * 0.75,
        )
    }

    @Test
    fun theAdaptiveIconDeclaresAllThreeLayers() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val text = File("src/main/res/mipmap-anydpi-v26", name).readText()
            listOf("<background", "<foreground", "<monochrome").forEach { layer ->
                assertTrue("$name declares no $layer", text.contains(layer))
            }
        }
    }

    private companion object {
        /** Android's guarantee: the middle 66% of an adaptive icon survives. */
        const val SAFE_FRACTION = 0.66
    }
}
