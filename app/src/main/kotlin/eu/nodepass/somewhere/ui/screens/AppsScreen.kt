// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.apps.AppListView
import eu.nodepass.somewhere.apps.AppSort
import eu.nodepass.somewhere.apps.AppsController
import eu.nodepass.somewhere.apps.InstalledApp
import eu.nodepass.somewhere.apps.SelectionMode
import eu.nodepass.somewhere.data.AppSettings
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/**
 * Which applications the tunnel carries.
 *
 * Every control here reaches [AppsController] and through it the file the VPN
 * service reads while building a TUN. It did not, once: this screen offered
 * four invented applications and a search box that searched nothing, and none
 * of it survived leaving the screen.
 */
@Composable
fun AppsScreen(
    onBack: () -> Unit,
    controller: AppsController,
    onReconnect: () -> Unit,
    settings: AppSettings = AppSettings(),
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val colors = SomewhereTheme.colors
    val installed by controller.installed.collectAsState()
    val loading by controller.loading.collectAsState()
    val selection by controller.selection.collectAsState()
    val restartNeeded by controller.restartNeeded.collectAsState()
    val neverRouted by controller.unroutableCount.collectAsState()
    var query by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    // How many a paste selected; 0 when the clipboard held none; null before
    // any paste. A count rather than a sentence, so it renders in the current
    // language whenever it is drawn.
    var pasted by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { controller.refresh() }

    val shown =
        remember(installed, query, settings.appsShowSystem, settings.appsSort, selection.packages) {
            AppListView.arrange(installed, query, settings.appsShowSystem, settings.appsSort, selection.packages)
        }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.apps_title), onBack)

        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Segmented(
                options =
                    listOf(
                        stringResource(R.string.apps_exclude_these),
                        stringResource(R.string.apps_only_these),
                    ),
                selectedIndex = if (selection.mode == SelectionMode.OnlyThese) 1 else 0,
                onSelect = {
                    controller.setMode(
                        if (it == 1) SelectionMode.OnlyThese else SelectionMode.AllButThese,
                    )
                },
            )
        }

        if (restartNeeded) {
            // Android fixes the per-application set at establish(), so this
            // selection is not the one the running tunnel was built with. Said
            // out loud rather than left for the traffic to reveal.
            Notice(
                text = stringResource(R.string.apps_restart_needed),
                action = stringResource(R.string.apps_reconnect),
                onAction = {
                    controller.restarted()
                    onReconnect()
                },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            item { SelfRow() }
            item { SearchBox(query) { query = it } }
            item {
                ListTools(
                    showSystem = settings.appsShowSystem,
                    sort = settings.appsSort,
                    onShowSystem = { show -> onSettingsChange { it.copy(appsShowSystem = show) } },
                    onSort = { sort -> onSettingsChange { it.copy(appsSort = sort) } },
                    onSelectAll = { controller.selectAll(shown) },
                    onSelectNone = { controller.selectNone(shown) },
                    onCopy = { clipboard.setText(AnnotatedString(AppListView.export(selection))) },
                    onPaste = {
                        val parsed = clipboard.getText()?.text?.let(AppListView::parseImport)
                        if (parsed != null) controller.replacePackages(parsed)
                        pasted = parsed?.size ?: 0
                    },
                )
            }
            pasted?.let { count ->
                item {
                    Footnote(
                        if (count == 0) stringResource(R.string.apps_paste_nothing) else stringResource(R.string.apps_pasted, count),
                    )
                }
            }

            if (loading) {
                // Not an empty list: "still reading" and "nothing to show" are
                // different facts, and rendering them the same way tells the
                // user this device has no applications on it.
                item { Footnote(stringResource(R.string.apps_loading)) }
            } else if (installed.isEmpty()) {
                item { Footnote(stringResource(R.string.apps_none)) }
            } else if (shown.isEmpty()) {
                // The device has apps; the filter or the search hides them
                // all. An empty list with nothing said reads as "no apps".
                item { Footnote(stringResource(R.string.apps_none_shown)) }
            }

            items(shown, key = InstalledApp::packageName) { app ->
                PanelDivider()
                AppRow(
                    app = app,
                    controller = controller,
                    checked = app.packageName in selection.packages,
                    onCheckedChange = { controller.toggle(app.packageName) },
                )
            }

            if (!loading) {
                item {
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (neverRouted > 0) {
                            Footnote(pluralStringResource(R.plurals.apps_never_routed, neverRouted, neverRouted))
                        }
                        if (controller.listIsPartial) {
                            // D-16. The list is what package visibility allows,
                            // and saying so is cheaper than a permission that
                            // decides how this app may be distributed.
                            Footnote(stringResource(R.string.apps_partial_list))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    controller: AppsController,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = SomewhereTheme.colors
    // Off the composition thread and cached by the controller's adapter: three
    // hundred drawables read where the frame is built is the difference
    // between a list and a slideshow.
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = controller.icon(app.packageName)?.toBitmap()?.asImageBitmap()
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceAlt),
        ) {
            icon?.let { Image(it, null, Modifier.size(34.dp)) }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = app.label,
                fontFamily = SomewhereType.Body,
                fontSize = 13.5.sp,
                color = colors.ink,
            )
            // A package name is an identifier: monospaced, never translated,
            // and never wrapped into prose.
            MonoText(app.packageName, colors.faint, fontSize = 10.5.sp)
        }
        SomewhereSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        fontFamily = SomewhereType.Body,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        color = SomewhereTheme.colors.muted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun Notice(
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.brandTint)
                .border(1.dp, colors.brandLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            fontFamily = SomewhereType.Body,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
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

@Composable
private fun SelfRow() {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.brandTint)
                .border(1.dp, colors.brandLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.brandLine),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "S",
                fontFamily = SomewhereType.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = colors.brand,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.apps_self_excluded),
                fontFamily = SomewhereType.Body,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = colors.muted,
            )
        }
        Icon(SomewhereIcons.Check, null, Modifier.size(17.dp), tint = colors.brand)
    }
}

@Composable
private fun SearchBox(
    query: String,
    onQuery: (String) -> Unit,
) {
    val colors = SomewhereTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(SomewhereIcons.Search, null, Modifier.size(17.dp), tint = colors.faint)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.apps_search),
                    fontFamily = SomewhereType.Body,
                    fontSize = 13.5.sp,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle =
                    TextStyle(
                        fontFamily = SomewhereType.Body,
                        fontSize = 13.5.sp,
                        color = colors.ink,
                    ),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Filter, order, and the bulk actions.
 *
 * Select all and none act on what is shown, so a search narrows them: "all
 * the Google apps" is a search and a tap. Copy and paste move the package
 * list as text, one per line, which is also what other clients export.
 */
@Composable
private fun ListTools(
    showSystem: Boolean,
    sort: AppSort,
    onShowSystem: (Boolean) -> Unit,
    onSort: (AppSort) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.apps_show_system),
                fontFamily = SomewhereType.Body,
                fontSize = 13.sp,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            SomewhereSwitch(showSystem, onShowSystem)
        }
        val sorts = AppSort.entries
        Segmented(
            options =
                sorts.map {
                    stringResource(
                        when (it) {
                            AppSort.Name -> R.string.apps_sort_name
                            AppSort.InstallTime -> R.string.apps_sort_installed
                        },
                    )
                },
            selectedIndex = sorts.indexOf(sort),
            onSelect = { onSort(sorts[it]) },
            height = 30.dp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolAction(stringResource(R.string.apps_select_all), onSelectAll)
            ToolAction(stringResource(R.string.apps_select_none), onSelectNone)
            ToolAction(stringResource(R.string.apps_copy), onCopy)
            ToolAction(stringResource(R.string.apps_paste), onPaste)
        }
    }
}

@Composable
private fun ToolAction(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontFamily = SomewhereType.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        color = SomewhereTheme.colors.brand,
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
