// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The three hues that render one traffic direction.
 *
 * Obtained only from [SomewhereColors.direction]. Reading
 * [SomewhereColors.upstream] or [SomewhereColors.downstream] anywhere else is a
 * test failure — see `DirectionHueIsNotAnAccentTest`.
 */
@Immutable
data class DirectionColors(
    /** Text, icons, the meter fill. */
    val figure: Color,
    /** A chip or card ground carrying [figure]. */
    val tint: Color,
    /** The border of the selected carrier for this direction. */
    val line: Color,
)

/**
 * The colours for one direction of traffic.
 *
 * **This is the only sanctioned reader of the direction hues**, and the reason
 * is a defect rather than a preference. `upstream` teal was once used for six
 * unrelated things — the active tab, the add button, a reachable node's border,
 * the tunnel action, a usage meter, and the upstream direction — so on the node
 * list a teal `UP TCP` chip sat beside a teal border that meant nothing of the
 * kind. Everything that was an accent moved to [SomewhereColors.brand] and
 * everything that was a state moved to its state hue; what remained is here,
 * behind a call that cannot be made without naming a direction.
 */
fun SomewhereColors.direction(upstream: Boolean): DirectionColors =
    if (upstream) {
        DirectionColors(figure = this.upstream, tint = upstreamTint, line = upstreamLine)
    } else {
        DirectionColors(figure = downstream, tint = downstreamTint, line = downstreamLine)
    }
