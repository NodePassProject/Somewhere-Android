// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The figures on the home screen are a measurement of this tunnel.
 *
 * The unit tests prove the meter's arithmetic against a fake clock. This proves
 * the wiring, which is the part that is wrong in practice: a counter can be
 * perfectly correct and be incremented in a place no byte passes through, and
 * the screen then shows a confident zero while 20 MB moves underneath it.
 *
 * So the assertion is against a transfer of a size this test decides: fetch a
 * known payload, then require the session totals to account for it.
 */
@RunWith(AndroidJUnit4::class)
@Ignore(
    "This case fetches over an ordinary socket from inside the app's own process, and this " +
        "client is forced out of its own tunnel in every mode (AppSelection.ruleFor) because a " +
        "VPN inside its own tunnel is a routing loop. Its traffic therefore never enters the TUN, " +
        "so the case proves only that the destination was reachable some other way — which is " +
        "exactly what happened when it was first run after per-app selection landed: every case " +
        "passed and the Portal's byte counters had not moved. The claim now belongs to " +
        "conformance/scripts/e2e-tunnel-fetch.sh, which drives the fetch from the shell user, who " +
        "is inside the tunnel. See internal/NOTES.md.",
)
class ThroughputOnDeviceTest {
    private companion object {
        /**
         * How much more than the payload the totals may show.
         *
         * The meter counts what crossed the flow, and a flow carries more than
         * the body: request line, headers, the response's own headers, and the
         * ACK-shaped traffic of the other direction. Ten per cent is loose
         * enough for those on a 20 MB body and far too tight to be satisfied by
         * a counter that is merely plausible.
         */
        const val OVERHEAD_ALLOWANCE = 1.10
    }

    @Before
    fun consentIsAlreadyGranted() {
        // A missing pre-grant is a skip on a bare device and a **failure** when
        // a Portal was supplied — because at that point the script ran, the
        // whole environment is standing, and a silent skip looks exactly like a
        // pass. Four device cases skipped that way once, in a run whose summary
        // said BUILD SUCCESSFUL.
        E2eEnvironment.requireConsent(TunnelHarness.context)
    }

    @After
    fun stopTunnel() = TunnelHarness.stop()

    @Test
    fun theSessionTotalsAccountForATransferOfAKnownSize() {
        val origin = E2eEnvironment.requireOrigin()

        assertFalse(
            "a tunnel that has not started cannot have measured anything",
            TunnelController.traffic.value.measured,
        )

        TunnelHarness.start()
        val fetched = TunnelHarness.fetchAndDigest("http://$origin${TunnelHarness.PATH}")
        assertEquals("the payload came back changed", fetched.declaredDigest, fetched.computedDigest)

        // The sampler publishes once a second, so the reading that includes the
        // last of the transfer has not necessarily been taken yet.
        TunnelHarness.await("a reading that accounts for the transfer", timeoutMillis = 10_000) {
            TunnelController.traffic.value.downstreamBytes >= fetched.bodyBytes
        }
        val traffic = TunnelController.traffic.value

        assertTrue("nothing was measured at all", traffic.measured)
        assertTrue(
            "downstream totalled ${traffic.downstreamBytes} for a ${fetched.bodyBytes}-byte body",
            traffic.downstreamBytes >= fetched.bodyBytes,
        )
        assertTrue(
            "downstream totalled ${traffic.downstreamBytes}, which is more than a " +
                "${fetched.bodyBytes}-byte body plus its framing — something is being counted twice",
            traffic.downstreamBytes <= (fetched.bodyBytes * OVERHEAD_ALLOWANCE).toLong(),
        )
        assertTrue(
            "a request was sent, so upstream cannot be zero",
            traffic.upstreamBytes > 0,
        )
        assertTrue(
            "upstream totalled ${traffic.upstreamBytes}: a request is not the size of a response, " +
                "so the two directions are not being counted apart",
            traffic.upstreamBytes < fetched.bodyBytes / 100,
        )
        assertEquals(
            "the session total is both directions",
            traffic.upstreamBytes + traffic.downstreamBytes,
            traffic.totalBytes,
        )
    }

    @Test
    fun stoppingTheTunnelStopsClaimingAMeasurement() {
        val origin = E2eEnvironment.requireOrigin()
        TunnelHarness.start()
        TunnelHarness.fetchAndDigest("http://$origin${TunnelHarness.PATH}")
        TunnelHarness.await("a first reading") { TunnelController.traffic.value.measured }

        TunnelHarness.stop()
        // Last session's figures are not this session's, and a rate for a
        // tunnel that has stopped is the same false claim as a zero for one
        // that has not measured — rule 4, facing the other way.
        assertFalse(TunnelController.traffic.value.measured)
        assertEquals(0, TunnelController.traffic.value.downstreamBytes)
    }
}
