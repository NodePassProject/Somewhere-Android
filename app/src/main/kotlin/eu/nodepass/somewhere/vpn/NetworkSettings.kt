// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import java.io.File
import java.io.IOException

/**
 * How the TUN is built: what it leaves out, its MTU, and where the queries it
 * cannot answer go.
 *
 * Its own file, because the VPN service reads it when it builds a tunnel and
 * the screen that writes it may not be open. Read once per tunnel, like the
 * per-app selection and the rules: a change applies at the next connect.
 *
 * Every default is the behaviour before the setting existed, except
 * [excludeLocal]: see there.
 */
data class NetworkSettings(
    /**
     * Leave local networks out of the tunnel altogether: the LAN, link-local,
     * unique-local and multicast, both families. For "everything through the
     * Portal" users, whose printer otherwise went to a Portal that has its own
     * 192.168.0.0/16 and not theirs. Rules mode already sends them direct.
     *
     * **On by default**, as in mainstream clients. Off, a new user's printer,
     * NAS and router page went to the Portal in the default mode and simply
     * stopped answering, which reads as the tunnel breaking the LAN. The
     * resolver and the fake-IP pools stay inside whatever this says; see
     * [TunConfiguration.plan].
     */
    val excludeLocal: Boolean = true,
    /** More ranges to leave out, as the user typed them, already validated. */
    val excludedRoutes: List<TunConfiguration.Cidr> = emptyList(),
    val mtu: Int = TunConfiguration.MTU,
    /**
     * Where a query the tunnel will not answer itself is sent — PTR, SRV, TXT,
     * MX. Empty means the device's own resolvers, read off the network before
     * the tunnel replaced them. Those are often a private address the Portal
     * cannot reach, which is what this is for.
     */
    val dnsServers: List<String> = emptyList(),
) {
    /** What leaves the tunnel, in full: [excludedRoutes] and, if asked, the local ranges. */
    val exclusions: List<TunConfiguration.Cidr>
        get() = (if (excludeLocal) LOCAL_RANGES else emptyList()) + excludedRoutes

    internal fun render(): String =
        buildString {
            append("excludeLocal=").append(excludeLocal).append('\n')
            append("excludedRoutes=").append(excludedRoutes.joinToString(",") { "${it.address}/${it.prefix}" }).append('\n')
            append("mtu=").append(mtu).append('\n')
            append("dnsServers=").append(dnsServers.joinToString(",")).append('\n')
        }

    companion object {
        const val MIN_MTU = 1280
        const val MAX_MTU = 1500

        /** RFC 1918, link-local, CGNAT is not here: it is the carrier's, not the user's LAN. */
        val LOCAL_RANGES: List<TunConfiguration.Cidr> =
            listOf(
                TunConfiguration.Cidr("10.0.0.0", 8),
                TunConfiguration.Cidr("172.16.0.0", 12),
                TunConfiguration.Cidr("192.168.0.0", 16),
                TunConfiguration.Cidr("169.254.0.0", 16),
                TunConfiguration.Cidr("224.0.0.0", 4),
                TunConfiguration.Cidr("fc00::", 7),
                TunConfiguration.Cidr("fe80::", 10),
                TunConfiguration.Cidr("ff00::", 8),
            )

        /** Enough for any real list; below API 33 each one is many routes. */
        const val MAX_EXCLUDED_ROUTES = 64

        /**
         * Ranges from free text: lines, commas or spaces. Null if any piece is
         * not one, is one the tunnel cannot leave out, or there are too many.
         */
        fun parseRoutes(text: String): List<TunConfiguration.Cidr>? =
            split(text)
                .map { piece -> CidrSet.parse(piece)?.takeIf(TunConfiguration::isExcludable) ?: return null }
                .distinct()
                .takeIf { it.size <= MAX_EXCLUDED_ROUTES }

        /**
         * Resolver addresses from free text. Null if any piece is not a public
         * address.
         *
         * Public, because the queries reach it through the Portal: a LAN
         * resolver is on the Portal's LAN, not this one, and the tunnel's own
         * resolver or a fake address would be the tunnel asking itself.
         */
        fun parseResolvers(text: String): List<String>? =
            split(text)
                .map { piece -> piece.takeIf(::isPublicAddress) ?: return null }
                .distinct()

        private val UNUSABLE_RESOLVERS: List<TunConfiguration.Cidr>
            get() =
                LOCAL_RANGES + TunConfiguration.LOOPBACK + TunConfiguration.protectedRoutes +
                    listOf(
                        TunConfiguration.Cidr("0.0.0.0", 8),
                        TunConfiguration.Cidr("100.64.0.0", 10),
                        TunConfiguration.Cidr("255.255.255.255", 32),
                    )

        private fun isPublicAddress(text: String): Boolean {
            // An address, not a range: no slash.
            if ('/' in text) return false
            val address = CidrSet.parse(text)?.let(CidrSet::range) ?: return false
            return UNUSABLE_RESOLVERS.none { other ->
                val o = CidrSet.range(other)
                o != null && o.bytes == address.bytes && address.first >= o.first && address.first <= o.last
            }
        }

        private fun split(text: String): List<String> = text.split('\n', ',', ' ', '\t', '\r').map(String::trim).filter(String::isNotEmpty)

        internal fun parse(text: String): NetworkSettings {
            val fields =
                text
                    .lineSequence()
                    .mapNotNull { line ->
                        val at = line.indexOf('=')
                        if (at <= 0) null else line.take(at).trim() to line.substring(at + 1).trim()
                    }.toMap()
            val defaults = NetworkSettings()
            return NetworkSettings(
                // An unreadable value is the default, not "off": with the
                // default on, reading it as off would put the LAN back through
                // the Portal on a file nobody chose to write that way.
                excludeLocal = fields["excludeLocal"]?.toBooleanStrictOrNull() ?: defaults.excludeLocal,
                excludedRoutes = fields["excludedRoutes"]?.let(::parseRoutes) ?: defaults.excludedRoutes,
                // Clamped on read: a hand-edited file must still build a TUN.
                mtu = fields["mtu"]?.toIntOrNull()?.coerceIn(MIN_MTU, MAX_MTU) ?: defaults.mtu,
                dnsServers = fields["dnsServers"]?.let(::parseResolvers) ?: defaults.dnsServers,
            )
        }
    }
}

/** [NetworkSettings] on disk. */
class NetworkSettingsStore(
    private val file: File,
) {
    fun load(): NetworkSettings =
        try {
            if (file.exists()) NetworkSettings.parse(file.readText()) else NetworkSettings()
        } catch (_: IOException) {
            NetworkSettings()
        }

    fun save(settings: NetworkSettings): Boolean =
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(settings.render())
            if (!temporary.renameTo(file)) {
                file.writeText(settings.render())
                temporary.delete()
            }
            true
        } catch (_: IOException) {
            false
        }
}
