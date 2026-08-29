// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.dns.FakeIpPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relationships inside the TUN's configuration, which is the part of a
 * tunnel that is easy to get subtly wrong and impossible to see fail.
 *
 * None of these would throw on a device. A resolver that is announced but not
 * routed produces a tunnel where names stop resolving after a few seconds; an
 * address inside the fake-IP range produces one where a single destination
 * behaves strangely and nothing else does. Both look like the network.
 */
class TunConfigurationTest {
    @Test
    fun bothFamiliesAreAddressedAndRouted() {
        assertTrue("no IPv4 address", TunConfiguration.addresses.any { !it.isIpv6 })
        assertTrue("no IPv6 address", TunConfiguration.addresses.any { it.isIpv6 })
        assertTrue("no IPv4 route", TunConfiguration.routes.any { !it.isIpv6 })
        assertTrue("no IPv6 route", TunConfiguration.routes.any { it.isIpv6 })
    }

    @Test
    fun carryingIpv6IsReadOffTheRoutesRatherThanDeclaredBesideThem() {
        // The DNS layer mints an IPv6 placeholder only when a flow to it can
        // arrive, and the only thing that makes it arrive is a route. Two
        // separate declarations of that fact is how they come to disagree —
        // and the disagreement is silent: an AAAA answer pointing at an
        // address nothing routes fails at the device with no error worth
        // reading.
        assertEquals(TunConfiguration.routes.any { it.isIpv6 }, TunConfiguration.carriesIpv6)
    }

    @Test
    fun everyRouteIsADefaultRoute() {
        // The design is "everything enters the TUN, and the rule set decides".
        // A narrower route here would be a second answer to a question the
        // bundled rules already answer, written where a user's import cannot
        // override it.
        TunConfiguration.routes.forEach { route ->
            assertEquals("${route.address}/${route.prefix} is not a default route", 0, route.prefix)
        }
    }

    @Test
    fun noAnnouncedAddressIsOneTheFakeIpPoolCouldMint() {
        // 198.18.0.0/15 and fc00::/96 are recognised by FakeIpResolver as
        // names. A TUN address or resolver inside either would be resolved to
        // a name that has expired, and the flow would be dialled directly at
        // an address that exists only inside this device.
        (TunConfiguration.addressBytes() + TunConfiguration.resolverBytes()).forEach { address ->
            assertFalse(
                "an announced address is inside the fake-IP range",
                FakeIpPool.isFake(address),
            )
        }
    }

    @Test
    fun everyAnnouncedResolverIsRoutedToThisTunnel() {
        // Android hands DNS to netd, which sends it to whatever the network
        // declares. A resolver announced but not routed here is a query that
        // leaves the device and never comes back — and the failure arrives
        // seconds later as "no internet", nowhere near this line.
        TunConfiguration.dnsServers.forEach { server ->
            val resolver = TunConfiguration.parse(server)
            assertNotNull("$server is not an address", resolver)
            val onLink =
                TunConfiguration.addresses.any { address ->
                    val network = TunConfiguration.parse(address.address) ?: return@any false
                    TunConfiguration.contains(network, address.prefix, resolver!!)
                }
            assertTrue("$server is announced but is on no interface subnet", onLink)
        }
    }

    @Test
    fun oneResolverIsAnnouncedPerFamilyThatIsCarried() {
        // Announcing only IPv4 while carrying IPv6 tells the device something
        // narrower than the truth, and which family Android then picks is not
        // this client's to assume.
        val families = TunConfiguration.dnsServers.map { it.contains(':') }.toSet()
        assertTrue("no IPv4 resolver is announced", false in families)
        assertEquals("IPv6 is carried but no IPv6 resolver is announced", TunConfiguration.carriesIpv6, true in families)
    }

    @Test
    fun theAddressParserAgreesWithTheOneTheRouterUses() {
        // Two parsers exist, and the reason is readability rather than
        // necessity. This is what stops them drifting.
        listOf("10.66.0.2", "0.0.0.0", "255.255.255.255", "::", "::1", "fd66::2", "2001:db8::1")
            .forEach { literal ->
                val mine = TunConfiguration.parse(literal)
                val theirs =
                    eu.nodepass.somewhere.routing.RoutingRules
                        .parseAddress(literal)
                assertNotNull("$literal did not parse here", mine)
                assertNotNull("$literal did not parse in the router", theirs)
                assertTrue("$literal parses to different bytes in two places", mine!!.contentEquals(theirs!!))
            }
    }

    @Test
    fun theMtuIsTheOneTheDeviceTestsMeasureAgainst() {
        assertEquals(1500, TunConfiguration.MTU)
    }
}
