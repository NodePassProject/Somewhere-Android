// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Brings a tunnel up and holds it, so that a shell can use it.
 *
 * ## Why this exists
 *
 * The claim that matters most on a physical device — *does the TUN carry an
 * application's traffic* — cannot be made from inside this app. This client is
 * forced out of its own tunnel in every mode, because a VPN inside its own
 * tunnel is a routing loop, so instrumentation's sockets never enter the TUN.
 * The claim belongs to a process that is not this app, and the one available is
 * `adb shell`, which runs as the shell user and *is* inside the tunnel.
 *
 * But `adb` cannot start a VPN: `VpnService` needs consent and an application
 * to bind it. So the shell needs something inside the app to bring the tunnel
 * up and keep it up while the shell works — which is this.
 *
 * It is not a test and asserts nothing. It skips unless asked for by name, so
 * an ordinary suite run never sits here holding a tunnel open for a minute.
 */
@RunWith(AndroidJUnit4::class)
class TunnelHolderTest {
    @Test
    fun holdTheTunnelOpen() {
        val seconds =
            InstrumentationRegistry.getArguments().getString("nowhereHoldSeconds")?.toIntOrNull()
        assumeTrue(
            "not asked to hold a tunnel; pass -e nowhereHoldSeconds N",
            seconds != null && seconds > 0,
        )

        E2eEnvironment.requireConsent(TunnelHarness.context)
        TunnelHarness.start()
        try {
            // Printed so a script can wait for the tunnel rather than sleeping
            // a guessed interval, which is the difference between a check that
            // is slow and one that is flaky.
            println("TUNNEL_UP")
            System.out.flush()
            Thread.sleep(seconds!! * 1_000L)
        } finally {
            TunnelHarness.stop()
            println("TUNNEL_DOWN")
        }
    }
}
