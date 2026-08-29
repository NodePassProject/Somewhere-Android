// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.dns.FakeIpPool

/**
 * What the TUN presents to the device: addresses, routes, resolvers, MTU.
 *
 * A value rather than a sequence of builder calls, because the interesting
 * properties of this configuration are relationships between its parts, and a
 * relationship cannot be checked by a test that needs a `VpnService` to exist.
 * Three of them have teeth:
 *
 * - **A family is carried or it is not, and everything must agree.** The route
 *   decides it, and [carriesIpv6] is read off the routes rather than declared
 *   beside them. The DNS interceptor asks the same question before it
 *   synthesises an AAAA record, so a route added without a synthesiser — or the
 *   reverse — is not expressible.
 * - **No address here may be one the fake-IP pool could mint.** A TUN address
 *   inside `198.18.0.0/15` or `fc00::/96` would be recognised by
 *   `FakeIpResolver` as a name that has expired, and a flow to it would be
 *   dialled directly at an address that exists only inside this device.
 * - **An announced resolver must be routed here.** Android hands DNS to `netd`,
 *   which sends it to whatever the network declares; a resolver this tunnel
 *   announces but does not route is a query that leaves and never comes back.
 *
 * ## Why both default routes, rather than the complement
 *
 * The donor client enumerates the complement of the ranges it wants to leave
 * outside the tunnel — eight IPv6 routes that add up to everything except
 * unique-local and link-local. That is one way to keep the printer working.
 *
 * This client takes the other: **everything enters the TUN, and the rule set
 * decides.** It is the design the IPv4 side already has — `0.0.0.0/0` plus a
 * bundled set that sends RFC 1918 to DIRECT — and having two answers to the
 * same question, one written in routes and one written in rules, is how they
 * come to disagree. It also means a user who imports a rule can override what
 * ships; a route is not overridable by anything.
 */
object TunConfiguration {
    data class Cidr(
        val address: String,
        val prefix: Int,
    ) {
        val isIpv6: Boolean get() = address.contains(':')
    }

    /**
     * `10.66.0.0/24` and `fd66::/64` rather than the ranges every other tunnel
     * picks: a collision with the device's real network makes the LAN
     * unreachable while connected, and the symptom ("my printer stopped
     * working") is never attributed to this.
     */
    val addresses =
        listOf(
            Cidr("10.66.0.2", 24),
            Cidr("fd66::2", 64),
        )

    /** Everything, both families. See the class comment for why not the complement. */
    val routes =
        listOf(
            Cidr("0.0.0.0", 0),
            Cidr("::", 0),
        )

    /**
     * The resolvers this tunnel announces, one per family.
     *
     * Inside the TUN's own subnets, so a query to either is routed here rather
     * than anywhere. Nothing listens on them in the ordinary sense — the
     * packets arrive at lwIP and are answered by
     * [NowhereFlowHandler.onUdpDatagram].
     *
     * Both families are announced because Android picks; a tunnel that
     * announced only IPv4 while carrying IPv6 would be telling the device
     * something narrower than the truth.
     */
    val dnsServers = listOf("10.66.0.1", "fd66::1")

    const val MTU = 1500

    /**
     * Whether IPv6 reaches this tunnel at all.
     *
     * Derived, and deliberately not a constant sitting next to the routes: the
     * DNS layer mints an IPv6 placeholder only when a flow to it can arrive,
     * and the only thing that makes it arrive is a route.
     */
    val carriesIpv6: Boolean get() = routes.any { it.isIpv6 }

    /** Every announced address, as bytes, for the checks that are about ranges. */
    internal fun addressBytes(): List<ByteArray> = addresses.mapNotNull { parse(it.address) }

    internal fun resolverBytes(): List<ByteArray> = dnsServers.mapNotNull { parse(it) }

    /**
     * Dotted quad or IPv6 literal, as bytes.
     *
     * Written here rather than borrowed from `RoutingRules` so that this file
     * can be read on its own; the two agree because both are checked against
     * the same literals.
     */
    internal fun parse(text: String): ByteArray? {
        if (!text.contains(':')) {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            parts.forEachIndexed { index, part ->
                val value = part.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                bytes[index] = value.toByte()
            }
            return bytes
        }
        val halves = text.split("::")
        if (halves.size > 2) return null
        val head = halves[0].split(':').filter { it.isNotEmpty() }
        val tail = if (halves.size == 2) halves[1].split(':').filter { it.isNotEmpty() } else emptyList()
        if (halves.size == 1 && head.size != 8) return null
        if (head.size + tail.size > 8) return null
        val bytes = ByteArray(16)
        head.forEachIndexed { index, group ->
            val value = group.toIntOrNull(16) ?: return null
            if (value !in 0..0xFFFF) return null
            bytes[index * 2] = ((value shr 8) and 0xFF).toByte()
            bytes[index * 2 + 1] = (value and 0xFF).toByte()
        }
        tail.forEachIndexed { index, group ->
            val value = group.toIntOrNull(16) ?: return null
            if (value !in 0..0xFFFF) return null
            val position = 16 - (tail.size - index) * 2
            bytes[position] = ((value shr 8) and 0xFF).toByte()
            bytes[position + 1] = (value and 0xFF).toByte()
        }
        return bytes
    }

    /** Whether [resolver] lies inside [network]/[prefix]. */
    internal fun contains(
        network: ByteArray,
        prefix: Int,
        resolver: ByteArray,
    ): Boolean {
        if (network.size != resolver.size) return false
        for (index in 0 until prefix) {
            val mask = 1 shl (7 - index % 8)
            if ((network[index / 8].toInt() and mask) != (resolver[index / 8].toInt() and mask)) return false
        }
        return true
    }

    /** Whether the pool could have minted [address]. Kept close to the rule it enforces. */
    internal fun isSynthetic(address: ByteArray): Boolean = FakeIpPool.isFake(address)
}
