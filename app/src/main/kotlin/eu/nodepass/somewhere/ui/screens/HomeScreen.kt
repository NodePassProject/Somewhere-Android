// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import android.os.SystemClock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.IconSquare
import eu.nodepass.somewhere.ui.components.Meter
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.SmallButton
import eu.nodepass.somewhere.ui.components.StatusDot
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.state.Format
import eu.nodepass.somewhere.ui.state.NodeEntry
import eu.nodepass.somewhere.ui.state.SessionSnapshot
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import eu.nodepass.somewhere.ui.theme.direction
import eu.nodepass.somewhere.vpn.TunnelController
import eu.nodepass.somewhere.vpn.TunnelState
import kotlinx.coroutines.delay

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
    nodes: NodeRepository,
    onOpenNodes: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTunnel: (NowhereUrl) -> Unit = {},
) {
    val stored by nodes.nodes.collectAsState()
    val first = stored.firstOrNull()

    if (first == null) {
        NoNodeYet(onOpenNodes)
        return
    }

    val tunnel by TunnelController.state.collectAsState()

    // Re-read once a second: a duration that only advances when something else
    // recomposes is a stopped clock that looks like a running one. It is kept
    // separate from the traffic reading below because it is a different kind of
    // fact — how long the tunnel has been up is known the moment it comes up,
    // while a rate needs an interval before it exists at all.
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tunnel) {
        val connected = tunnel as? TunnelState.Connected
        if (connected == null) {
            elapsedSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - connected.sinceElapsedRealtime) / 1000
            delay(1_000)
        }
    }

    // Both figures are measured now. `measured` still comes from the meter
    // rather than from `connected`, because a tunnel can be up before it has
    // carried anything, and a rate needs an interval before it is a rate at
    // all — until then every figure renders as an em dash rather than as zero,
    // which would claim a measurement that was never taken.
    val traffic by TunnelController.traffic.collectAsState()

    Home(
        node = NodeEntry(first.url),
        session =
            SessionSnapshot.DISCONNECTED.copy(
                connected = tunnel is TunnelState.Connected,
                connecting = tunnel is TunnelState.Connecting,
                measured = traffic.measured,
                upstreamBytesPerSecond = traffic.upstreamBytesPerSecond,
                downstreamBytesPerSecond = traffic.downstreamBytesPerSecond,
                upstreamOfPeak = traffic.upstreamOfPeak,
                downstreamOfPeak = traffic.downstreamOfPeak,
                activeFlows = traffic.activeFlows,
                sessionBytes = traffic.totalBytes,
                connectedSeconds = elapsedSeconds,
            ),
        onOpenNodes = onOpenNodes,
        onOpenSettings = onOpenSettings,
        onToggleTunnel = { onToggleTunnel(first.url) },
    )
}

/** No node to connect to. The one thing to do from here is add one. */
@Composable
private fun NoNodeYet(onOpenNodes: () -> Unit) {
    val colors = SomewhereTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_no_node),
            style = SomewhereType.rowHeading,
            color = colors.inkMuted,
        )
        Text(
            text = stringResource(R.string.home_empty_detail),
            style = SomewhereType.bodySmall,
            color = colors.faint,
        )
        SmallButton(
            label = stringResource(R.string.nodes_add),
            onClick = onOpenNodes,
            fill = colors.primaryAction,
            contentColor = colors.onPrimaryAction,
            height = 40.dp,
        )
    }
}

/**
 * The populated home screen, separated from its data source so a preview can
 * render the design without a repository behind it.
 */
@Composable
internal fun Home(
    node: NodeEntry,
    session: SessionSnapshot,
    onOpenNodes: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTunnel: () -> Unit = {},
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
                color = colors.direction(upstream = true).figure,
                tint = colors.direction(upstream = true).tint,
                carrier = carrierLabel(node.url.up, node.url.mux),
                bytesPerSecond = session.upstreamBytesPerSecond.takeIf { session.measured },
                ofPeak = if (session.measured) session.upstreamOfPeak else 0f,
            )
            DirectionCard(
                label = stringResource(R.string.direction_downstream),
                icon = SomewhereIcons.ArrowDown,
                color = colors.direction(upstream = false).figure,
                tint = colors.direction(upstream = false).tint,
                carrier = carrierLabel(node.url.down, node.url.mux),
                bytesPerSecond = session.downstreamBytesPerSecond.takeIf { session.measured },
                ofPeak = if (session.measured) session.downstreamOfPeak else 0f,
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
            PrimaryAction(session = session, onClick = onToggleTunnel)
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
    /** Null when nothing has measured this direction yet — not the same as zero. */
    bytesPerSecond: Long?,
    ofPeak: Float,
) {
    val colors = SomewhereTheme.colors
    val reading = bytesPerSecond?.let(Format::throughput)
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
                text = reading?.value ?: "\u2014",
                fontFamily = SomewhereType.Mono,
                fontWeight = FontWeight.Medium,
                fontSize = 33.sp,
                lineHeight = 33.sp,
                color = if (reading == null) colors.faint else colors.ink,
            )
            Text(
                text = reading?.unit.orEmpty(),
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
    val unmeasured = "\u2014"
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.line)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Fact(
            Modifier.weight(1f),
            if (session.measured) session.activeFlows.toString() else unmeasured,
            null,
            stringResource(R.string.home_stat_active_flows),
        )
        Fact(
            Modifier.weight(1f),
            if (session.measured) sessionBytes.value else unmeasured,
            sessionBytes.unit.takeIf { session.measured },
            stringResource(R.string.home_stat_session),
        )
        Fact(
            Modifier.weight(1f),
            if (session.measured) session.handshakeMillis.toString() else unmeasured,
            "ms".takeIf { session.measured },
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
    session: SessionSnapshot,
    onClick: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    val label =
        when {
            session.connected -> R.string.action_disconnect
            session.connecting -> R.string.home_connecting
            else -> R.string.action_connect
        }
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
            text = stringResource(label),
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

/**
 * Whether a node cannot be carried as configured.
 *
 * This used to mean "needs QUIC", when QUIC was not implemented, and then "needs
 * certificate verification", when neither `sni` nor `pin` worked on it. It has
 * narrowed twice and now means one thing: **a QUIC node verifying the chain
 * against an `sni` name.** A bare `nowhere://key@host:port` connects, and so
 * does one carrying `pin`, which the QUIC carrier compares against the leaf the
 * peer presents. `sni` needs a trust store fed into the QUIC stack's TLS
 * backend, which is a different order of work, and carrying such a node without
 * it would be a security downgrade the user configured against and could not
 * observe.
 *
 * Kept next to [carrierLabel] so the two cannot disagree about a node.
 */
val NowhereUrl.needsQuicNotice: Boolean
    get() = requiresQuic && certificateVerification is CertificateVerification.Sni

@Composable
internal fun DividerBox(color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
