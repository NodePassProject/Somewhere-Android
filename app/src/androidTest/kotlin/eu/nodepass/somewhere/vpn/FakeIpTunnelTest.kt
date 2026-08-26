// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.dns.FakeIpPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * A name survives the whole way to the Portal, on a device. NW-P-05.
 *
 * The claim is narrow and it is the one L1 was missing: an app on this device
 * asks for a **name**, and what the Portal is asked to dial is that name rather
 * than an address the device resolved for itself. Everything else here exists
 * to make that claim falsifiable.
 *
 * ## Why this runs as instrumentation rather than as a shell script
 *
 * Android excludes uids 0, 1000 and 2000 from VPN routing — look at `ip rule`,
 * the ranges sent to `tun0` are 1..999, 1001..1999 and 2001..99999. `adb shell`
 * is uid 2000, so a `curl` from a shell **bypasses the tunnel and proves
 * nothing**, while looking exactly like a pass. Instrumentation runs in the
 * app's own process, so a socket opened here is a socket that goes through the
 * tunnel this test just started.
 *
 * ## What the origin name has to be
 *
 * Resolvable where the Portal runs and meaningless on this device.
 * `conformance/scripts/e2e-fakeip.sh` gets that from Docker: the origin server
 * and the Portal share a network, so the Portal's resolver knows the name and
 * nothing on the device does. A name the device could resolve would prove
 * nothing — the fetch would succeed with the fake-IP layer removed, which is
 * exactly the check that was run against this test to confirm it bites.
 */
@RunWith(AndroidJUnit4::class)
class FakeIpTunnelTest {
    @Before
    fun consentIsAlreadyGranted() {
        // The script pre-grants it with `appops`. Without it `establish()`
        // returns null and every assertion below fails for a reason that has
        // nothing to do with what is under test.
        assumeTrue(
            "VPN consent was not pre-granted; see conformance/scripts/e2e-fakeip.sh",
            VpnService.prepare(TunnelHarness.context) == null,
        )
    }

    @After
    fun stopTunnel() = TunnelHarness.stop()

    @Test
    fun aDomainFetchGoesOutAsANameAndComesBackIntact() {
        val origin = E2eEnvironment.requireOrigin()
        TunnelHarness.start()
        val fetched = TunnelHarness.fetchAndDigest("http://$origin${TunnelHarness.PATH}")

        // The Portal's log is checked by the script. What this asserts is the
        // half only the device can see — that the name resolved into the
        // synthetic range, so the flow that carried it was opened by name.
        val host = origin.substringBeforeLast(':')
        val resolved = InetAddress.getByName(host).address
        assertTrue(
            "$host resolved to ${resolved.joinToString(".") { (it.toInt() and 0xFF).toString() }}, " +
                "which is not in the synthetic range — the query was not intercepted",
            FakeIpPool.isFake(resolved),
        )
        assertEquals("the payload came back changed", fetched.declaredDigest, fetched.computedDigest)
    }

    @Test
    fun anAddressLiteralStillWorks() {
        // The regression half. Fake-IP must not change what happens to a flow
        // that never involved a name.
        val target = E2eEnvironment.target
        assumeTrue("no literal target supplied", target != null)
        TunnelHarness.start()
        val fetched = TunnelHarness.fetchAndDigest("http://$target${TunnelHarness.PATH}")
        assertEquals(fetched.declaredDigest, fetched.computedDigest)
    }
}
