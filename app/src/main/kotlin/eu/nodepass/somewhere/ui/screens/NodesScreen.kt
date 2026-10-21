// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.AppSettings
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.NodeSort
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.data.ProbeResult
import eu.nodepass.somewhere.data.SubscriptionStore
import eu.nodepass.somewhere.nodes.NodeChoice
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.subscription.SubscriptionUsage
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.IconSquare
import eu.nodepass.somewhere.ui.components.Meter
import eu.nodepass.somewhere.ui.components.MonoChip
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.components.SmallButton
import eu.nodepass.somewhere.ui.components.StatusDot
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.state.Format
import eu.nodepass.somewhere.ui.state.NodeEntry
import eu.nodepass.somewhere.ui.state.NodeOrdering
import eu.nodepass.somewhere.ui.state.NodeStatus
import eu.nodepass.somewhere.ui.state.SubscriptionState
import eu.nodepass.somewhere.ui.state.asMessage
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
    onChoose: (NodeChoice) -> Unit = {},
    settings: AppSettings = AppSettings(),
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val stored by nodes.nodes.collectAsState()
    val probes by nodes.probes.collectAsState()
    val choice by nodes.choice.collectAsState()
    val health by nodes.healthChanges.collectAsState()
    val records by nodes.subscriptionList.collectAsState()
    val refreshFailures by nodes.refreshFailures.collectAsState()
    val refreshing by nodes.refreshing.collectAsState()
    var removing by remember { mutableStateOf<String?>(null) }

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
    LaunchedEffect(stored) { nodes.probeUnprobedInBackground() }

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
            if (stored.isNotEmpty()) {
                IconSquare(
                    icon = SomewhereIcons.Zap,
                    contentDescription = stringResource(R.string.nodes_test_all),
                    onClick = nodes::probeAllInBackground,
                )
            }
            IconSquare(
                icon = SomewhereIcons.Plus,
                contentDescription = stringResource(R.string.nodes_add),
                onClick = onAdd,
                tint = SomewhereTheme.colors.brand,
                background = SomewhereTheme.colors.brandTint,
                borderColor = SomewhereTheme.colors.brandLine,
            )
        }

        // Empty only when there is nothing at all. A subscription whose first
        // fetch failed has no nodes yet, and hiding it behind "no nodes" would
        // hide the one card that says why.
        if (entries.isEmpty() && records.isEmpty()) {
            EmptyNodeList(onAdd)
            return@Column
        }

        val effective = remember(stored, choice) { nodes.effectiveChoice() }
        val shown =
            NodeOrdering.arrange(
                entries,
                sort = settings.nodeSort,
                hideUnreachable = settings.hideUnreachable,
                chosen = (effective as? NodeChoice.Chosen)?.node,
            )
        NodeList(
            entries = shown,
            total = entries.size,
            sort = settings.nodeSort,
            hideUnreachable = settings.hideUnreachable,
            onSort = { sort -> onSettingsChange { it.copy(nodeSort = sort) } },
            onHideUnreachable = { hide -> onSettingsChange { it.copy(hideUnreachable = hide) } },
            choice = effective,
            automaticTarget =
                remember(stored, probes, health) {
                    nodes.automaticTarget()?.let { NodeEntry(it.url).displayName }
                },
            onChoose = onChoose,
            // A refresh that failed has to say so, on the card of the
            // subscription that failed. It was once kept in the repository and
            // shown nowhere, which meant a subscription that could not be
            // reached looked exactly like one that had nothing to say.
            subscriptions =
                records.map { record ->
                    record.asState(clock = System::currentTimeMillis).copy(
                        refreshing = record.id in refreshing,
                        failure = refreshFailures[record.id],
                    )
                },
            onRefresh = nodes::refreshInBackground,
            onRemove = { removing = it },
            onEdit = onEdit,
        )
    }

    removing?.let { id ->
        RemoveSubscriptionDialog(
            onConfirm = {
                nodes.removeSubscription(id)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * Removing a subscription takes its nodes with it, so it asks first.
 *
 * The one destructive action on this screen that cannot be undone by
 * importing the same thing again: the subscription URL is a credential, and
 * once forgotten the user has to go back to the dashboard for it.
 */
@Composable
private fun RemoveSubscriptionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        // The design system's radius for a surface that sits over the
        // screen, not Material's 28: nothing here is fully rounded.
        shape = RoundedCornerShape(14.dp),
        title = { Text(stringResource(R.string.subscription_remove_title), style = SomewhereType.rowHeading, color = colors.ink) },
        text = { Text(stringResource(R.string.subscription_remove_detail), style = SomewhereType.bodySmall, color = colors.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.subscription_remove), color = colors.critical) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = colors.inkMuted) }
        },
    )
}

/**
 * The list itself, separated from its data source so a preview — and the
 * design-rule instrumentation suite — can render the populated design without a
 * repository behind it.
 */
@Composable
internal fun NodeList(
    entries: List<NodeEntry>,
    /** How many nodes there are before any are hidden. */
    total: Int = entries.size,
    sort: NodeSort = NodeSort.ListOrder,
    hideUnreachable: Boolean = false,
    onSort: (NodeSort) -> Unit = {},
    onHideUnreachable: (Boolean) -> Unit = {},
    choice: NodeChoice = NodeChoice.Automatic,
    /** The display name automatic selection would use now, or null if none. */
    automaticTarget: String? = null,
    onChoose: (NodeChoice) -> Unit = {},
    subscriptions: List<SubscriptionState> = emptyList(),
    onRefresh: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onEdit: (NodeEntry) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (subscriptions.isNotEmpty()) {
            item(key = "subscriptions") { SectionLabel(stringResource(R.string.nodes_subscription)) }
            items(subscriptions, key = { "subscription:${it.id}" }) { subscription ->
                SubscriptionCard(
                    subscription = subscription,
                    onRefresh = { onRefresh(subscription.id) },
                    onRemove = { onRemove(subscription.id) },
                )
            }
        }
        item(key = "controls") {
            ListControls(
                total = total,
                hidden = total - entries.size,
                sort = sort,
                hideUnreachable = hideUnreachable,
                onSort = onSort,
                onHideUnreachable = onHideUnreachable,
                modifier = Modifier.padding(top = if (subscriptions.isNotEmpty()) 8.dp else 0.dp),
            )
        }
        item(key = "automatic") {
            AutomaticCard(
                selected = choice == NodeChoice.Automatic,
                target = automaticTarget,
                onClick = { onChoose(NodeChoice.Automatic) },
            )
        }
        items(entries, key = { it.url.toUrl() }) { node ->
            NodeCard(
                node = node,
                selected = choice == NodeChoice.Chosen(node.url.toUrl()),
                onChoose = { onChoose(NodeChoice.Chosen(node.url.toUrl())) },
                onEdit = { onEdit(node) },
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
private fun SubscriptionStore.Record.asState(clock: () -> Long): SubscriptionState {
    val minutes = fetchedAtEpochMillis?.let { ((clock() - it) / 60_000L).coerceAtLeast(0).toInt() }
    return SubscriptionState(
        // The dashboard's title, or its host. Never the URL: the URL is the
        // credential, and the host is all a person needs to tell two apart.
        title = title ?: hostOf(url),
        usage = usage,
        refreshedMinutesAgo = minutes,
        id = id,
    )
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

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

/**
 * One subscription: its name, how old its figures are, and its quota.
 *
 * Quota is worded the only way it can honestly be worded. NW-D-05: metering is
 * per Portal, not per user, so two subscriptions sharing a Portal are each
 * charged the full amount. The word is **counted**, never "used", and the note
 * underneath says why rather than presenting a figure as an exact bill.
 * NW-D-02: there is no upload row, because upstream does not meter upload — a
 * permanent zero rendered as a measurement is a lie about what the app knows.
 *
 * The age of the figures is the refresh control. It already says how old they
 * are, so the affordance sits on the thing it acts on.
 */
@Composable
private fun SubscriptionCard(
    subscription: SubscriptionState,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    val usage = subscription.usage
    // The label a screen reader announces, which is the action rather than the
    // age — "refreshed 4 min ago" describes what it says, not what it does.
    val refreshLabel = stringResource(R.string.nodes_refresh_now)
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
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
            MonoText(
                text =
                    when {
                        subscription.refreshing -> stringResource(R.string.nodes_refreshing)
                        subscription.refreshedMinutesAgo == null -> stringResource(R.string.subscription_never_fetched)
                        else ->
                            pluralStringResource(
                                R.plurals.nodes_refreshed_minutes,
                                subscription.refreshedMinutesAgo,
                                subscription.refreshedMinutesAgo,
                            )
                    },
                color = if (subscription.refreshing) colors.brand else colors.faint,
                fontSize = 10.5.sp,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !subscription.refreshing, onClick = onRefresh)
                        .semantics { contentDescription = refreshLabel }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (usage != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Meter(
                    fraction = usage.fractionCounted?.toFloat() ?: 0f,
                    color = colors.brand,
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
        subscription.failure?.let { RefreshFailure(it.asMessage()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            usage?.expiresAtEpochSeconds?.let { epoch ->
                MonoText(
                    text = stringResource(R.string.nodes_expires, epoch.asIsoDate()),
                    color = colors.muted,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.subscription_remove),
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = colors.critical,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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

/**
 * The count, the order, and the filter.
 *
 * The count is of every node, not of the ones showing: a list that says "3
 * nodes" while the user has five, two of them hidden, is the list contradicting
 * its owner. The number hidden is said beside the switch that hid them.
 */
@Composable
private fun ListControls(
    total: Int,
    hidden: Int,
    sort: NodeSort,
    hideUnreachable: Boolean,
    onSort: (NodeSort) -> Unit,
    onHideUnreachable: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SomewhereTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(pluralStringResource(R.plurals.nodes_count, total, total), Modifier.weight(1f))
            Text(
                text =
                    if (hideUnreachable && hidden > 0) {
                        stringResource(R.string.nodes_hidden, hidden)
                    } else {
                        stringResource(R.string.nodes_hide_unreachable)
                    },
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = if (hideUnreachable) colors.brand else colors.faint,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(value = hideUnreachable, role = Role.Switch, onValueChange = onHideUnreachable)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        val sorts = NodeSort.entries
        Segmented(
            options =
                sorts.map {
                    stringResource(
                        when (it) {
                            NodeSort.ListOrder -> R.string.nodes_sort_list
                            NodeSort.Latency -> R.string.nodes_sort_latency
                            NodeSort.Name -> R.string.nodes_sort_name
                        },
                    )
                },
            selectedIndex = sorts.indexOf(sort),
            onSelect = { onSort(sorts[it]) },
            height = 30.dp,
        )
    }
}

/**
 * The choice that hands the decision to the client.
 *
 * It names the node it would use now, because "automatic" on its own is a
 * promise with nothing behind it: the user should be able to see where their
 * traffic would go before they tap.
 */
@Composable
private fun AutomaticCard(
    selected: Boolean,
    target: String?,
    onClick: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    val selectedLabel = stringResource(R.string.nodes_selected)
    Card(
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .then(if (selected) Modifier.semantics { stateDescription = selectedLabel } else Modifier),
        borderColor = if (selected) colors.brandLine else colors.line,
        background = if (selected) colors.brandTint else colors.surface,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.nodes_automatic),
                fontFamily = SomewhereType.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (selected) Icon(SomewhereIcons.Check, null, Modifier.size(16.dp), tint = colors.brand)
        }
        Text(
            text =
                if (target != null) {
                    stringResource(R.string.nodes_automatic_detail, target)
                } else {
                    stringResource(R.string.nodes_automatic_none)
                },
            fontFamily = SomewhereType.Body,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = colors.muted,
        )
    }
}

@Composable
private fun NodeCard(
    node: NodeEntry,
    selected: Boolean,
    onChoose: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    val selectedLabel = stringResource(R.string.nodes_selected)

    if (node.status is NodeStatus.RemovedByProvider) {
        // NW-D-04: the dashboard drops nodes when a subscription lapses. The
        // node stays visible and says which it was — expiry or quota — because
        // "network error" and an empty list are both wrong.
        //
        // Tapping it edits rather than selects: the provider has withdrawn it,
        // and the one useful thing left to do with it is remove it.
        Card(
            modifier = Modifier.alpha(0.55f).clickable(onClick = onEdit),
            padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(colors.inactive)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
        modifier =
            Modifier
                .clickable(onClick = onChoose)
                .then(if (selected) Modifier.semantics { stateDescription = selectedLabel } else Modifier),
        // The selection outranks reachability for the border: which node is
        // chosen is the question this list answers first, and the status dot
        // and latency still say whether it answers.
        borderColor =
            when {
                selected -> colors.brandLine
                active -> colors.goodLine
                else -> colors.line
            },
        background = if (selected) colors.brandTint else colors.surface,
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
            if (selected) Icon(SomewhereIcons.Check, null, Modifier.size(16.dp), tint = colors.brand)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // One carrier for both directions, so one neutral chip: the
            // direction hues belong to the two channels on the home screen, and
            // a chip that was neither would borrow one of them.
            MonoChip(carrierLabel(node.url.net), colors.muted, colors.surfaceAlt)
            if (node.url.morph) {
                MonoChip("MORPH", colors.muted, colors.surfaceAlt)
            }
            if (node.url.certificateVerification is CertificateVerification.Pin) {
                MonoChip("PIN", colors.muted, colors.surfaceAlt)
            }
            Spacer(Modifier.weight(1f))
            EditButton(onEdit)
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
    }
}

private fun Long.asIsoDate(): String = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(this).atZone(ZoneId.systemDefault()))

/**
 * Opens the editor. Its own target, because tapping the card selects.
 *
 * Text rather than an icon: a pencil glyph is one more thing to learn, and
 * "Edit" says the one thing it does in every locale this app ships.
 */
@Composable
private fun EditButton(onClick: () -> Unit) {
    val colors = SomewhereTheme.colors
    Text(
        text = stringResource(R.string.action_edit),
        fontFamily = SomewhereType.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = colors.brand,
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
