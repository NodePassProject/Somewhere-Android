// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The platform's answer to "what is installed", and nothing else.
 *
 * Every rule about which applications may be routed lives in [AppInventory],
 * where it is a pure function a test can write down. This class holds no
 * policy at all — it exists so that the policy has something to be a function
 * *of*, and so that the tests never need a device.
 *
 * ## What this can and cannot see
 *
 * From API 30 the platform filters what `getInstalledApplications` returns to
 * the packages this app has declared an interest in. The manifest declares a
 * `<queries>` element for launcher activities, so every application with an
 * icon in the launcher is visible — which is very nearly the set a user would
 * ever want to route.
 *
 * It is not *every* installed package: an application with no launcher entry
 * stays hidden, and seeing those would need `QUERY_ALL_PACKAGES`, which is
 * policy-sensitive on the Play store and is **D-16**, not a line added here.
 * [invisibleWithoutBroaderQuery] is what the screen uses to say so rather than
 * quietly presenting a partial list as a complete one.
 */
class PackageManagerApps(
    context: Context,
    private val self: String = context.packageName,
) {
    private val packages: PackageManager = context.packageManager

    private val icons = LruCache<String, Drawable>(ICON_CACHE_ENTRIES)

    /** Everything the platform will admit to, unfiltered by this project's rules. */
    fun candidates(): List<AppCandidate> =
        packages.getInstalledApplications(0).map { info ->
            AppCandidate(
                packageName = info.packageName,
                // An application may refuse to produce a label; that is a
                // missing label and not a failure, and [AppInventory] decides
                // what to show instead.
                label = runCatching { packages.getApplicationLabel(info).toString() }.getOrNull(),
                uid = info.uid,
                hasInternet =
                    packages.checkPermission(
                        android.Manifest.permission.INTERNET,
                        info.packageName,
                    ) == PackageManager.PERMISSION_GRANTED,
            )
        }

    /** The candidates, put through every rule in [AppInventory]. */
    fun inventory(): AppInventory = AppInventory.of(candidates(), self)

    /**
     * True when the platform is filtering the list and this app has not asked
     * for the permission that would stop it.
     *
     * Not "how many are hidden" — that number is precisely what cannot be
     * known without the permission, and inventing it would be worse than
     * saying the list is partial.
     */
    val invisibleWithoutBroaderQuery: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

    /**
     * An application's icon, off the main thread and remembered.
     *
     * A list of three hundred applications loads three hundred drawables from
     * disk, and doing that where the frame is composed is the difference
     * between a list and a slideshow.
     */
    suspend fun icon(packageName: String): Drawable? {
        icons.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = runCatching { packages.getApplicationIcon(packageName) }.getOrNull()
            loaded?.also { icons.put(packageName, it) }
        }
    }

    private companion object {
        /**
         * Enough for a long scroll without holding every drawable on a device
         * with hundreds of applications installed.
         */
        const val ICON_CACHE_ENTRIES = 96
    }
}
