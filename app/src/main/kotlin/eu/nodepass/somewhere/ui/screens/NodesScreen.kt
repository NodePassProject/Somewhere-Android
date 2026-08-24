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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.data.ProbeResult
import eu.nodepass.somewhere.data.SubscriptionStore
import eu.nodepass.somewhere.subscription.SubscriptionUsage
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
import eu.nodepass.somewhere.ui.state.SubscriptionState
import eu.nodepass.somewhere.ui.state.asMessage
import eu.nodepass.somewhere.ui.state.switchedToTcp
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
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
    val record by nodes.subscription.collectAsState()
    val refreshFailure by nodes.lastRefreshFailure.collectAsState()
    val refreshing by nodes.refreshing.collectAsState()

    // Dismissals are deliberately not persisted. NW-P-25's "Keep as is" is the
    // user saying "not now", not "never" — and a node that still cannot connect
    // should say why again next time the list is opened rather than going quiet
    // forever on the strength of one tap.
    var dismissedNotices by remember { mutableStateOf(emptySet<String>()) }

    val entries =
        stored.map { entry ->
            // A node the feed has stopped listing is not a node that failed to
            // answer. NW-D-04: the reason is expiry or quota, and probing it
            // would produce a network failure that sends the reader off to
            // debug entirely the wrong thing.
            val status =
                if (entry.origin == NodeStore.Origin.RemovedFromFeed) {
                    NodeStatus.RemovedByProvider
                } else {
                    probes[entry.url.toUrl()].toStatus()
                }
            NodeEntry(url = entry.url, status = status)
        }

    // Probing on arrival rather than behind a button: a list of nodes with no
    // indication of which ones work is the state this screen exists to leave.
    // Only nodes never probed in this process are dialled, so returning to the
    // tab does not re-dial the whole list.
    LaunchedEffect(stored) {
        stored
            .filter { it.origin != NodeStore.Origin.RemovedFromFeed }
            .forEach { entry ->
                if (probes[entry.url.toUrl()] == null) {
                    nodes.probeInBackground(entry.url)
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

        NodeList(
            entries = entries,
            subscription = record?.asState(clock = System::currentTimeMillis),
            // A refresh that failed has to say so here. It was previously kept
            // in the repository and shown nowhere, which meant a subscription
            // that could not be reached looked exactly like one that had
            // nothing to say.
            refreshFailure = refreshFailure?.asMessage(),
            dismissedNotices = dismissedNotices,
            refreshing = refreshing,
            // The header already says how old the figures are. Making that the
            // control means the affordance sits on the thing it acts on, and
            // costs no new furniture on a screen the design drew without any.
            onRefresh = nodes::refreshInBackground,
            onEdit = onEdit,
            // NW-P-25: the rewrite happens here, on the user's instruction, and
            // nowhere else. The requirement is not that the app avoid changing
            // the configuration — it is that the app never changes it without
            // being told to.
            onSwitchToTcp = { entry -> nodes.replace(entry.url, entry.url.switchedToTcp()) },
            onKeepAsIs = { entry -> dismissedNotices = dismissedNotices + entry.url.toUrl() },
        )
    }
}

/**
 * The list itself, separated from its data source so a preview — and the
 * design-rule instrumentation suite — can render the populated design without a
 * repository behind it.
 */
@Composable
internal fun NodeList(
    entries: List<NodeEntry>,
    subscription: SubscriptionState? = null,
    refreshFailure: String? = null,
    dismissedNotices: Set<String> = emptySet(),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onEdit: (NodeEntry) -> Unit = {},
    onSwitchToTcp: (NodeEntry) -> Unit = {},
    onKeepAsIs: (NodeEntry) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (subscription != null) {
            item { SubscriptionHeader(subscription, refreshing, onRefresh) }
            item { SubscriptionCard(subscription) }
        }
        if (refreshFailure != null) {
            item { RefreshFailure(refreshFailure) }
        }
        item {
            Box(Modifier.padding(top = if (subscription != null) 8.dp else 0.dp)) {
                SectionLabel(pluralStringResource(R.plurals.nodes_count, entries.size, entries.size))
            }
        }
        items(entries, key = { it.url.toUrl() }) { node ->
            NodeCard(
                node = node,
                onClick = { onEdit(node) },
                onSwitchToTcp = { onSwitchToTcp(node) },
                onKeepAsIs = { onKeepAsIs(node) },
                dismissed = node.url.toUrl() in dismissedNotices,
            )
        }
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

/**
 * The stored subscription as the screen's own vocabulary.
 *
 * The age of the figures is carried, not dropped. A quota with its age attached
 * is a measurement; the same quota presented as current is a claim — and this
 * screen may well be showing numbers fetched before the device went offline.
 */
private fun SubscriptionStore.Record.asState(clock: () -> Long): SubscriptionState? {
    val usage = usage ?: return null
    val minutes = fetchedAtEpochMillis?.let { ((clock() - it) / 60_000L).coerceAtLeast(0).toInt() } ?: 0
    return SubscriptionState(
        title = title ?: "",
        usage = usage,
        refreshedMinutesAgo = minutes,
    )
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
private fun RefreshFailure(message: String) {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.criticalTint)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(SomewhereIcons.AlertTriangle, null, Modifier.size(15.dp), tint = colors.critical)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.subscription_refresh_failed),
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                color = colors.critical,
            )
            Text(
                text = message,
                fontFamily = SomewhereType.Body,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.inkMuted,
            )
        }
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    // The label a screen reader announces, which is the action rather than the
    // age — "refreshed 4 min ago" describes what it says, not what it does.
    val refreshLabel = stringResource(R.string.nodes_refresh_now)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(stringResource(R.string.nodes_subscription), Modifier.weight(1f))
        MonoText(
            text =
                if (refreshing) {
                    stringResource(R.string.nodes_refreshing)
                } else {
                    pluralStringResource(
                        R.plurals.nodes_refreshed_minutes,
                        subscription.refreshedMinutesAgo,
                        subscription.refreshedMinutesAgo,
                    )
                },
            color = if (refreshing) colors.upstream else colors.faint,
            fontSize = 10.5.sp,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !refreshing, onClick = onRefresh)
                    .semantics { contentDescription = refreshLabel }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                text = quotaText(usage),
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
private fun quotaText(usage: SubscriptionUsage): AnnotatedString {
    val colors = SomewhereTheme.colors
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
    onSwitchToTcp: () -> Unit = {},
    onKeepAsIs: () -> Unit = {},
    dismissed: Boolean = false,
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

        if (node.url.requiresQuic && !dismissed) {
            NeedsQuicNotice(
                onSwitchToTcp = onSwitchToTcp,
                onKeepAsIs = onKeepAsIs,
            )
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
private fun NeedsQuicNotice(
    onSwitchToTcp: () -> Unit,
    onKeepAsIs: () -> Unit,
) {
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
                    onClick = onSwitchToTcp,
                    fill = colors.warnAction,
                    contentColor = colors.onWarnAction,
                )
                Box(
                    Modifier
                        .height(30.dp)
                        .clickable(onClick = onKeepAsIs)
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
