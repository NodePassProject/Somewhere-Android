// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

/**
 * The two builder calls, behind an interface a test can implement.
 *
 * `VpnService.Builder` is final and needs a device, so applying a rule to one
 * directly would make the only interesting part of this untestable. The
 * adapter in the VPN service is four lines and holds no decisions.
 */
interface VpnAppTarget {
    /** `addAllowedApplication`. */
    fun allow(packageName: String)

    /** `addDisallowedApplication`. */
    fun disallow(packageName: String)
}

/**
 * Applies this rule to a tunnel being built.
 *
 * One `when`, one call per package, and no way to reach both branches — which
 * is the whole reason [AppRule] is a sealed type rather than two collections.
 * Android throws `UnsupportedOperationException` if a builder is given both.
 */
fun AppRule.applyTo(target: VpnAppTarget) {
    when (this) {
        is AppRule.OnlyThese -> packages.forEach(target::allow)
        is AppRule.AllButThese -> packages.forEach(target::disallow)
    }
}

/**
 * What changing the selection means for a tunnel that may be running.
 *
 * Android fixes the per-application set at `establish()`, for the life of the
 * descriptor. **A change cannot take effect until the TUN is rebuilt**, so
 * there are exactly two honest outcomes and no third: store it, or rebuild.
 *
 * The third outcome — accept the change, show it as applied, and keep routing
 * the old set — is the one a naive implementation produces, and it is the
 * expensive one: everything looks right and the traffic disagrees.
 */
sealed interface SelectionChange {
    /** Nothing is running, or nothing changed. The selection is simply saved. */
    data object Stored : SelectionChange

    /** A tunnel is up and carries the previous selection until it is rebuilt. */
    data object NeedsRestart : SelectionChange
}

/**
 * Decides between the two, from the only two facts that matter.
 *
 * A selection that did not change is [SelectionChange.Stored] even while a
 * tunnel is running: rebuilding a tunnel because somebody opened a screen and
 * closed it again would be a worse defect than the one this prevents.
 */
fun changeOutcome(
    engaged: Boolean,
    before: AppSelection,
    after: AppSelection,
): SelectionChange =
    when {
        before == after -> SelectionChange.Stored
        engaged -> SelectionChange.NeedsRestart
        else -> SelectionChange.Stored
    }
