// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.state.SampleState
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val colors = SomewhereTheme.colors
    var mode by remember { mutableIntStateOf(0) }
    val apps = remember { mutableStateListOf(*SampleState.apps.toTypedArray()) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.apps_title), onBack)

        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Segmented(
                options =
                    listOf(
                        stringResource(R.string.apps_exclude_these),
                        stringResource(R.string.apps_only_these),
                    ),
                selectedIndex = mode,
                onSelect = { mode = it },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            item { SelfRow() }
            item { SearchBox() }
            itemsIndexed(apps) { index, app ->
                if (index > 0) PanelDivider()
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
                    )
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
                        // A package name is an identifier: monospaced, never
                        // translated, and never wrapped into prose.
                        MonoText(app.packageName, colors.faint, fontSize = 10.5.sp)
                    }
                    SomewhereSwitch(
                        checked = app.excluded,
                        onCheckedChange = { apps[index] = app.copy(excluded = it) },
                    )
                }
            }
        }
    }
}

/**
 * NW-A-04: the client excludes itself, and cannot be talked out of it.
 *
 * Its own traffic would otherwise loop back through the tunnel it is carrying.
 * Drawn as a fixed row with a tick rather than a switch, because a switch that
 * must never be off is an invitation to a bug report.
 */
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
private fun SearchBox() {
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
        Text(
            text = stringResource(R.string.apps_search),
            fontFamily = SomewhereType.Body,
            fontSize = 13.5.sp,
            color = colors.faint,
        )
    }
}
