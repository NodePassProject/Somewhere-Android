// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.IconSquare
import eu.nodepass.somewhere.ui.components.Meter
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.StatusDot
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.state.Format
import eu.nodepass.somewhere.ui.state.NodeEntry
import eu.nodepass.somewhere.ui.state.SampleState
import eu.nodepass.somewhere.ui.state.SessionSnapshot
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/**
 * Home — the node, the two directions, and the one action.
 *
 * The screen is organised around the fact that **upstream and downstream are
 * separate things**. Every other proxy client shows one throughput number,
 * because for every other protocol there is only one path; Nowhere can put the
 * two directions on different carriers, so one number here would be an average
 * of two unrelated measurements.
 */
@Composable
fun HomeScreen(
    onOpenNodes: () -> Unit,
    onOpenSettings: () -> Unit,
    node: NodeEntry = SampleState.frankfurt,
    session: SessionSnapshot = SampleState.session,
) {
    val colors = SomewhereTheme.colors
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 52.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SectionLabel(
                        stringResource(
                            if (session.connected) R.string.home_connected_to else R.string.home_not_connected,
                        ),
                    )
                    Text(
                        text = node.displayName,
                        fontFamily = SomewhereType.Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = (-0.2).sp,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenNodes),
                    )
                }
                IconSquare(
                    icon = SomewhereIcons.MoreVertical,
                    contentDescription = stringResource(R.string.home_node_options),
                    onClick = onOpenSettings,
                )
            }

            if (!node.url.certificateVerification.isVerified) {
                UnverifiedBanner()
            }
        }

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DirectionCard(
                label = stringResource(R.string.direction_upstream),
                icon = SomewhereIcons.ArrowUp,
                color = colors.upstream,
                tint = colors.upstreamTint,
                carrier = carrierLabel(node.url.up, node.url.mux),
                bytesPerSecond = session.upstreamBytesPerSecond,
                ofPeak = session.upstreamOfPeak,
            )
            DirectionCard(
                label = stringResource(R.string.direction_downstream),
                icon = SomewhereIcons.ArrowDown,
                color = colors.downstream,
                tint = colors.downstreamTint,
                carrier = carrierLabel(node.url.down, node.url.mux),
                bytesPerSecond = session.downstreamBytesPerSecond,
                ofPeak = session.downstreamOfPeak,
            )
        }

        SessionFacts(session, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(if (session.connected) colors.good else colors.inactive)
                MonoText(
                    text =
                        buildString {
                            append(
                                stringResource(
                                    if (session.connected) {
                                        R.string.home_state_connected
                                    } else {
                                        R.string.home_state_disconnected
                                    },
                                ).uppercase(),
                            )
                            if (session.connected) {
                                append(" · ")
                                append(Format.elapsed(session.connectedSeconds))
                            }
                        },
                    color = if (session.connected) colors.good else colors.faint,
                    fontSize = 12.sp,
                )
            }
            PrimaryAction(connected = session.connected, onClick = onOpenNodes)
        }
    }
}

/**
 * The certificate marker.
 *
 * D-11 / NW-P-09. Persistent and non-dismissible, because the condition persists
 * for as long as the node does — and because every URL NowhereDash currently
 * generates lands here. The two parameter names are arguments rather than part
 * of the sentence, so a translator is never offered the chance to localise them.
 */
@Composable
private fun UnverifiedBanner() {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.criticalTint)
                .border(1.dp, colors.criticalLine, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(SomewhereIcons.AlertTriangle, null, Modifier.size(15.dp), tint = colors.critical)
        // One flowing sentence, two tones: the statement in the critical colour,
        // the reason in ordinary ink. Two stacked Texts would fix a line break
        // that the two Chinese translations do not want in the same place.
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.critical)) {
                        append(stringResource(R.string.cert_unverified_short))
                    }
                    withStyle(SpanStyle(color = colors.inkMuted)) {
                        append(" · ")
                        append(stringResource(R.string.cert_unverified_reason, "sni", "pin"))
                    }
                },
            fontFamily = SomewhereType.Body,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun DirectionCard(
    label: String,
    icon: ImageVector,
    color: Color,
    tint: Color,
    carrier: String,
    bytesPerSecond: Long,
    ofPeak: Float,
) {
    val colors = SomewhereTheme.colors
    val measured = Format.throughput(bytesPerSecond)
    Card(
        padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, null, Modifier.size(15.dp), tint = color)
                SectionLabel(label, color = color)
            }
            MonoText(carrier, colors.muted, fontSize = 10.5.sp, weight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = measured.value,
                fontFamily = SomewhereType.Mono,
                fontWeight = FontWeight.Medium,
                fontSize = 33.sp,
                lineHeight = 33.sp,
                color = colors.ink,
            )
            Text(
                text = measured.unit,
                fontFamily = SomewhereType.Mono,
                fontSize = 13.sp,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Meter(fraction = ofPeak, color = color, track = tint)
    }
}

/**
 * Active flows, session total, handshake.
 *
 * All three are monospaced with tabular figures: a value that updates in place
 * must not shift the layout under the reader's eyes.
 */
@Composable
private fun SessionFacts(
    session: SessionSnapshot,
    modifier: Modifier = Modifier,
) {
    val colors = SomewhereTheme.colors
    val sessionBytes = Format.bytes(session.sessionBytes)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.line)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Fact(Modifier.weight(1f), session.activeFlows.toString(), null, stringResource(R.string.home_stat_active_flows))
        Fact(Modifier.weight(1f), sessionBytes.value, sessionBytes.unit, stringResource(R.string.home_stat_session))
        Fact(
            Modifier.weight(1f),
            session.handshakeMillis.toString(),
            "ms",
            stringResource(R.string.home_stat_handshake),
        )
    }
}

@Composable
private fun Fact(
    modifier: Modifier,
    value: String,
    unit: String?,
    label: String,
) {
    val colors = SomewhereTheme.colors
    Column(
        modifier
            .background(colors.panel)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontFamily = SomewhereType.Mono,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = colors.ink,
            )
            if (unit != null) {
                Text(
                    text = " $unit",
                    fontFamily = SomewhereType.Mono,
                    fontSize = 11.sp,
                    color = colors.muted,
                )
            }
        }
        Text(
            text = label,
            fontFamily = SomewhereType.Body,
            fontSize = 10.5.sp,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one primary action.
 *
 * Tinted on dark, solid on light — the design system's one deliberate
 * theme-dependent treatment: a tinted fill is the loudest thing on a dark
 * screen, and the same treatment on white reads as *disabled*.
 */
@Composable
private fun PrimaryAction(
    connected: Boolean,
    onClick: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.primaryAction)
                .border(1.dp, colors.primaryActionLine, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(SomewhereIcons.Power, null, Modifier.size(19.dp), tint = colors.onPrimaryAction)
        Text(
            text = stringResource(if (connected) R.string.action_disconnect else R.string.action_connect),
            fontFamily = SomewhereType.Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = colors.onPrimaryAction,
        )
    }
}

/**
 * What a direction is actually riding on.
 *
 * Never translated: these are carrier names, and a user comparing their screen
 * with the Portal's configuration has to see the same word.
 */
fun carrierLabel(
    carrier: NextHopCarrier,
    mux: Boolean,
): String =
    when (carrier) {
        NextHopCarrier.Tcp -> if (mux) "TLS / TCP · MUX" else "TLS / TCP"
        NextHopCarrier.Udp -> "QUIC"
    }

/** `UP TCP`, `DOWN UDP` — the chip form, for the node list. */
fun directionChip(
    prefix: String,
    carrier: NextHopCarrier,
): String = "$prefix ${carrier.token.uppercase()}"

/** Kept next to [carrierLabel] so the two cannot disagree about a node. */
val NowhereUrl.needsQuicNotice: Boolean get() = requiresQuic

@Composable
internal fun DividerBox(color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
