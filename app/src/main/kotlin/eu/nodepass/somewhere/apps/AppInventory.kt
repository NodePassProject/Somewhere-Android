// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

/**
 * One installed application, as far as routing is concerned.
 *
 * [label] is never blank: an application with no readable label falls back to
 * its package name rather than to an empty row, because a blank row is
 * untappable and unreportable.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * One candidate straight from the platform, before any of this file's rules.
 *
 * Separated from [InstalledApp] so that every rule below is a pure function of
 * data a test can write down. The only thing that constructs these is the
 * `PackageManager` adapter, which therefore holds no policy at all.
 */
data class AppCandidate(
    val packageName: String,
    val label: String?,
    val uid: Int,
    val hasInternet: Boolean,
)

/**
 * What this device has installed, filtered down to what routing can act on.
 *
 * ## Three reasons an application is not in [routable]
 *
 * **It cannot open a socket.** An application without `android.permission.
 * INTERNET` has nothing for a VPN to carry, and asking the user about it is
 * noise in a list that is already long.
 *
 * **It is this client.** NW-A-04: the client is always outside its own tunnel,
 * and that is not a preference — its own traffic would otherwise loop back
 * through the tunnel it is carrying. It is therefore absent from the list
 * rather than present and switched off, because a control that must never
 * change is an invitation to a bug report.
 *
 * **The system will never route it.** Android sends uid ranges `1..999`,
 * `1001..1999` and `2001..99999` to the tunnel, so uids 0, 1000 and 2000 are
 * outside it no matter what this app asks for. Those are counted in
 * [unroutableCount] rather than listed: a switch that changes nothing is the
 * same defect as a figure that was never measured, and this screen has shipped
 * one of those already.
 */
data class AppInventory(
    val routable: List<InstalledApp>,
    val unroutableCount: Int,
) {
    companion object {
        /**
         * Uids Android keeps out of the tunnel regardless of this app's
         * request. Read off `ip rule` on a device with a tunnel up, not from
         * documentation — the ranges sent to `tun0` are `1..999`, `1001..1999`
         * and `2001..99999`.
         */
        val UNROUTABLE_UIDS = setOf(0, 1000, 2000)

        /**
         * Applies the three rules above and orders what survives.
         *
         * The order is total and stable: by label, case-insensitively, and by
         * package name where labels collide — which they do, because nothing
         * stops two applications sharing one.
         */
        fun of(
            candidates: List<AppCandidate>,
            self: String,
        ): AppInventory {
            val reachable = candidates.filter { it.hasInternet && it.packageName != self }
            val (routable, unroutable) = reachable.partition { it.uid !in UNROUTABLE_UIDS }
            return AppInventory(
                routable =
                    routable
                        .map { InstalledApp(it.packageName, it.label?.takeUnless(String::isBlank) ?: it.packageName) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::label).thenBy(InstalledApp::packageName)),
                unroutableCount = unroutable.size,
            )
        }
    }
}
