// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.ui.components.MonoChip
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import eu.nodepass.somewhere.ui.state.LogSeverity
import eu.nodepass.somewhere.ui.state.explanation
import eu.nodepass.somewhere.ui.state.identifier
import eu.nodepass.somewhere.ui.state.severity
import eu.nodepass.somewhere.ui.theme.SomewhereColors
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/** The accent bar down the start edge of every log card. */
private val STRIPE_WIDTH = 2.dp

/**
 * The connection log.
 *
 * NW-P-06 is the whole design of this screen: **seven rejections, seven
 * messages.** A client that renders them all as "connection failed" hides the
 * difference between a Portal at its flow limit and a session taken over on
 * another device — which is exactly the information someone needs in order to
 * act. The identifier is drawn beside the sentence rather than inside it, so a
 * translator cannot localise `DIAL_FAILED` and break the one string a user can
 * usefully paste into an issue.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(entries: List<ConnectionLogEntry> = emptyList()) {
    val colors = SomewhereTheme.colors
    var surface by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 52.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = SomewhereType.screenTitle,
                color = colors.ink,
            )
            // Connection log and runtime log are separate surfaces (NW-A-06), so
            // the split is a control rather than a filter buried in a menu.
            Segmented(
                options =
                    listOf(
                        stringResource(R.string.diagnostics_connections),
                        stringResource(R.string.diagnostics_runtime),
                    ),
                selectedIndex = surface,
                onSelect = { surface = it },
            )
        }

        if (entries.isEmpty()) {
            // No session has run, so there is nothing to report. The screen said
            // so with fabricated log lines until now — seven plausible entries
            // with plausible timestamps, none of which had ever happened. That
            // is the same defect as rendering an unmeasured throughput: it is
            // not a placeholder, it is the app claiming something.
            NothingLogged()
            return@Column
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
}

@Composable
private fun NothingLogged() {
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
            text = stringResource(R.string.diagnostics_empty_title),
            style = SomewhereType.rowHeading,
            color = colors.inkMuted,
        )
        Text(
            text = stringResource(R.string.diagnostics_empty_detail),
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
                            stringResource(
                                R.string.log_handshake_complete,
                                "38 ms",
                                entry.carrier.orEmpty(),
                            )
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
        LogSeverity.Warn -> colors.downstreamTint
        LogSeverity.Critical -> colors.criticalTint
        LogSeverity.Neutral -> colors.surfaceAlt
    }
