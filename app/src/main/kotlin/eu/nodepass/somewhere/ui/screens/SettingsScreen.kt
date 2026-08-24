// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.BuildConfig
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val colors = SomewhereTheme.colors
    var alwaysOn by remember { mutableStateOf(true) }
    var onBoot by remember { mutableStateOf(false) }
    var quickTile by remember { mutableStateOf(true) }
    var httpsOnly by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(stringResource(R.string.settings_title), onBack)

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.settings_connection))
                Panel {
                    SwitchRow(
                        stringResource(R.string.settings_always_on),
                        stringResource(R.string.settings_always_on_detail),
                        alwaysOn,
                    ) { alwaysOn = it }
                    PanelDivider()
                    SwitchRow(stringResource(R.string.settings_start_on_boot), null, onBoot) { onBoot = it }
                    PanelDivider()
                    SwitchRow(stringResource(R.string.settings_quick_tile), null, quickTile) { quickTile = it }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.nodes_subscription))
                Panel {
                    PanelRow {
                        Text(
                            text = stringResource(R.string.settings_refresh_automatically),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.settings_refresh_every_6h),
                            fontFamily = SomewhereType.Body,
                            fontSize = 13.sp,
                            color = colors.muted,
                        )
                    }
                    PanelDivider()
                    // "Your subscription link is a password" is the whole reason
                    // this switch exists: the token is in the URL, so a plaintext
                    // fetch hands it to the network.
                    SwitchRow(
                        stringResource(R.string.settings_https_only),
                        stringResource(R.string.settings_https_only_detail),
                        httpsOnly,
                    ) { httpsOnly = it }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.settings_about))
                Panel {
                    PanelRow {
                        Text(
                            text = stringResource(R.string.settings_version),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        MonoText(BuildConfig.VERSION_NAME, colors.muted, fontSize = 12.5.sp)
                    }
                    PanelDivider()
                    PanelRow {
                        Text(
                            text = stringResource(R.string.settings_protocol),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        MonoText(stringResource(R.string.protocol_baseline), colors.muted, fontSize = 12.5.sp)
                    }
                    PanelDivider()
                    PanelRow(onClick = {}) {
                        Text(
                            text = stringResource(R.string.settings_source_code),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(SomewhereIcons.ExternalLink, null, Modifier.size(17.dp), tint = colors.faint)
                    }
                }
                Text(
                    text = stringResource(R.string.settings_privacy_note),
                    fontFamily = SomewhereType.Body,
                    fontSize = 11.5.sp,
                    lineHeight = 17.25.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = SomewhereTheme.colors
    PanelRow {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, fontFamily = SomewhereType.Body, fontSize = 14.sp, color = colors.ink)
            if (detail != null) {
                Text(
                    text = detail,
                    fontFamily = SomewhereType.Body,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = colors.faint,
                )
            }
        }
        SomewhereSwitch(checked, onCheckedChange)
    }
}

/** A pushed screen's header: back, title, and an optional trailing action. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    backIcon: ImageVector = SomewhereIcons.ChevronLeft,
    backDescription: String = stringResource(R.string.action_back),
) {
    val colors = SomewhereTheme.colors
    Row(
        modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 40.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The whole 44 dp box is the target, not the 22 dp glyph: the design
        // system's minimum applies to what a finger has to hit, not to what the
        // eye sees.
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(backIcon, backDescription, Modifier.size(22.dp), tint = colors.inkMuted)
        }
        Text(
            text = title,
            fontFamily = SomewhereType.Display,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = (-0.2).sp,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
