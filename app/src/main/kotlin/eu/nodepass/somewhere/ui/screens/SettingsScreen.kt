// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

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
                    // Always-on is the system's switch, not this app's: it
                    // lives in Android's VPN settings and no application can
                    // set it for itself. A switch here would have been a
                    // control that looked like it did something and did not,
                    // so this opens the place where the real one is.
                    //
                    // Start-on-boot and the quick-settings tile were switches
                    // over nothing at all — there is no BOOT_COMPLETED
                    // receiver and no TileService — and are gone until there
                    // is something for them to turn on.
                    PanelRow(onClick = { context.openVpnSettings() }) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_always_on),
                                fontFamily = SomewhereType.Body,
                                fontSize = 14.sp,
                                color = colors.ink,
                            )
                            Text(
                                text = stringResource(R.string.settings_always_on_detail),
                                style = SomewhereType.bodySmall,
                                color = colors.muted,
                            )
                        }
                        Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.faint)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.nodes_subscription))
                Panel {
                    // "Every 6 hours" described a scheduler that does not
                    // exist — no WorkManager, no alarm, nothing periodic
                    // anywhere in the app — and "only over HTTPS" was a switch
                    // over an enforcement that was never written. What is real
                    // is the warning: a plaintext subscription URL is reported
                    // as plaintext when it is fetched, by
                    // SubscriptionFetcher, whether or not anybody has been
                    // offered a switch. Both are gone rather than left saying
                    // something the app does not do.
                    PanelRow {
                        Text(
                            text = stringResource(R.string.settings_https_only_detail),
                            style = SomewhereType.bodySmall,
                            color = colors.muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
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

/**
 * Opens Android's own VPN settings, where always-on lives.
 *
 * No application can turn always-on on for itself — it is a per-app system
 * setting the user grants — so the honest control is a door to the right
 * screen. The fallback is the app's own detail page, which every device has,
 * for the handful of builds with no VPN settings activity to resolve.
 */
private fun Context.openVpnSettings() {
    val vpn = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(vpn) }.recoverCatching { startActivity(fallback) }
}
