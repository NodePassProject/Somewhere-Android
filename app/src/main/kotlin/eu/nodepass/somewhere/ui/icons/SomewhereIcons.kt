// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn from the same path data as the design canvas.
 *
 * These are stroke icons on a 24×24 grid — outlines, not filled glyphs. The
 * stock Material set is filled and sits on a different grid, so substituting it
 * would quietly change the weight of every screen. Building the vectors here
 * costs a few hundred lines once and keeps the drawn design and the shipped app
 * the same drawing.
 *
 * Colour comes from the caller: every path is stroked in black and tinted at the
 * call site, which is what lets one icon serve `upstream` on one screen and
 * `muted` on the next.
 */
object SomewhereIcons {
    private fun stroke(
        name: String,
        width: Float = 2f,
        cap: StrokeCap = StrokeCap.Round,
        vararg paths: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                paths.forEach { data ->
                    addPath(
                        pathData = PathParser().parsePathString(data).toNodes(),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = width,
                        strokeLineCap = cap,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()

    val ArrowUp = stroke("ArrowUp", 2.2f, paths = arrayOf("M12 19V5", "m5 12 7-7 7 7"))
    val ArrowDown = stroke("ArrowDown", 2.2f, paths = arrayOf("M12 5v14", "m19 12-7 7-7-7"))

    val AlertTriangle =
        stroke(
            "AlertTriangle",
            2f,
            paths =
                arrayOf(
                    "M12 9v4",
                    "M12 17h.01",
                    "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z",
                ),
        )

    val AlertCircle =
        stroke("AlertCircle", 2f, paths = arrayOf("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M12 8v4", "M12 16h.01"))

    val MoreVertical =
        stroke(
            "MoreVertical",
            1.8f,
            paths =
                arrayOf(
                    "M12 13.4a1.4 1.4 0 1 0 0-2.8 1.4 1.4 0 0 0 0 2.8Z",
                    "M12 6.4a1.4 1.4 0 1 0 0-2.8 1.4 1.4 0 0 0 0 2.8Z",
                    "M12 20.4a1.4 1.4 0 1 0 0-2.8 1.4 1.4 0 0 0 0 2.8Z",
                ),
        )

    val Power = stroke("Power", 2.2f, paths = arrayOf("M18.36 6.64A9 9 0 1 1 5.64 6.64", "M12 2v10"))
    val Plus = stroke("Plus", 2.2f, paths = arrayOf("M12 5v14", "M5 12h14"))
    val Check = stroke("Check", 2.4f, paths = arrayOf("M20 6 9 17l-5-5"))
    val ChevronRight = stroke("ChevronRight", 2.2f, paths = arrayOf("m9 18 6-6-6-6"))
    val ChevronLeft = stroke("ChevronLeft", 2.2f, paths = arrayOf("m15 18-6-6 6-6"))
    val Close = stroke("Close", 2.2f, paths = arrayOf("M18 6 6 18", "m6 6 12 12"))
    val Search = stroke("Search", 2.2f, paths = arrayOf("M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z", "m20 20-3.5-3.5"))
    val ExternalLink = stroke("ExternalLink", 2.2f, paths = arrayOf("M7 17 17 7", "M8 7h9v9"))

    val QrCode =
        stroke(
            "QrCode",
            2f,
            paths =
                arrayOf("M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h2v2h-2z", "M18 18h2v2h-2z"),
        )

    val Link =
        stroke(
            "Link",
            2f,
            paths =
                arrayOf(
                    "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7",
                    "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7",
                ),
        )

    // ── The four tabs ───────────────────────────────────────────────────────
    val TabHome = stroke("TabHome", 2f, paths = arrayOf("m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"))

    val TabNodes =
        stroke(
            "TabNodes",
            2f,
            paths =
                arrayOf(
                    "M4.6 4h14.8A1.6 1.6 0 0 1 21 5.6v3.8a1.6 1.6 0 0 1-1.6 1.6H4.6A1.6 1.6 0 0 1 3 9.4V5.6A1.6 1.6 0 0 1 4.6 4Z",
                    "M4.6 14h14.8A1.6 1.6 0 0 1 21 15.6v3.8a1.6 1.6 0 0 1-1.6 1.6H4.6A1.6 1.6 0 0 1 3 19.4v-3.8A1.6 1.6 0 0 1 4.6 14Z",
                    "M7 7.5h.01",
                    "M7 17.5h.01",
                ),
        )

    val TabRouting =
        stroke(
            "TabRouting",
            2f,
            paths =
                arrayOf(
                    "M3 6h18",
                    "M7 12h14",
                    "M11 18h10",
                    "M4 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z",
                    "M8 19a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z",
                ),
        )

    val TabLogs = stroke("TabLogs", 2f, paths = arrayOf("M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z", "M12 8v4l2.5 2.5"))
}
