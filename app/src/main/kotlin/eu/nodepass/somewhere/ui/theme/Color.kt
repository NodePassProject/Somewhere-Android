// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colour tokens, per `docs/design-system.md`.
 *
 * Two palettes, designed rather than inverted: each theme picks its own lightness
 * for the same hue. Dark's `#55C4CE` on a white ground measures 1.9:1 and is
 * unreadable, so the light theme darkens it instead of reusing it.
 *
 * Every ratio quoted below is measured against that theme's own ground and is
 * asserted in `ColorContrastTest` — the numbers are a gate, not a comment.
 */
@Immutable
data class SomewhereColors(
    val ground: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val muted: Color,
    val faint: Color,
    /**
     * The upstream direction.
     *
     * Never the same as [downstream], anywhere. Nowhere can put the two
     * directions on different transports, and this colour is how the app says
     * which is which — it is protocol state rendered as hue, not decoration.
     */
    val upstream: Color,
    val upstreamTint: Color,
    /** The downstream direction. See [upstream]. */
    val downstream: Color,
    val downstreamTint: Color,
    val good: Color,
    val goodTint: Color,
    val warn: Color,
    val critical: Color,
    val criticalTint: Color,
    /**
     * A recessed container for a grouped list — settings rows, rule sets.
     *
     * Distinct from [surface]: a card sits *on* the ground, a panel is cut
     * *into* it. In the dark theme that means darker than the surface; in the
     * light theme it means lighter. The direction reverses, the role does not.
     */
    val panel: Color,
    /** The hairline between two rows inside a [panel]. Lighter than [line]. */
    val panelLine: Color,
    /** The border that marks the selected upstream option, or a chosen row. */
    val upstreamLine: Color,
    /** The border that marks the selected downstream option. */
    val downstreamLine: Color,
    /** The border of a panel carrying a critical statement. */
    val criticalLine: Color,
    /** A dot or switch knob that is off. Never carries text. */
    val inactive: Color,
    /**
     * The primary action's fill, and the three secondary action fills.
     *
     * **These differ by theme on purpose**, per `docs/design-system.md`: a
     * tinted fill is the loudest thing on a dark screen, and the same treatment
     * on white reads as *disabled*. So dark tints and light goes solid — which
     * is why each carries its own `on*` colour rather than reusing [ink].
     */
    val primaryAction: Color,
    val onPrimaryAction: Color,
    val primaryActionLine: Color,
    val warnAction: Color,
    val onWarnAction: Color,
    val criticalAction: Color,
    val onCriticalAction: Color,
    val isDark: Boolean,
)

val LightColors: SomewhereColors =
    SomewhereColors(
        ground = Color(0xFFF4F7F7),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFEDF2F2),
        line = Color(0xFFD8E2E2),
        ink = Color(0xFF0F1618), // 16.98:1
        inkMuted = Color(0xFF33454A), // 9.32:1
        muted = Color(0xFF5E7076), // 4.81:1
        faint = Color(0xFF5E6D72), // 4.99:1 ground, 4.76:1 surfaceAlt
        upstream = Color(0xFF0C6E78), // 5.54:1
        upstreamTint = Color(0xFFE0F0F1),
        downstream = Color(0xFFA65814), // 4.84:1
        downstreamTint = Color(0xFFF7EADD),
        good = Color(0xFF2C6E49), // 5.68:1
        goodTint = Color(0xFFE4F0E9),
        warn = Color(0xFF8A6410), // 4.98:1
        critical = Color(0xFFA33228), // 6.40:1
        criticalTint = Color(0xFFF8E7E4),
        panel = Color(0xFFFFFFFF),
        panelLine = Color(0xFFE2EAEA),
        upstreamLine = Color(0xFFB6DCDF),
        downstreamLine = Color(0xFFE8CBA5),
        criticalLine = Color(0xFFEFC9C3),
        inactive = Color(0xFFB4C4C4),
        primaryAction = Color(0xFF0C6E78),
        onPrimaryAction = Color(0xFFFFFFFF), // 5.97:1
        primaryActionLine = Color(0xFF0C6E78),
        warnAction = Color(0xFF8A6410),
        onWarnAction = Color(0xFFFFFFFF), // 5.37:1
        criticalAction = Color(0xFFA33228),
        onCriticalAction = Color(0xFFFFFFFF), // 6.90:1
        isDark = false,
    )

val DarkColors: SomewhereColors =
    SomewhereColors(
        ground = Color(0xFF0C1214),
        surface = Color(0xFF131C1E),
        surfaceAlt = Color(0xFF182326),
        line = Color(0xFF253236),
        ink = Color(0xFFE7EFEF), // 16.18:1
        inkMuted = Color(0xFFC0D0D1), // 11.85:1
        muted = Color(0xFF8FA3A7), // 7.16:1
        faint = Color(0xFF7C8E94), // 5.53:1 ground, 4.71:1 surfaceAlt
        upstream = Color(0xFF55C4CE), // 9.15:1
        upstreamTint = Color(0xFF12292C),
        downstream = Color(0xFFE09B55), // 8.08:1
        downstreamTint = Color(0xFF2A1F14),
        good = Color(0xFF6BBF8C), // 8.50:1
        goodTint = Color(0xFF16261D),
        warn = Color(0xFFD9AC4A), // 8.95:1
        critical = Color(0xFFE2857A), // 7.08:1
        criticalTint = Color(0xFF2E1A18),
        panel = Color(0xFF0F1719),
        panelLine = Color(0xFF1B2528),
        upstreamLine = Color(0xFF1E4A50),
        downstreamLine = Color(0xFF4A3418),
        criticalLine = Color(0xFF4A2A26),
        inactive = Color(0xFF3A4A4E),
        primaryAction = Color(0xFF12292C),
        onPrimaryAction = Color(0xFF55C4CE), // 7.38:1
        primaryActionLine = Color(0xFF1E4A50),
        warnAction = Color(0xFF3A2E18),
        onWarnAction = Color(0xFFD9AC4A), // 6.28:1
        criticalAction = Color(0xFF4A2A26),
        onCriticalAction = Color(0xFFE7EFEF), // 10.90:1
        isDark = true,
    )
