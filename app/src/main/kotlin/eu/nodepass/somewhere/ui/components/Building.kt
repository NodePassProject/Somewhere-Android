// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/*
 * The pieces every screen is assembled from.
 *
 * Radii are by role, per `docs/design-system.md`: 8 chips · 10 fields and rows ·
 * 12 cards and buttons · 14 the primary action. Nothing is fully rounded — a
 * pill reads as consumer-app friendliness, and this is closer to an instrument.
 */

/** An uppercase section label. Archivo, wide tracking, never a sentence. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = SomewhereTheme.colors.muted,
) {
    Text(
        text = text.uppercase(),
        style = SomewhereType.sectionLabel,
        color = color,
        modifier = modifier,
    )
}

/** A card: sits on the ground, bordered, radius 12. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    borderColor: Color = SomewhereTheme.colors.line,
    background: Color = SomewhereTheme.colors.surface,
    padding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout
            .PaddingValues(horizontal = 16.dp, vertical = 15.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A panel: a grouped list cut into the ground rather than sitting on it.
 *
 * Rows inside are divided by [PanelDivider], not by their own borders, so the
 * group reads as one object.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SomewhereTheme.colors.panel)
                .border(1.dp, SomewhereTheme.colors.line, RoundedCornerShape(12.dp)),
        content = content,
    )
}

/** One row of a [Panel]. */
@Composable
fun PanelRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The hairline between two [PanelRow]s. */
@Composable
fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SomewhereTheme.colors.panelLine),
    )
}

/**
 * A monospaced chip: `UP TCP`, `MUX`, `flow 8421`.
 *
 * Always a machine identifier, never a sentence — which is why it is
 * monospaced and why `docs/i18n.md` forbids translating what goes in it.
 */
@Composable
fun MonoChip(
    text: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    padding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout
            .PaddingValues(horizontal = 7.dp, vertical = 3.dp),
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            fontFamily = SomewhereType.Mono,
            fontSize = fontSize,
            letterSpacing = 0.4.sp,
            lineHeight = fontSize * 1.35f,
            color = foreground,
        )
    }
}

/** The connection dot: green when up, [inactive] when not. Never carries text. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 7.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** A switch, drawn to the design's dimensions rather than Material's. */
@Composable
fun SomewhereSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SomewhereTheme.colors
    Box(
        modifier
            .size(width = 46.dp, height = 27.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) colors.upstreamLine else colors.surfaceAlt)
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(21.dp)
                .clip(CircleShape)
                .background(if (checked) colors.upstream else colors.inactive),
        )
    }
}

/** A two-or-more-way segmented control. The selected fill is passed in so the
 *  upstream and downstream carriers keep their own hue. */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = SomewhereTheme.colors.surface,
    borderColor: Color = SomewhereTheme.colors.line,
    selectedFill: Color = SomewhereTheme.colors.upstreamLine,
    height: androidx.compose.ui.unit.Dp = 36.dp,
    mono: Boolean = false,
) {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .then(if (borderColor == Color.Transparent) Modifier else Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp)))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(height)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) selectedFill else Color.Transparent)
                        .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontFamily = if (mono) SomewhereType.Mono else SomewhereType.Body,
                    fontSize = if (mono) 11.5.sp else 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) colors.ink else colors.faint,
                )
            }
        }
    }
}

/**
 * A throughput meter.
 *
 * Deliberately not a shared component with a "total" — the two directions have
 * no common denominator, so each is scaled against its own recent peak.
 */
@Composable
fun Meter(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 3.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxSize()
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

/** A small icon button in a bordered square — the 44 dp minimum target. */
@Composable
fun IconSquare(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SomewhereTheme.colors.inkMuted,
    background: Color = SomewhereTheme.colors.surfaceAlt,
    borderColor: Color = SomewhereTheme.colors.line,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = tint)
    }
}

/** A short text button on a tinted panel — "Switch to TCP", "Add SNI". */
@Composable
fun SmallButton(
    label: String,
    onClick: () -> Unit,
    fill: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 30.dp,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(7.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = SomewhereType.bodySmall, fontWeight = FontWeight.Medium, color = contentColor)
    }
}

/** A one-line value that must not shift as it updates. */
@Composable
fun MonoText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = SomewhereType.Mono,
        fontSize = fontSize,
        fontWeight = weight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A field showing a value. Editing lands with the state layer; the frame is
 *  here so the layout it has to fit into is already fixed. */
@Composable
fun FieldBox(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SomewhereTheme.colors.surface)
            .border(1.dp, SomewhereTheme.colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A fixed-width spacer used where a row needs an explicit gutter. */
@Composable
fun HGap(width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width))
}
