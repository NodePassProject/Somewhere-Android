// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.app.Application
import eu.nodepass.somewhere.apps.AppSelectionStore
import eu.nodepass.somewhere.apps.PackageManagerApps
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.data.SubscriptionStore
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The object graph, built by hand.
 *
 * No injection framework: this app has one repository and one dialer, and a
 * container that can be read top to bottom is worth more here than one that
 * generates itself. If the graph grows past a screenful, revisit.
 */
class SomewhereApplication : Application() {
    val nodes: NodeRepository by lazy {
        NodeRepository(
            store = NodeStore(File(filesDir, "nodes/nodes.txt")),
            // Its own file, not a section of the node list: the subscription URL
            // is a bearer credential, and a future "export my nodes" must not be
            // able to sweep it up by accident.
            subscriptions = SubscriptionStore(File(filesDir, "subscription/subscription.txt")),
            fetcher = SubscriptionFetcher(clientVersion = BuildConfig.VERSION_NAME),
            io = Dispatchers.IO,
            // Application-lifetime, and supervised so one failed fetch does not
            // take the scope down with it.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * Which applications the tunnel carries.
     *
     * Its own file for the same reason the subscription has one: it is read by
     * the VPN service on a different thread from the screen that writes it,
     * and a shared file would make one a reason to reparse the other.
     */
    val appSelection: AppSelectionStore by lazy {
        AppSelectionStore(File(filesDir, "apps/selection.txt"))
    }

    /** What the device has installed, for the screen that offers a choice. */
    val installedApps: PackageManagerApps by lazy { PackageManagerApps(this) }
}
