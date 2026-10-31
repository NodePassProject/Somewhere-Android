// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One direction's recent rates, as a line against its own peak.
 *
 * Its own peak and never the other direction's, for the reason [Meter] gives:
 * the two have no common denominator, and a shared scale would flatten the
 * smaller one into the baseline. The newest reading sits at the right edge, and
 * a history shorter than [capacity] is drawn from the right, so the line grows
 * in from where new readings arrive rather than stretching to fill the card.
 */
@Composable
fun Sparkline(
    values: List<Long>,
    color: Color,
    track: Color,
    capacity: Int,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val stroke = 1.5.dp.toPx()
        val baseline = size.height - stroke / 2
        drawLine(track, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = stroke)
        if (values.size < 2) return@Canvas
        val peak = values.max().coerceAtLeast(1L).toFloat()
        val step = size.width / (capacity - 1).coerceAtLeast(1)
        val start = size.width - step * (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = start + step * index
            val y = baseline - (value / peak) * (baseline - stroke)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
