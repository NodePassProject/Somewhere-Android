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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.apps.AppsController
import eu.nodepass.somewhere.routing.RoutingController
import eu.nodepass.somewhere.routing.RoutingMode
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import java.text.NumberFormat

@Composable
fun RoutingScreen(
    apps: AppsController,
    routing: RoutingController,
    onOpenApps: () -> Unit,
    onReconnect: () -> Unit,
    onImportRules: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    val settings by routing.settings.collectAsState()
    val loaded by routing.loaded.collectAsState()
    val importError by routing.lastImportError.collectAsState()
    val restartNeeded by routing.restartNeeded.collectAsState()

    LaunchedEffect(Unit) { routing.refresh() }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.routing_title),
            style = SomewhereType.screenTitle,
            color = colors.ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 52.dp, bottom = 20.dp),
        )

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(
                    selected = settings.mode == RoutingMode.Rules,
                    title = stringResource(R.string.routing_rules),
                    detail = stringResource(R.string.routing_rules_detail),
                    onClick = { routing.setMode(RoutingMode.Rules) },
                )
                ModeCard(
                    selected = settings.mode == RoutingMode.Everything,
                    title = stringResource(R.string.routing_everything),
                    detail = stringResource(R.string.routing_everything_detail),
                    onClick = { routing.setMode(RoutingMode.Everything) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.routing_rule_sets), Modifier.weight(1f))
                    Text(
                        text =
                            stringResource(
                                if (loaded.count == 0) R.string.routing_import else R.string.routing_remove_rules,
                            ),
                        fontFamily = SomewhereType.Body,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.brand,
                        modifier =
                            Modifier.clickable {
                                if (loaded.count == 0) onImportRules() else routing.clearRules()
                            },
                    )
                }
                Panel {
                    // What is actually loaded. The four rule sets drawn here
                    // before — private ranges, domestic domains, everything
                    // else, bypass by country — were invented, with invented
                    // counts, and the last of them switched a GEOIP feature
                    // that does not exist.
                    PanelRow(Modifier.padding(horizontal = 0.dp)) {
                        Text(
                            text =
                                if (loaded.count == 0) {
                                    stringResource(R.string.routing_no_rules)
                                } else {
                                    pluralStringResource(
                                        R.plurals.routing_rule_count,
                                        loaded.count,
                                        NumberFormat.getIntegerInstance().format(loaded.count),
                                    )
                                },
                            style = SomewhereType.bodySmall,
                            color = if (loaded.count == 0) colors.muted else colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (loaded.unsupported.isNotEmpty()) {
                        PanelDivider()
                        PanelRow(Modifier.padding(horizontal = 0.dp)) {
                            // Named rather than dropped: a client that silently
                            // ignored a GEOIP rule would leave the user routing
                            // against a set they believe is loaded.
                            Text(
                                text =
                                    stringResource(
                                        R.string.routing_unsupported,
                                        loaded.unsupported.entries.joinToString(", ") { "${it.key} x${it.value}" },
                                    ),
                                style = SomewhereType.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }
                    importError?.let { reason ->
                        PanelDivider()
                        PanelRow(Modifier.padding(horizontal = 0.dp)) {
                            Text(text = reason, style = SomewhereType.bodySmall, color = colors.warn)
                        }
                    }
                }
                if (restartNeeded) {
                    RoutingNotice(
                        text = stringResource(R.string.routing_restart_needed),
                        action = stringResource(R.string.routing_reconnect),
                        onAction = {
                            routing.restarted()
                            onReconnect()
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                SectionLabel(stringResource(R.string.routing_per_app))
                Card(
                    modifier = Modifier.clickable(onClick = onOpenApps),
                    padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // The real selection, not a sample: this row is the
                        // only place the count appears outside the per-app
                        // screen, and two sources for one number is how they
                        // start disagreeing.
                        val selection by apps.selection.collectAsState()
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.apps_excluded_count,
                                        selection.packages.size,
                                        selection.packages.size,
                                    ),
                                fontFamily = SomewhereType.Body,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = colors.ink,
                            )
                            Text(
                                // Package names rather than labels: a label
                                // needs the PackageManager and this row is a
                                // summary, not the list. An identifier is
                                // never translated, so it is monospaced.
                                text = selection.packages.sorted().joinToString(", "),
                                style = SomewhereType.bodySmall,
                                color = colors.muted,
                            )
                        }
                        Icon(SomewhereIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        borderColor = if (selected) colors.brandLine else colors.line,
        padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) colors.brand else colors.inactive, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(colors.brand),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = SomewhereType.rowHeading,
                    color = if (selected) colors.ink else colors.inkMuted,
                )
                Text(text = detail, style = SomewhereType.bodySmall, color = colors.muted)
            }
        }
    }
}

/**
 * A line saying a running tunnel does not have these rules yet.
 *
 * Rules are read when the TUN is built, so a change made now reaches the next
 * tunnel and not this one. The alternative is a screen showing a rule set the
 * traffic is not using.
 */
@Composable
private fun RoutingNotice(
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.brandTint)
                .border(1.dp, colors.brandLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = SomewhereType.bodySmall,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = action,
            fontFamily = SomewhereType.Body,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            color = colors.brand,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
