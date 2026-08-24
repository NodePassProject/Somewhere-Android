// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.ProbeResult
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.IconSquare
import eu.nodepass.somewhere.ui.components.Meter
import eu.nodepass.somewhere.ui.components.MonoChip
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.SmallButton
import eu.nodepass.somewhere.ui.components.StatusDot
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.state.Format
import eu.nodepass.somewhere.ui.state.NodeEntry
import eu.nodepass.somewhere.ui.state.NodeStatus
import eu.nodepass.somewhere.ui.state.SampleState
import eu.nodepass.somewhere.ui.state.SubscriptionState
import eu.nodepass.somewhere.ui.state.asMessage
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The node list, and what the dashboard said about the account.
 *
 * This screen carries more state colour than any other — good, warn, critical
 * and both direction hues in one column — which is why it is the screen the
 * light palette was checked against first.
 */
@Composable
fun NodesScreen(
    nodes: NodeRepository,
    onAdd: () -> Unit,
    onEdit: (NodeEntry) -> Unit,
) {
    val stored by nodes.nodes.collectAsState()
    val probes by nodes.probes.collectAsState()
    val scope = rememberCoroutineScope()

    val entries =
        stored.map { entry ->
            NodeEntry(entry.url, probes[entry.url.toUrl()].toStatus())
        }

    // Probing on arrival rather than behind a button: a list of nodes with no
    // indication of which ones work is the state this screen exists to leave.
    // Only nodes never probed in this process are dialled, so returning to the
    // tab does not re-dial the whole list.
    LaunchedEffect(stored) {
        stored.forEach { entry ->
            if (probes[entry.url.toUrl()] == null) {
                scope.launch { nodes.probe(entry.url) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 52.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tab_nodes),
                style = SomewhereType.screenTitle,
                color = SomewhereTheme.colors.ink,
                modifier = Modifier.weight(1f),
            )
            IconSquare(
                icon = SomewhereIcons.Plus,
                contentDescription = stringResource(R.string.nodes_add),
                onClick = onAdd,
                tint = SomewhereTheme.colors.upstream,
                background = SomewhereTheme.colors.upstreamTint,
                borderColor = SomewhereTheme.colors.upstreamLine,
            )
        }

        if (entries.isEmpty()) {
            EmptyNodeList(onAdd)
            return@Column
        }

        NodeList(entries, subscription = null, onEdit = onEdit)
    }
}

/**
 * The list itself, separated from its data source so a preview can render the
 * populated design without a repository behind it.
 */
@Composable
private fun NodeList(
    entries: List<NodeEntry>,
    subscription: SubscriptionState?,
    onEdit: (NodeEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (subscription != null) {
            item { SubscriptionHeader(subscription) }
            item { SubscriptionCard(subscription) }
        }
        item {
            Box(Modifier.padding(top = if (subscription != null) 8.dp else 0.dp)) {
                SectionLabel(pluralStringResource(R.plurals.nodes_count, entries.size, entries.size))
            }
        }
        items(entries, key = { it.url.toUrl() }) { node -> NodeCard(node, onClick = { onEdit(node) }) }
    }
}

/**
 * No nodes yet.
 *
 * The design canvas has no artboard for this — every screen there is drawn
 * populated — but it is the first screen a new install actually shows, so it is
 * built from the same system rather than left as a blank column.
 */
@Composable
private fun EmptyNodeList(onAdd: () -> Unit) {
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
            text = stringResource(R.string.nodes_empty_title),
            style = SomewhereType.rowHeading,
            color = colors.inkMuted,
        )
        Text(
            text = stringResource(R.string.nodes_empty_detail),
            style = SomewhereType.bodySmall,
            color = colors.faint,
            textAlign = TextAlign.Center,
        )
        SmallButton(
            label = stringResource(R.string.nodes_add),
            onClick = onAdd,
            fill = colors.primaryAction,
            contentColor = colors.onPrimaryAction,
            height = 40.dp,
        )
    }
}

/** A probe result as the list's own vocabulary. Never probed is not a failure. */
private fun ProbeResult?.toStatus(): NodeStatus =
    when (this) {
        null -> NodeStatus.Unknown
        ProbeResult.Probing -> NodeStatus.Probing
        is ProbeResult.Reachable -> NodeStatus.Reachable(handshakeMillis)
        is ProbeResult.Unreachable -> NodeStatus.Unreachable(reason)
    }

@Composable
private fun SubscriptionHeader(subscription: SubscriptionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(stringResource(R.string.nodes_subscription), Modifier.weight(1f))
        MonoText(
            text =
                pluralStringResource(
                    R.plurals.nodes_refreshed_minutes,
                    subscription.refreshedMinutesAgo,
                    subscription.refreshedMinutesAgo,
                ),
            color = SomewhereTheme.colors.faint,
            fontSize = 10.5.sp,
        )
    }
}

/**
 * Quota, worded the only way it can honestly be worded.
 *
 * NW-D-05: metering is per Portal, not per user, so two subscriptions sharing a
 * Portal are each charged the full amount. The word is **counted**, never
 * "used", and the note underneath says why rather than presenting a figure as
 * an exact bill. NW-D-02: there is no upload row, because upstream does not
 * meter upload — a permanent zero rendered as a measurement is a lie about what
 * the app knows.
 */
@Composable
private fun SubscriptionCard(subscription: SubscriptionState) {
    val colors = SomewhereTheme.colors
    val usage = subscription.usage
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = subscription.title,
                fontFamily = SomewhereType.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            usage.expiresAtEpochSeconds?.let { epoch ->
                MonoText(
                    text = stringResource(R.string.nodes_expires, epoch.asIsoDate()),
                    color = colors.muted,
                    fontSize = 11.sp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Meter(
                fraction = usage.fractionCounted?.toFloat() ?: 0f,
                color = colors.upstream,
                track = colors.surfaceAlt,
                height = 5.dp,
            )
            Text(
                text = quotaText(),
                fontFamily = SomewhereType.Mono,
                fontSize = 12.sp,
                color = colors.faint,
            )
            Text(
                text = stringResource(R.string.quota_per_portal_note),
                fontFamily = SomewhereType.Body,
                fontSize = 11.sp,
                lineHeight = 15.4.sp,
                color = colors.faint,
            )
        }
    }
}

/**
 * Two tones out of one string.
 *
 * `docs/i18n.md` forbids assembling a sentence from fragments, so the counted
 * figure cannot be its own `<string>` with a suffix beside it — Chinese puts the
 * two in the other order. Instead the whole sentence is formatted first and the
 * substituted value is located in the result, which works whatever order the
 * translation puts it in.
 */
@Composable
private fun quotaText(): AnnotatedString {
    val colors = SomewhereTheme.colors
    val usage = SampleState.subscription.usage
    val counted = Format.bytesText(usage.downloadBytes)
    val total = usage.totalBytes?.let { Format.bytesText(it) }.orEmpty()
    val full = stringResource(R.string.quota_counted, counted, total)
    val start = full.indexOf(counted)
    return buildAnnotatedString {
        append(full)
        if (start >= 0) {
            addStyle(SpanStyle(color = colors.inkMuted), start, start + counted.length)
        }
    }
}

@Composable
private fun NodeCard(
    node: NodeEntry,
    onClick: () -> Unit,
) {
    val colors = SomewhereTheme.colors

    if (node.status is NodeStatus.RemovedByProvider) {
        // NW-D-04: the dashboard drops nodes when a subscription lapses. The
        // node stays visible and says which it was — expiry or quota — because
        // "network error" and an empty list are both wrong.
        Card(
            modifier = Modifier.alpha(0.55f),
            padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(colors.inactive)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = node.displayName,
                        fontFamily = SomewhereType.Display,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                        color = colors.muted,
                    )
                    Text(
                        text = stringResource(R.string.quota_exhausted),
                        fontFamily = SomewhereType.Body,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = colors.critical,
                    )
                }
            }
        }
        return
    }

    val active = node.status is NodeStatus.Reachable
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        borderColor = if (active) colors.upstreamLine else colors.line,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(
                when (node.status) {
                    is NodeStatus.Reachable -> colors.good
                    is NodeStatus.Unreachable -> colors.critical
                    else -> colors.inactive
                },
            )
            Text(
                text = node.displayName,
                fontFamily = SomewhereType.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp,
                color = if (active) colors.ink else colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            MonoText(
                text =
                    when (val status = node.status) {
                        is NodeStatus.Reachable -> "${status.handshakeMillis} ms"
                        NodeStatus.Probing -> "…"
                        else -> "—"
                    },
                color =
                    when (node.status) {
                        is NodeStatus.Reachable -> colors.good
                        is NodeStatus.Unreachable -> colors.critical
                        else -> colors.faint
                    },
                fontSize = 11.5.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CarrierChip(directionChip("UP", node.url.up), node.url.up.token == "tcp")
            CarrierChip(directionChip("DOWN", node.url.down), node.url.down.token == "tcp")
            if (node.url.mux) {
                MonoChip("MUX", colors.muted, colors.surfaceAlt)
            }
            if (!node.url.certificateVerification.isVerified) {
                MonoChip(
                    text = stringResource(R.string.chip_unverified).uppercase(),
                    foreground = colors.critical,
                    background = colors.criticalTint,
                    leading = {
                        Icon(SomewhereIcons.AlertTriangle, null, Modifier.size(10.dp), tint = colors.critical)
                    },
                )
            }
        }

        (node.status as? NodeStatus.Unreachable)?.let { unreachable ->
            // The dialer's own reason, not "offline". It distinguishes a wrong
            // port from a refused ALPN from a pin that did not match, and those
            // are three different things for the person to go and fix.
            Text(
                text = unreachable.reason.asMessage(),
                fontFamily = SomewhereType.Body,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = colors.critical,
            )
        }

        if (node.url.requiresQuic) {
            NeedsQuicNotice()
        }
    }
}

/** Upstream is teal, downstream is amber — everywhere, including in a chip. */
@Composable
private fun CarrierChip(
    text: String,
    upstreamHue: Boolean,
) {
    val colors = SomewhereTheme.colors
    MonoChip(
        text = text,
        foreground = if (upstreamHue) colors.upstream else colors.downstream,
        background = if (upstreamHue) colors.upstreamTint else colors.downstreamTint,
    )
}

/**
 * NW-P-25, drawn.
 *
 * Upstream defaults both directions to `udp`, so a pasted default configuration
 * lands here. Two buttons, never one: rewriting the user's configuration on
 * their behalf is exactly what the requirement forbids.
 */
@Composable
private fun NeedsQuicNotice() {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.downstreamTint)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            SomewhereIcons.AlertCircle,
            null,
            Modifier
                .padding(top = 1.dp)
                .size(14.dp),
            tint = colors.warn,
        )
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = stringResource(R.string.node_needs_quic),
                fontFamily = SomewhereType.Body,
                fontSize = 12.sp,
                lineHeight = 16.8.sp,
                color = colors.warn,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallButton(
                    label = stringResource(R.string.node_switch_to_tcp),
                    onClick = {},
                    fill = colors.warnAction,
                    contentColor = colors.onWarnAction,
                )
                Box(
                    Modifier
                        .height(30.dp)
                        .clickable {}
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.node_keep_as_is),
                        style = SomewhereType.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

private fun Long.asIsoDate(): String = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(this).atZone(ZoneId.systemDefault()))
