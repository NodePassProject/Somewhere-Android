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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.MonoChip
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.state.RoutingMode
import eu.nodepass.somewhere.ui.state.RuleAction
import eu.nodepass.somewhere.ui.state.RuleSet
import eu.nodepass.somewhere.ui.state.SampleState
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import java.text.NumberFormat

@Composable
fun RoutingScreen(onOpenApps: () -> Unit) {
    val colors = SomewhereTheme.colors
    var mode by remember { mutableStateOf(RoutingMode.Rules) }
    var geoIp by remember { mutableStateOf(false) }

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
                    selected = mode == RoutingMode.Rules,
                    title = stringResource(R.string.routing_rules),
                    detail = stringResource(R.string.routing_rules_detail),
                    onClick = { mode = RoutingMode.Rules },
                )
                ModeCard(
                    selected = mode == RoutingMode.Everything,
                    title = stringResource(R.string.routing_everything),
                    detail = stringResource(R.string.routing_everything_detail),
                    onClick = { mode = RoutingMode.Everything },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.routing_rule_sets), Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.action_edit),
                        fontFamily = SomewhereType.Body,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.upstream,
                        modifier = Modifier.clickable {},
                    )
                }
                Panel {
                    SampleState.ruleSets.forEachIndexed { index, set ->
                        if (index > 0) PanelDivider()
                        RuleRow(set, geoIp) { geoIp = it }
                    }
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
                        val excluded = SampleState.apps.filter { it.excluded }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.apps_excluded_count,
                                        excluded.size,
                                        excluded.size,
                                    ),
                                fontFamily = SomewhereType.Body,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = colors.ink,
                            )
                            Text(
                                text = excluded.joinToString(", ") { it.label },
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
        borderColor = if (selected) colors.upstreamLine else colors.line,
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
                    .border(2.dp, if (selected) colors.upstream else colors.inactive, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(colors.upstream),
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

@Composable
private fun RuleRow(
    set: RuleSet,
    geoIpEnabled: Boolean,
    onGeoIpChange: (Boolean) -> Unit,
) {
    val colors = SomewhereTheme.colors
    PanelRow(Modifier.padding(horizontal = 0.dp)) {
        // DIRECT / TUNNEL / GEOIP are routing verbs, not prose: the chip stays
        // monospaced and English so a rule file and this screen read alike.
        MonoChip(
            text = set.action.name.uppercase(),
            foreground = if (set.action == RuleAction.Tunnel) colors.upstream else colors.muted,
            background = if (set.action == RuleAction.Tunnel) colors.upstreamTint else colors.surfaceAlt,
        )
        Text(
            text = stringResource(set.labelResource),
            fontFamily = SomewhereType.Body,
            fontSize = 13.sp,
            color = if (set.action == RuleAction.GeoIp) colors.inkMuted else colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (set.action == RuleAction.GeoIp) {
            SomewhereSwitch(checked = geoIpEnabled, onCheckedChange = onGeoIpChange)
        } else {
            MonoText(
                text = set.entryCount?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—",
                color = colors.faint,
            )
        }
    }
}

private val RuleSet.labelResource: Int
    get() =
        when (name) {
            "private_ranges" -> R.string.rule_private_ranges
            "domestic_domains" -> R.string.rule_domestic_domains
            "everything_else" -> R.string.rule_everything_else
            else -> R.string.rule_bypass_by_country
        }
