// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Many flows at once, through a real tunnel.
 *
 * On its own this says the client survives concurrency. Its other half is on
 * the host: `e2e-fakeip.sh` counts the **distinct source ports** the Portal saw
 * while this ran, which is the number of TLS connections that were really
 * opened. That is the whole case for L2 stated as a measurement — the same
 * sixteen fetches cost sixteen connections without Mux and four with it — and
 * it is a figure neither side can fake, because the Portal is reporting the
 * addresses its own accept() returned.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentFlowsTest {
    private companion object {
        /**
         * Enough to need several shards at a threshold of four, and few enough
         * that an emulator moves them all inside the timeout.
         */
        const val FLOWS = 16
    }

    @Before
    fun consentIsAlreadyGranted() = E2eEnvironment.requireConsent(TunnelHarness.context)

    @After
    fun stopTunnel() = TunnelHarness.stop()

    @Test
    fun sixteenSimultaneousFetchesAllComeBackIntact() {
        val origin = E2eEnvironment.requireOrigin()
        TunnelHarness.start()

        val pool = Executors.newFixedThreadPool(FLOWS)
        try {
            val url = "http://$origin${TunnelHarness.SMALL_PATH}"
            val pending =
                (1..FLOWS).map {
                    pool.submit<TunnelHarness.Fetched> { TunnelHarness.fetchAndDigest(url) }
                }
            val results = pending.map { it.get(180, TimeUnit.SECONDS) }

            results.forEachIndexed { index, fetched ->
                assertEquals("fetch $index came back changed", fetched.declaredDigest, fetched.computedDigest)
            }
            assertEquals(FLOWS, results.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
