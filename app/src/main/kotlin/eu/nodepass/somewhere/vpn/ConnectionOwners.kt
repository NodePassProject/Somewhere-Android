// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** The four-tuple a connection was opened with, as the device sees it. */
class OwnerQuery(
    val protocol: ConnectionProtocol,
    val source: ByteArray,
    val sourcePort: Int,
    val destination: ByteArray,
    val destinationPort: Int,
)

/**
 * Which app opened a connection, by the name the launcher shows.
 *
 * `ConnectivityManager.getConnectionOwnerUid` answers this for the active VPN
 * app and nobody else, from API 29. Below that there is no supported way, and
 * the list says nothing rather than guessing from `/proc`, which newer
 * versions close and older ones expose for every app on the device.
 *
 * Labels are cached by uid: an app's name does not change while a tunnel
 * runs, and a busy device opens hundreds of connections a minute from the
 * same few apps.
 */
class ConnectionOwners(
    private val context: Context,
) {
    private val labels = ConcurrentHashMap<Int, String>()

    fun ownerOf(query: OwnerQuery): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val uid =
            try {
                manager.getConnectionOwnerUid(
                    if (query.protocol == ConnectionProtocol.Tcp) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP,
                    InetSocketAddress(InetAddress.getByAddress(query.source), query.sourcePort),
                    InetSocketAddress(InetAddress.getByAddress(query.destination), query.destinationPort),
                )
            } catch (_: Exception) {
                // SecurityException when this app is not the active VPN — a
                // tunnel torn down a moment ago — or a malformed address.
                return null
            }
        if (uid < 0) return null
        return labels.getOrPut(uid) { label(uid) }.ifEmpty { null }
    }

    private fun label(uid: Int): String {
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        // Empty for a uid with no package — a system component. The screen
        // says "unknown app" in the reader's language rather than "uid 1073".
        val first = packages.firstOrNull() ?: return ""
        // A shared uid is several packages; the first is named and the count
        // says the rest exist rather than pretending there is one.
        val name =
            try {
                val info = context.packageManager.getApplicationInfo(first, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                first
            }
        // A label is whatever the app's developer wrote, and it ends up in an
        // exported log: one line, and not a paragraph.
        val clean = name.filterNot { it.isISOControl() }.take(MAX_LABEL)
        return if (packages.size > 1) "$clean +${packages.size - 1}" else clean
    }

    private companion object {
        const val MAX_LABEL = 64
    }
}
