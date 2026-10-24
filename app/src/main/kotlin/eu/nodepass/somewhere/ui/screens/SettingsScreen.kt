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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.nodepass.somewhere.BuildConfig
import eu.nodepass.somewhere.QuickActionActivity
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.AppSettings
import eu.nodepass.somewhere.data.ThemeMode
import eu.nodepass.somewhere.subscription.RefreshPreferences
import eu.nodepass.somewhere.ui.AppLanguage
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import eu.nodepass.somewhere.vpn.SomewhereVpnService

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    automaticRefresh: Boolean = false,
    refreshIntervalHours: Int = 0,
    onToggleAutomaticRefresh: (Boolean) -> Unit = {},
    onRefreshIntervalChange: (Int) -> Unit = {},
    settings: AppSettings = AppSettings(),
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    /** What the last backup action said, already a resource id; null for nothing. */
    backupMessage: Int? = null,
) {
    val colors = SomewhereTheme.colors
    val context = LocalContext.current
    // Re-read on every return to the screen: the system's notification
    // settings are where this changes, and the user comes back from there.
    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = context.notificationsEnabled()
        onPauseOrDispose {}
    }
    var choosingLanguage by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Int?>(null) }

    // The header stays put and the form scrolls under it. Scrolled with the
    // form, the header slid up beneath the status bar, whose icons then sat
    // on top of the title.
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.settings_title), onBack)

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
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
                    // The quick-settings tile exists now and needs no switch:
                    // a tile is added from the shade's own editor, and an
                    // in-app toggle for it would be a second place to say
                    // something the system already asks.
                    //
                    // Start-on-boot is still absent, and now deliberately
                    // rather than for want of a receiver. Always-on VPN starts
                    // the tunnel *before* applications run, keeps it running,
                    // and can block traffic while it is down; a BOOT_COMPLETED
                    // receiver starts later, can be killed, and cannot block
                    // anything. Shipping one would be offering a worse version
                    // of the feature the row below already opens.
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
                    PanelDivider()
                    PanelRow(onClick = onOpenNetwork) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.settings_network),
                                fontFamily = SomewhereType.Body,
                                fontSize = 14.sp,
                                color = colors.ink,
                            )
                            Text(stringResource(R.string.network_detail), style = SomewhereType.bodySmall, color = colors.muted)
                        }
                        Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.faint)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.nodes_subscription))
                Panel {
                    // "Every 6 hours" once described a scheduler that did not
                    // exist. It exists now — a periodic JobService, off unless
                    // this switch turns it on — so the row is back, over the
                    // mechanism rather than in front of nothing.
                    //
                    // Off by default and stated as such in the detail line: a
                    // refresh sends a bearer token to a dashboard, and a client
                    // that started doing that on a schedule because it was
                    // installed would be deciding something that is the user's.
                    PanelRow(onClick = { onToggleAutomaticRefresh(!automaticRefresh) }) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_refresh_auto),
                                fontFamily = SomewhereType.Body,
                                fontSize = 14.sp,
                                color = colors.ink,
                            )
                            Text(
                                text = stringResource(R.string.settings_refresh_auto_detail, refreshIntervalHours),
                                style = SomewhereType.bodySmall,
                                color = colors.muted,
                            )
                        }
                        // The design system's switch, as every other row here
                        // uses: two switch styles on one screen read as two
                        // kinds of setting.
                        SomewhereSwitch(automaticRefresh, onToggleAutomaticRefresh)
                    }
                    if (automaticRefresh) {
                        PanelDivider()
                        PanelRow {
                            Text(
                                text = stringResource(R.string.settings_refresh_interval),
                                fontFamily = SomewhereType.Body,
                                fontSize = 14.sp,
                                color = colors.ink,
                                modifier = Modifier.weight(0.8f),
                            )
                            val choices = RefreshPreferences.INTERVAL_CHOICES
                            Segmented(
                                options = choices.map { stringResource(R.string.settings_refresh_hours, it) },
                                // An interval written by hand that is not one of
                                // the choices selects none, rather than pretending.
                                selectedIndex = choices.indexOf(refreshIntervalHours),
                                onSelect = { index -> onRefreshIntervalChange(choices[index]) },
                                height = 30.dp,
                                mono = true,
                                modifier = Modifier.weight(2f),
                            )
                        }
                    }
                    PanelDivider()
                    // "Only over HTTPS" was a switch over an enforcement that
                    // was never written, and is still gone. What is real is the
                    // warning: a plaintext subscription URL is reported as
                    // plaintext when it is fetched, by SubscriptionFetcher,
                    // whether or not anybody has been offered a switch.
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
                SectionLabel(stringResource(R.string.settings_appearance))
                Panel {
                    PanelRow {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(0.8f),
                        )
                        val modes = ThemeMode.entries
                        Segmented(
                            options =
                                modes.map {
                                    stringResource(
                                        when (it) {
                                            ThemeMode.System -> R.string.theme_system
                                            ThemeMode.Light -> R.string.theme_light
                                            ThemeMode.Dark -> R.string.theme_dark
                                        },
                                    )
                                },
                            selectedIndex = modes.indexOf(settings.theme),
                            onSelect = { index -> onSettingsChange { it.copy(theme = modes[index]) } },
                            height = 30.dp,
                            modifier = Modifier.weight(2f),
                        )
                    }
                    if (AppLanguage.supported) {
                        PanelDivider()
                        PanelRow(onClick = { choosingLanguage = true }) {
                            Text(
                                text = stringResource(R.string.settings_language),
                                fontFamily = SomewhereType.Body,
                                fontSize = 14.sp,
                                color = colors.ink,
                                modifier = Modifier.weight(1f),
                            )
                            val current = AppLanguage.current(context)
                            Text(
                                text = if (current.isEmpty()) stringResource(R.string.language_system) else AppLanguage.endonym(current),
                                fontFamily = SomewhereType.Body,
                                fontSize = 13.sp,
                                color = colors.muted,
                            )
                            Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.faint)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.settings_app))
                Panel {
                    if (!notificationsOn) {
                        PanelRow(onClick = { context.openNotificationSettings() }) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_notifications_off),
                                    style = SomewhereType.bodySmall,
                                    color = colors.warn,
                                )
                                Text(
                                    text = stringResource(R.string.settings_notifications_allow),
                                    fontFamily = SomewhereType.Body,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = colors.brand,
                                )
                            }
                            Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.faint)
                        }
                        PanelDivider()
                    }
                    SwitchRow(
                        title = stringResource(R.string.settings_connect_on_launch),
                        detail = stringResource(R.string.settings_connect_on_launch_detail),
                        checked = settings.connectOnLaunch,
                        onCheckedChange = { on -> onSettingsChange { it.copy(connectOnLaunch = on) } },
                    )
                    PanelDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_hide_recents),
                        detail = null,
                        checked = settings.hideFromRecents,
                        onCheckedChange = { on -> onSettingsChange { it.copy(hideFromRecents = on) } },
                    )
                    PanelDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_automation),
                        // The action names are identifiers an automation app
                        // needs verbatim, so they are arguments, not prose.
                        detail =
                            stringResource(
                                R.string.settings_automation_detail,
                                listOf(QuickActionActivity.ACTION_START, QuickActionActivity.ACTION_STOP, QuickActionActivity.ACTION_TOGGLE)
                                    .joinToString(", "),
                            ),
                        checked = settings.automation,
                        onCheckedChange = { on -> onSettingsChange { it.copy(automation = on) } },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.settings_backup))
                Panel {
                    PanelRow(onClick = { confirming = R.string.backup_export_warning }) {
                        Text(
                            stringResource(R.string.backup_export),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.faint)
                    }
                    PanelDivider()
                    PanelRow(onClick = { confirming = R.string.backup_import_warning }) {
                        Text(
                            stringResource(R.string.backup_import),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.faint)
                    }
                }
                backupMessage?.let {
                    Text(
                        stringResource(it),
                        style = SomewhereType.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
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
                    // GPL-3.0: the source is part of what the user received,
                    // so the row goes to it rather than being a drawn link.
                    PanelRow(onClick = { context.openSourceCode() }) {
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

    if (choosingLanguage) LanguageDialog(onDismiss = { choosingLanguage = false })

    // Said before the file picker opens: an export is every key in plaintext,
    // and an import replaces everything. Neither is a tap to make by accident.
    confirming?.let { warning ->
        val exporting = warning == R.string.backup_export_warning
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = colors.surface,
            shape = RoundedCornerShape(14.dp),
            title = {
                Text(
                    stringResource(if (exporting) R.string.backup_export else R.string.backup_import),
                    style = SomewhereType.rowHeading,
                    color = colors.ink,
                )
            },
            text = { Text(stringResource(warning), style = SomewhereType.bodySmall, color = colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    if (exporting) onExportBackup() else onImportBackup()
                }) {
                    Text(
                        stringResource(if (exporting) R.string.backup_export else R.string.backup_import),
                        color = if (exporting) colors.brand else colors.critical,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel), color = colors.inkMuted) }
            },
        )
    }
}

/** The four choices, each named in its own language. */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val colors = SomewhereTheme.colors
    val context = LocalContext.current
    val current = AppLanguage.current(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(14.dp),
        title = { Text(stringResource(R.string.settings_language), style = SomewhereType.rowHeading, color = colors.ink) },
        text = {
            Column {
                AppLanguage.tags.forEach { tag ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onDismiss()
                                    AppLanguage.set(context, tag)
                                }.padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (tag.isEmpty()) stringResource(R.string.language_system) else AppLanguage.endonym(tag),
                            fontFamily = SomewhereType.Body,
                            fontSize = 14.sp,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (tag == current) Icon(SomewhereIcons.Check, null, Modifier.size(16.dp), tint = colors.brand)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = colors.inkMuted) }
        },
    )
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

/** Off altogether, or only the tunnel's channel blocked: either way the shade shows nothing. */
private fun Context.notificationsEnabled(): Boolean {
    val manager = getSystemService(android.app.NotificationManager::class.java) ?: return true
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannel(SomewhereVpnService.CHANNEL) ?: return true
    return channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
}

/** The app's own notification settings, where the permission can be given once the system stops asking. */
private fun Context.openNotificationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.openSourceCode() {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NodePassProject/Somewhere-Android"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
