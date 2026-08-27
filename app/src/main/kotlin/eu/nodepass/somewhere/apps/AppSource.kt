// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import android.graphics.drawable.Drawable

/**
 * Where the list of applications comes from.
 *
 * An interface with one real implementation, which is worth it here: the real
 * one is `PackageManager` and needs a device, and everything interesting about
 * [AppsController] — what it saves, when it decides a tunnel must be rebuilt —
 * would otherwise be untestable on a JVM.
 */
interface AppSource {
    /** Everything installed, put through the rules in [AppInventory]. */
    fun inventory(): AppInventory

    /** True when the platform is hiding applications this app has not asked to see. */
    val listIsPartial: Boolean

    /** An application's icon, or null. Never called on the main thread. */
    suspend fun icon(packageName: String): Drawable?
}
