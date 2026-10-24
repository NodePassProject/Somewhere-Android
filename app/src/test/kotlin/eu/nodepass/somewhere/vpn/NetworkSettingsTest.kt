// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.vpn.TunConfiguration.Cidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger

class NetworkSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Whether [address] would reach the TUN under [plan]: longest prefix wins. */
    private fun reachesTun(
        plan: TunConfiguration.Plan,
        address: String,
    ): Boolean {
        val bytes = TunConfiguration.parse(address)!!
        val value = BigInteger(1, bytes)

        fun match(cidrs: List<Cidr>) =
            cidrs
                .mapNotNull { c ->
                    CidrSet.range(c)?.takeIf { it.bytes == bytes.size && value >= it.first && value <= it.last }?.let { c.prefix }
                }.maxOrNull()
        val routed = match(plan.routes) ?: return false
        val excluded = match(plan.excludes) ?: return true
        return routed > excluded
    }

    @Test
    fun `defaults keep local networks out and nothing else`() {
        // The one default that is not the tunnel as it was: a new user's LAN
        // stays on the LAN. Everything else is as before.
        assertTrue(NetworkSettings().excludeLocal)
        assertEquals(NetworkSettings.LOCAL_RANGES, NetworkSettings().exclusions)
        val plan = TunConfiguration.plan(NetworkSettings(excludeLocal = false).exclusions, NetworkSettings().mtu, 35)
        assertEquals(TunConfiguration.routes, plan.routes)
        assertTrue(plan.excludes.isEmpty())
        assertEquals(1500, plan.mtu)
    }

    @Test
    fun `a file that turned local networks off keeps them off, and an unreadable one gets the default`() {
        assertFalse(NetworkSettings.parse("excludeLocal=false\n").excludeLocal)
        assertTrue(NetworkSettings.parse("mtu=1400\n").excludeLocal)
    }

    @Test
    fun `local networks stay out, and the tunnel's own ranges stay in, on every API level`() {
        val exclusions = NetworkSettings(excludeLocal = true).exclusions
        for (sdk in listOf(26, 32, 33, 36)) {
            val plan = TunConfiguration.plan(exclusions, 1500, sdk)
            listOf("192.168.1.10", "10.0.0.1", "172.16.5.5", "fe80::1", "fd12::1", "224.0.0.251").forEach {
                assertFalse("API $sdk: $it should leave the tunnel", reachesTun(plan, it))
            }
            // Resolvers, TUN addresses and both fake-IP pools: without these
            // every lookup and every flow by name would break.
            listOf("10.66.0.1", "10.66.0.2", "fd66::1", "198.18.0.4", "198.19.255.1", "fc00::1:2").forEach {
                assertTrue("API $sdk: $it must stay in the tunnel", reachesTun(plan, it))
            }
            listOf("8.8.8.8", "2001:db8::1", "100.64.0.1").forEach {
                assertTrue("API $sdk: $it should be tunnelled", reachesTun(plan, it))
            }
        }
    }

    @Test
    fun `an exclusion that touches a protected range is refused, and dropped from a hand-edited file`() {
        // On API 33+ an exclusion equal to a protected route replaces it, and
        // one inside it wins on prefix length; either breaks DNS or every flow
        // by name. So none may overlap one at all.
        listOf("198.18.0.0/15", "198.18.0.0/16", "10.66.0.0/25", "fd66::/80", "fc00::/96", "0.0.0.0/0").forEach {
            assertNull("$it should be refused", NetworkSettings.parseRoutes(it))
        }
        // Wider is fine: the protected route is longer and still wins.
        assertEquals(listOf(Cidr("198.16.0.0", 12)), NetworkSettings.parseRoutes("198.16.0.0/12"))
        for (sdk in listOf(30, 35)) {
            val plan = TunConfiguration.plan(listOf(Cidr("198.18.0.0", 16), Cidr("203.0.113.0", 24)), 1500, sdk)
            assertTrue(reachesTun(plan, "198.18.0.9"))
            assertFalse(reachesTun(plan, "203.0.113.5"))
        }
    }

    @Test
    fun `loopback is never routed or excluded, which the builder would throw on`() {
        assertNull(NetworkSettings.parseRoutes("127.0.0.0/8"))
        assertNull(NetworkSettings.parseRoutes("::1"))
        // Below 33, a neighbour's complement must not start a route in 127/8.
        val plan = TunConfiguration.plan(listOf(Cidr("126.0.0.0", 8)), 1500, 30)
        val loopback = CidrSet.range(Cidr("127.0.0.0", 8))!!
        assertTrue(
            plan.routes.none { r ->
                val range = CidrSet.range(r)!!
                range.bytes == 4 && range.first <= loopback.last && loopback.first <= range.last
            },
        )
        assertTrue(plan.routes.none { it.address == "0:0:0:0:0:0:0:1" || it.address == "::1" })
    }

    @Test
    fun `more than 64 exclusions are refused`() {
        val many = (1..65).joinToString("\n") { "203.0.$it.0/24" }
        assertNull(NetworkSettings.parseRoutes(many))
    }

    @Test
    fun `settings survive a restart and a bad file still builds a TUN`() {
        val store = NetworkSettingsStore(File(folder.root, "network.txt"))
        val wanted = NetworkSettings(true, listOf(Cidr("203.0.113.0", 24)), 1400, listOf("1.1.1.1", "2606:4700:4700::1111"))
        assertTrue(store.save(wanted))
        assertEquals(wanted, store.load())

        File(folder.root, "bad.txt").writeText("mtu=99999\nexcludedRoutes=nonsense\ndnsServers=not-an-ip\nexcludeLocal=maybe\n")
        val loaded = NetworkSettingsStore(File(folder.root, "bad.txt")).load()
        assertEquals(1500, loaded.mtu)
        assertTrue(loaded.excludedRoutes.isEmpty())
        assertTrue(loaded.dnsServers.isEmpty())
        assertTrue("an unreadable value is the default", loaded.excludeLocal)
    }

    @Test
    fun `free text is read as ranges and addresses, all or nothing`() {
        assertEquals(listOf(Cidr("203.0.113.0", 24), Cidr("8.8.8.8", 32)), NetworkSettings.parseRoutes("203.0.113.7/24,\n8.8.8.8"))
        assertNull(NetworkSettings.parseRoutes("203.0.113.0/24 router.lan"))
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), NetworkSettings.parseResolvers("1.1.1.1 9.9.9.9 1.1.1.1"))
        assertNull(NetworkSettings.parseResolvers("1.1.1.1, dns.google"))
        assertEquals(emptyList<String>(), NetworkSettings.parseResolvers(""))
        // Reached through the Portal, so nothing the Portal cannot reach or
        // that is the tunnel itself.
        listOf(
            "192.168.1.1",
            "10.66.0.1",
            "198.18.0.5",
            "127.0.0.1",
            "fd00::53",
            "fe80::1",
            "0.0.0.0",
            "100.64.1.1",
            "1.1.1.0/24",
        ).forEach {
            assertNull("$it should be refused as a resolver", NetworkSettings.parseResolvers(it))
        }
        assertEquals(listOf("2606:4700:4700::1111"), NetworkSettings.parseResolvers("2606:4700:4700::1111"))
    }
}
