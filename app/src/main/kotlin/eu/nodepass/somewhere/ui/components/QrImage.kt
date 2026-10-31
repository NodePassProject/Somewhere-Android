// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import eu.nodepass.somewhere.qr.QrCodes

/**
 * [text] as a QR code.
 *
 * Black on white in both themes, and deliberately outside the colour tokens: a
 * scanner is reading this, not a person, and many scanners refuse light on
 * dark. The quiet zone is part of the drawing, so the code reads even on a dark
 * card.
 */
@Composable
fun QrImage(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val modules = remember(text) { QrCodes.encode(text) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description },
    ) {
        drawRect(Color.White)
        val cell = size.width / modules.size
        // A hair of overlap so that adjacent modules do not show seams where
        // the cell size is not a whole number of pixels.
        val module = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until modules.size) {
            for (x in 0 until modules.size) {
                if (modules[x, y]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = module)
            }
        }
    }
}
