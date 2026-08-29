// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.NetworkInterface

/**
 * That the device accepted the TUN this client asked for.
 *
 * The configuration itself is checked by `TunConfigurationTest`, which needs no
 * device and checks the relationships between its parts. What that cannot check
 * is the part where Android is asked for it: `Builder.addAddress` and
 * `addRoute` validate what they are given and throw, `establish()` returns null
 * rather than explaining itself, and a family the platform declined is not an
 * exception — it is a tunnel that comes up carrying one family while the DNS
 * layer mints placeholders for two.
 *
 * That failure has a specific shape worth naming: every name still resolves,
 * IPv4 still works, and the only thing that breaks is whatever the device
 * decided to try over IPv6 first — intermittently, per app, and never in a way
 * that points here.
 *
 * **This is deliberately not a traffic test.** This client is forced out of its
 * own tunnel in every mode, so nothing this process sends enters the TUN. What
 * a test inside the app can honestly observe is the interface the platform
 * built, which is exactly what this reads.
 */
@RunWith(AndroidJUnit4::class)
class TunFamiliesOnDeviceTest {
    @Before
    fun startTunnel() {
        E2eEnvironment.requireConsent(TunnelHarness.context)
        TunnelHarness.start()
    }

    @After
    fun stopTunnel() {
        runCatching { TunnelHarness.stop() }
    }

    /**
     * Every address on an interface, as bytes.
     *
     * Bytes rather than the printed form: `getHostAddress` does not agree with
     * itself across implementations for IPv6 — one prints `fd66::2` and another
     * `fd66:0:0:0:0:0:0:2` — so a string comparison here would pass or fail for
     * reasons that have nothing to do with the tunnel.
     */
    private fun addressesOf(candidate: NetworkInterface): List<ByteArray> = candidate.inetAddresses.toList().map { it.address }

    /** The interface carrying the TUN's own IPv4 address, or null. */
    private fun tunInterface(): NetworkInterface? {
        val expected = TunConfiguration.parse(TunConfiguration.addresses.first { !it.isIpv6 }.address)!!
        return NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .firstOrNull { candidate -> addressesOf(candidate).any { it.contentEquals(expected) } }
    }

    @Test
    fun theTunnelInterfaceCarriesEveryFamilyThisClientDeclares() {
        val tun =
            tunInterface() ?: throw AssertionError(
                "no interface carries the TUN's own address; the tunnel reported Connected without one",
            )

        val present = addressesOf(tun)
        val printed = present.joinToString { bytes -> bytes.joinToString("") { "%02x".format(it) } }
        TunConfiguration.addresses.forEach { declared ->
            val expected = TunConfiguration.parse(declared.address)!!
            assertTrue(
                "the platform did not give ${tun.name} the declared address ${declared.address}; it has $printed",
                present.any { it.contentEquals(expected) },
            )
        }
    }

    @Test
    fun theInterfaceMtuIsTheOneTheConfigurationAsksFor() {
        // A platform that silently clamped the MTU would make every path-MTU
        // measurement in the device pass measure the clamp instead.
        val tun = tunInterface() ?: throw AssertionError("no interface carries the TUN's own address")
        assertEquals("the TUN's MTU is not what was asked for", TunConfiguration.MTU, tun.mtu)
    }

    @Test
    fun carryingIpv6AndSynthesisingItAreTheSameDecision() {
        // Restated on the device because the two live in different layers and
        // the failure is silent: an AAAA record pointing at an address nothing
        // routes fails at the device with no error anyone can act on.
        val tun = tunInterface() ?: throw AssertionError("no interface carries the TUN's own address")
        val hasIpv6 =
            tun.inetAddresses
                .toList()
                .any { it.address.size == 16 && !it.isLinkLocalAddress }
        assertEquals(
            "the tunnel carries IPv6=$hasIpv6 while the configuration says ${TunConfiguration.carriesIpv6}",
            TunConfiguration.carriesIpv6,
            hasIpv6,
        )
    }
}
