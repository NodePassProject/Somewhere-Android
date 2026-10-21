// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import eu.nodepass.somewhere.R

/*
 * Handing a node to someone else.
 *
 * A node link carries its shared key, so both paths treat it as a credential:
 * the clipboard copy is marked sensitive, which keeps the system's clipboard
 * preview from showing it on screen, and the screen that offers these says
 * what the link carries before anyone taps.
 */

/** Copies [link] to the clipboard, marked sensitive where the platform can. */
fun Context.copyNodeLink(link: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(getString(R.string.app_name), link)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    // From 13 the system confirms a copy itself; a toast as well would be the
    // same sentence twice.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.editor_link_copied, Toast.LENGTH_SHORT).show()
    }
}

/** Offers [link] to whatever the user picks, through the system's own chooser. */
fun Context.shareNodeLink(link: String) = shareText(link)

/** Offers plain [text] to whatever the user picks. */
fun Context.shareText(text: String) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
