// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.nodepass.somewhere.BuildConfig
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import eu.nodepass.somewhere.update.Release

/**
 * A newer release, offered once.
 *
 * Download opens the release page rather than fetching an APK in the
 * background: the browser shows what is being downloaded and from where, and
 * Android checks the new APK's signature against this one when it installs.
 */
@Composable
fun UpdateDialog(
    release: Release,
    onLater: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = colors.surface,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                stringResource(R.string.update_available, release.version.toString()),
                style = SomewhereType.rowHeading,
                color = colors.ink,
            )
        },
        text = {
            Text(
                text = release.notes.ifBlank { stringResource(R.string.update_no_notes, BuildConfig.VERSION_NAME) },
                style = SomewhereType.bodySmall,
                color = colors.muted,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                context.openReleasePage(release.page)
                onLater()
            }) { Text(stringResource(R.string.update_download), color = colors.brand) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.update_later), color = colors.inkMuted) }
        },
    )
}

/** The release page in the browser. Only GitHub pages reach here; see `UpdateChecker.parse`. */
fun Context.openReleasePage(page: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
