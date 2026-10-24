// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.app.Application
import eu.nodepass.somewhere.apps.AppSelectionStore
import eu.nodepass.somewhere.apps.PackageManagerApps
import eu.nodepass.somewhere.data.AppSettingsStore
import eu.nodepass.somewhere.data.LaunchGuard
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.data.SubscriptionStore
import eu.nodepass.somewhere.nodes.NodeHealth
import eu.nodepass.somewhere.routing.BundledRules
import eu.nodepass.somewhere.routing.RoutingMode
import eu.nodepass.somewhere.routing.RoutingPreferences
import eu.nodepass.somewhere.routing.RuleStore
import eu.nodepass.somewhere.subscription.RefreshPreferences
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import eu.nodepass.somewhere.update.UpdateChecker
import eu.nodepass.somewhere.update.UpdateController
import eu.nodepass.somewhere.vpn.NetworkSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The object graph, built by hand.
 *
 * No injection framework: this app has one repository and one dialer, and a
 * container that can be read top to bottom is worth more here than one that
 * generates itself. If the graph grows past a screenful, revisit.
 */
class SomewhereApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Written by versions before nodes could be chosen and read by nothing
        // since. It holds a node URL with its shared key, so it does not stay.
        File(filesDir, "nodes/last-connected.txt").delete()
        warmPolicyRules()
    }

    /**
     * Parses the mainland-China set in the background when it will be needed.
     *
     * Only in Rules mode with the set on, which is the only time a tunnel
     * consults it; then the first connect finds it already in memory instead
     * of compiling it on the control thread while the user waits.
     */
    private fun warmPolicyRules() {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = routing.load()
            if (settings.mode != RoutingMode.Rules || !settings.mainlandChina) return@launch
            BundledRules
                .shared { assets.open(it).bufferedReader().use { reader -> reader.readText() } }
                .filter { it.tier == BundledRules.Tier.Policy }
                .forEach { it.loaded }
        }
    }

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
            choiceFile = File(filesDir, "nodes/choice.txt"),
            health = nodeHealth,
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

    /** How the TUN is built; read by the service at each connect. */
    val network: NetworkSettingsStore by lazy { NetworkSettingsStore(File(filesDir, "network/settings.txt")) }

    /** Whether connecting on launch is safe this time; see [LaunchGuard]. */
    val launchGuard: LaunchGuard by lazy { LaunchGuard(File(filesDir, "settings/launch-guard.txt")) }

    /** The app's own preferences: how screens look, what the app does unasked. */
    val settings: AppSettingsStore by lazy { AppSettingsStore(File(filesDir, "settings/app.txt")) }

    /** Whether a newer release exists. Process-wide, so a check is not repeated per activity. */
    val updates: UpdateController by lazy {
        UpdateController(
            checker = UpdateChecker(BuildConfig.VERSION_NAME),
            file = File(filesDir, "update/state.txt"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            io = Dispatchers.IO,
        )
    }

    /** What the device has installed, for the screen that offers a choice. */
    val installedApps: PackageManagerApps by lazy { PackageManagerApps(this) }

    /**
     * The imported rule document, and whether rules apply at all.
     *
     * Two files, because they have different lifetimes: the document is
     * replaced wholesale on every import, and losing the mode along with it
     * would be a surprise.
     */
    val rules: RuleStore by lazy { RuleStore(File(filesDir, "routing/rules.txt")) }

    val routing: RoutingPreferences by lazy { RoutingPreferences(File(filesDir, "routing/settings.txt")) }

    /**
     * How nodes have behaved, this run.
     *
     * Deliberately not persisted. What it measures is the device's network, and
     * that changes when the device moves — health carried across a restart
     * would describe the coffee shop's Wi-Fi in the user's kitchen. Latency
     * from a probe is persisted by the repository, because a measurement is a
     * measurement; a verdict is not.
     */
    val nodeHealth: NodeHealth by lazy { NodeHealth() }

    /**
     * Whether the subscription refreshes on its own.
     *
     * Its own file, read by a scheduled job that may run in a process this
     * screen is not part of.
     */
    val refreshPreferences: RefreshPreferences by lazy {
        RefreshPreferences(File(filesDir, "subscription/refresh.txt"))
    }
}
