// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import eu.nodepass.somewhere.vpn.NetworkSettings

/**
 * How the TUN is built.
 *
 * Every field is checked before anything is saved, and saved all at once: a
 * tunnel built from half of an edit — a new route list with the old MTU, say —
 * is a configuration nobody wrote. What cannot be read is said under the field
 * that holds it, in the reader's language.
 */
@Composable
fun NetworkScreen(
    initial: NetworkSettings,
    /** Returns whether the settings reached the disk. */
    onSave: (NetworkSettings) -> Boolean,
    onBack: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    // Saveable: an edit in progress survives a rotation or a language change.
    var excludeLocal by rememberSaveable { mutableStateOf(initial.excludeLocal) }
    var routes by rememberSaveable { mutableStateOf(initial.excludedRoutes.joinToString("\n") { "${it.address}/${it.prefix}" }) }
    var mtu by rememberSaveable { mutableStateOf(initial.mtu.toString()) }
    var dns by rememberSaveable { mutableStateOf(initial.dnsServers.joinToString("\n")) }
    var saveFailed by remember { mutableStateOf(false) }

    val parsedRoutes = NetworkSettings.parseRoutes(routes)
    val parsedMtu = mtu.trim().toIntOrNull()?.takeIf { it in NetworkSettings.MIN_MTU..NetworkSettings.MAX_MTU }
    val parsedDns = NetworkSettings.parseResolvers(dns)
    val valid = parsedRoutes != null && parsedMtu != null && parsedDns != null

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.settings_network),
            onBack = onBack,
            trailing = {
                Text(
                    text = stringResource(R.string.action_save),
                    fontFamily = SomewhereType.Display,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = if (valid) colors.brand else colors.faint,
                    modifier =
                        Modifier.clickable(enabled = valid) {
                            if (onSave(NetworkSettings(excludeLocal, parsedRoutes!!, parsedMtu!!, parsedDns!!))) {
                                onBack()
                            } else {
                                saveFailed = true
                            }
                        },
                )
            },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                stringResource(if (saveFailed) R.string.network_save_failed else R.string.network_applies_next),
                style = SomewhereType.bodySmall,
                color = if (saveFailed) colors.critical else colors.muted,
            )

            Panel {
                PanelRow(onClick = { excludeLocal = !excludeLocal }) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            stringResource(R.string.network_exclude_local),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                        )
                        Text(stringResource(R.string.network_exclude_local_detail), style = SomewhereType.bodySmall, color = colors.muted)
                    }
                    SomewhereSwitch(excludeLocal, { excludeLocal = it })
                }
            }

            Field(
                label = stringResource(R.string.network_excluded_routes),
                hint = stringResource(R.string.network_excluded_routes_hint),
                value = routes,
                onChange = { routes = it },
                error = stringResource(R.string.network_invalid_routes).takeIf { parsedRoutes == null },
                singleLine = false,
            )
            Field(
                label = stringResource(R.string.network_mtu),
                hint = stringResource(R.string.network_mtu_hint),
                value = mtu,
                onChange = { mtu = it.filter(Char::isDigit).take(4) },
                error = stringResource(R.string.network_invalid_mtu).takeIf { parsedMtu == null },
                keyboard = KeyboardType.Number,
            )
            Field(
                label = stringResource(R.string.network_dns),
                hint = stringResource(R.string.network_dns_hint),
                value = dns,
                onChange = { dns = it },
                error = stringResource(R.string.network_invalid_dns).takeIf { parsedDns == null },
                singleLine = false,
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Ascii,
) {
    val colors = SomewhereTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (singleLine) 44.dp else 88.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, if (error != null) colors.criticalLine else colors.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                textStyle = TextStyle(fontFamily = SomewhereType.Mono, fontSize = 13.sp, lineHeight = 19.sp, color = colors.ink),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(error ?: hint, style = SomewhereType.bodySmall, color = if (error != null) colors.critical else colors.muted)
    }
}
