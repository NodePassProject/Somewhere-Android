// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.MonoChip
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import eu.nodepass.somewhere.ui.state.Format
import eu.nodepass.somewhere.ui.state.LogSeverity
import eu.nodepass.somewhere.ui.state.explanation
import eu.nodepass.somewhere.ui.state.identifier
import eu.nodepass.somewhere.ui.state.severity
import eu.nodepass.somewhere.ui.theme.SomewhereColors
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import eu.nodepass.somewhere.ui.theme.direction
import eu.nodepass.somewhere.vpn.ConnectionRoute
import eu.nodepass.somewhere.vpn.DnsLogEntry
import eu.nodepass.somewhere.vpn.DnsOutcome
import eu.nodepass.somewhere.vpn.LiveConnection

/** The accent bar down the start edge of every log card. */
private val STRIPE_WIDTH = 2.dp

/** The three things the screen can show. */
enum class ActivitySurface {
    Connections,
    Dns,
    Portal,
}

/**
 * Activity: what the tunnel carries now, the names the device asked for, and
 * what the Portal answered.
 *
 * NW-P-06 is the whole design of the Portal surface: **every rejection, its
 * own message.** A client that renders them all as "connection failed" hides
 * the difference between a Portal at its flow limit and a target it could not
 * dial — which is exactly the information someone needs in order to act. The
 * identifier is drawn beside the sentence rather than inside it, so a
 * translator cannot localise `DIAL_FAILED` and break the one string a user can
 * usefully paste into an issue.
 */
@Composable
fun DiagnosticsScreen(
    entries: List<ConnectionLogEntry> = emptyList(),
    connections: List<LiveConnection> = emptyList(),
    dns: List<DnsLogEntry> = emptyList(),
    initial: ActivitySurface = ActivitySurface.Connections,
    /** The monotonic clock now, for each connection's age. */
    nowElapsedMillis: Long = 0L,
    onClose: (Long) -> Unit = {},
    onCloseAll: () -> Unit = {},
    /** Null hides the export action, for previews and tests. */
    onExport: (() -> Unit)? = null,
) {
    val colors = SomewhereTheme.colors
    var surface by remember { mutableStateOf(initial) }
    var confirmingExport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 52.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = SomewhereType.screenTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (onExport != null) {
                    TextAction(stringResource(R.string.diagnostics_export), colors.brand) { confirmingExport = true }
                }
            }
            // Three surfaces, so the split is a control rather than a filter
            // buried in a menu (NW-A-06). The second segment used to read
            // "Runtime" and showed nothing at all.
            Segmented(
                options =
                    listOf(
                        stringResource(R.string.diagnostics_connections),
                        stringResource(R.string.diagnostics_dns),
                        stringResource(R.string.diagnostics_portal),
                    ),
                selectedIndex = surface.ordinal,
                onSelect = { surface = ActivitySurface.entries[it] },
            )
        }

        when (surface) {
            ActivitySurface.Connections -> LiveConnections(connections, nowElapsedMillis, onClose, onCloseAll)
            ActivitySurface.Dns -> DnsQueries(dns)
            ActivitySurface.Portal -> PortalAnswers(entries)
        }
    }

    // Said before the share sheet, not after: the export is the user's
    // browsing, and the moment to know that is before it leaves the device.
    if (confirmingExport && onExport != null) {
        AlertDialog(
            onDismissRequest = { confirmingExport = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(14.dp),
            title = { Text(stringResource(R.string.diagnostics_export), style = SomewhereType.rowHeading, color = colors.ink) },
            text = { Text(stringResource(R.string.diagnostics_export_note), style = SomewhereType.bodySmall, color = colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingExport = false
                    onExport()
                }) { Text(stringResource(R.string.diagnostics_export), color = colors.brand) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingExport = false }) {
                    Text(stringResource(R.string.action_cancel), color = colors.inkMuted)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortalAnswers(entries: List<ConnectionLogEntry>) {
    val colors = SomewhereTheme.colors
    if (entries.isEmpty()) {
        // No session has run, so there is nothing to report. The screen said
        // so with fabricated log lines until now — seven plausible entries
        // with plausible timestamps, none of which had ever happened. That
        // is the same defect as rendering an unmeasured throughput: it is
        // not a placeholder, it is the app claiming something.
        NothingLogged(R.string.diagnostics_empty_title, R.string.diagnostics_empty_detail)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries) { entry -> LogCard(entry) }

        item {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel(stringResource(R.string.diagnostics_all_reasons), color = colors.faint)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    SetupResult.entries.filter { it.isRejection }.forEach { result ->
                        MonoChip(
                            text = result.identifier,
                            foreground = result.severity.foreground(colors),
                            background = result.severity.background(colors),
                            fontSize = 9.5.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the tunnel is carrying now.
 *
 * Each connection names where it goes, which app opened it, which way it left
 * — through the Portal or straight out — and what it has moved in each
 * direction, never summed. Closing one resets it for the app that opened it;
 * the tunnel stays up.
 */
@Composable
private fun LiveConnections(
    connections: List<LiveConnection>,
    nowElapsedMillis: Long,
    onClose: (Long) -> Unit,
    onCloseAll: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    if (connections.isEmpty()) {
        NothingLogged(R.string.live_empty_title, R.string.live_empty_detail)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    pluralStringResource(R.plurals.active_flows, connections.size, connections.size),
                    Modifier.weight(1f),
                )
                TextAction(stringResource(R.string.live_close_all), colors.critical, onCloseAll)
            }
        }
        items(connections, key = { it.id }) { connection ->
            Card(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoText(connection.target, colors.ink, Modifier.weight(1f), fontSize = 12.sp)
                    TextAction(stringResource(R.string.live_close), colors.critical) { onClose(connection.id) }
                }
                Text(
                    text = connection.app ?: stringResource(R.string.live_unknown_app),
                    fontFamily = SomewhereType.Body,
                    fontSize = 12.sp,
                    color = if (connection.app == null) colors.faint else colors.inkMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoChip(connection.protocol.name.uppercase(), colors.muted, colors.surfaceAlt, fontSize = 9.5.sp)
                    MonoChip(
                        connection.route.name.uppercase(),
                        if (connection.route == ConnectionRoute.Direct) colors.warn else colors.muted,
                        if (connection.route == ConnectionRoute.Direct) colors.warnTint else colors.surfaceAlt,
                        fontSize = 9.5.sp,
                    )
                    // Each direction in its own hue, as on Home: two
                    // measurements, never one.
                    MonoText(
                        "↑ " + Format.bytesText(connection.upstreamBytes),
                        colors.direction(upstream = true).figure,
                        fontSize = 10.5.sp,
                    )
                    MonoText(
                        "↓ " + Format.bytesText(connection.downstreamBytes),
                        colors.direction(upstream = false).figure,
                        fontSize = 10.5.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    MonoText(
                        Format.elapsed(((nowElapsedMillis - connection.openedAtElapsedMillis) / 1000).coerceAtLeast(0)),
                        colors.faint,
                        fontSize = 10.5.sp,
                    )
                }
            }
        }
    }
}

/** The names the device asked for, and what became of each. */
@Composable
private fun DnsQueries(entries: List<DnsLogEntry>) {
    val colors = SomewhereTheme.colors
    if (entries.isEmpty()) {
        NothingLogged(R.string.dns_empty_title, R.string.dns_empty_detail)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries) { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MonoText(entry.timestamp, colors.faint, fontSize = 10.5.sp)
                MonoText(entry.type.ifEmpty { "?" }, colors.muted, Modifier.width(44.dp), fontSize = 10.5.sp)
                MonoText(entry.name.ifEmpty { "?" }, colors.ink, Modifier.weight(1f), fontSize = 11.5.sp)
                // Machine identifiers, untranslated, so a pasted line matches
                // the export and someone else's.
                val tint =
                    when (entry.outcome) {
                        DnsOutcome.FakeIp -> colors.good
                        DnsOutcome.Failed -> colors.critical
                        else -> colors.muted
                    }
                MonoText(entry.outcome.identifier, tint, fontSize = 9.5.sp)
            }
        }
    }
}

@Composable
private fun TextAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontFamily = SomewhereType.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        color = color,
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun NothingLogged(
    title: Int,
    detail: Int,
) {
    val colors = SomewhereTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(title),
            style = SomewhereType.rowHeading,
            color = colors.inkMuted,
        )
        Text(
            text = stringResource(detail),
            style = SomewhereType.bodySmall,
            color = colors.faint,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LogCard(entry: ConnectionLogEntry) {
    val colors = SomewhereTheme.colors
    val accent = entry.result.severity.foreground(colors)

    // The severity stripe is drawn rather than laid out: a sibling Box with
    // fillMaxHeight inside a Row whose height comes from its own content
    // measures against an unbounded constraint and lands at zero. Painting it
    // behind the card takes the height the card actually ended up with.
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
            .background(colors.surface)
            .drawBehind {
                drawRect(color = accent, size = Size(STRIPE_WIDTH.toPx(), size.height))
            },
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MonoText(entry.result.identifier, accent, Modifier.weight(1f), fontSize = 11.sp)
                MonoText(entry.timestamp, colors.faint, fontSize = 10.5.sp)
            }
            Text(
                text =
                    when (entry.result) {
                        SetupResult.DialFailed ->
                            stringResource(entry.result.explanation, entry.target.orEmpty())
                        SetupResult.Ready ->
                            entry.setupMillis?.let {
                                stringResource(R.string.log_handshake_complete, "$it ms", entry.carrier.orEmpty())
                            } ?: stringResource(R.string.log_ready_unmeasured, entry.carrier.orEmpty())
                        else -> stringResource(entry.result.explanation)
                    },
                fontFamily = SomewhereType.Body,
                fontSize = 13.sp,
                lineHeight = 18.2.sp,
                fontWeight = FontWeight.Normal,
                color = if (entry.result == SetupResult.Ready) colors.inkMuted else colors.ink,
            )
            val tags = listOfNotNull(entry.flowId?.let { "flow $it" }, entry.carrier.takeIf { entry.result.isRejection })
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        MonoChip(tag, colors.muted, colors.surfaceAlt, fontSize = 9.5.sp)
                    }
                }
            }
        }
    }
}

private fun LogSeverity.foreground(colors: SomewhereColors): Color =
    when (this) {
        LogSeverity.Good -> colors.good
        LogSeverity.Warn -> colors.warn
        LogSeverity.Critical -> colors.critical
        LogSeverity.Neutral -> colors.muted
    }

private fun LogSeverity.background(colors: SomewhereColors): Color =
    when (this) {
        LogSeverity.Good -> colors.goodTint
        LogSeverity.Warn -> colors.warnTint
        LogSeverity.Critical -> colors.criticalTint
        LogSeverity.Neutral -> colors.surfaceAlt
    }
