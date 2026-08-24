// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R

/**
 * The type scale, per `docs/design-system.md`.
 *
 * Archivo for display, IBM Plex Sans for body, IBM Plex Mono for anything
 * measured. Archivo and Plex Sans ship as **variable** fonts: one 600 KB file
 * covers every weight the design uses, where three static cuts would cost more
 * and still not cover a fourth. Variable axes need API 26, which is exactly this
 * app's `minSdk`.
 *
 * **No CJK font is bundled.** Every Android device already carries one, and
 * bundling adds several megabytes for glyphs the system has. The fallback chain
 * resolves Chinese through the platform face.
 */
@OptIn(ExperimentalTextApi::class)
object SomewhereType {
    private fun variable(
        resource: Int,
        weight: FontWeight,
    ) = Font(
        resId = resource,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

    val Display: FontFamily =
        FontFamily(
            variable(R.font.archivo_variable, FontWeight.Medium),
            variable(R.font.archivo_variable, FontWeight.SemiBold),
            variable(R.font.archivo_variable, FontWeight.Bold),
        )

    val Body: FontFamily =
        FontFamily(
            variable(R.font.plex_sans_variable, FontWeight.Normal),
            variable(R.font.plex_sans_variable, FontWeight.Medium),
            variable(R.font.plex_sans_variable, FontWeight.SemiBold),
        )

    /**
     * Anything measured uses this, with tabular figures.
     *
     * Throughput, latency, flow ids, timestamps and byte counts are monospaced
     * so a value updating in place does not shift the layout under the reader's
     * eyes. Prose is never monospaced.
     */
    val Mono: FontFamily =
        FontFamily(
            Font(R.font.plex_mono_regular, FontWeight.Normal),
            Font(R.font.plex_mono_medium, FontWeight.Medium),
        )

    val screenTitle =
        TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.52).sp)
    val rowHeading =
        TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val sectionLabel =
        TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.54.sp)
    val body =
        TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val bodySmall =
        TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 12.sp)

    /** A headline figure, e.g. throughput. Tabular by construction. */
    val measure =
        TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 33.sp, textAlign = TextAlign.End)

    /** A log line, a flow id, a timestamp. */
    val monoSmall =
        TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp)

    val material: Typography =
        Typography(
            headlineMedium = screenTitle,
            titleMedium = rowHeading,
            labelSmall = sectionLabel,
            bodyMedium = body,
            bodySmall = bodySmall,
        )
}
