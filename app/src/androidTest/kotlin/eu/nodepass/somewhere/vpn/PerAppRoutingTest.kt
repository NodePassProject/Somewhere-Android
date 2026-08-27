// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.apps.AppSelection
import eu.nodepass.somewhere.apps.AppSelectionStore
import eu.nodepass.somewhere.apps.SelectionMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Per-application routing, asserted from inside an application.
 *
 * The instrumentation process **is** an app, with its own package name and its
 * own uid, and it is the app whose sockets these tests open. So it can be put
 * on either side of the selection and asked to prove which side it is on —
 * which is a real assertion about per-app routing rather than a proxy for one.
 *
 * That is worth spelling out because the obvious alternative does not work.
 * A fetch from `adb shell` proves nothing about any of this: shell is uid 2000
 * and Android keeps it out of the tunnel regardless, so the same command passes
 * whatever the selection says.
 *
 * ## What each case leans on
 *
 * With the test package **excluded**, its traffic never enters the TUN, so the
 * fake-IP resolver never sees the query and the name has to be resolved by the
 * device's own DNS. `E2eEnvironment.origin` is a name that only resolves inside
 * the Portal's container network, so resolution fails and the fetch fails —
 * and a failure here is the pass.
 *
 * With it **included**, the same fetch succeeds, because the query reaches the
 * fake-IP resolver and the flow leaves as a domain target for the Portal to
 * resolve.
 *
 * The two halves are what make either meaningful: a fetch that fails for its
 * own reasons would pass the first case on its own.
 */
@RunWith(AndroidJUnit4::class)
class PerAppRoutingTest {
    private val selectionFile: File
        get() = File(TunnelHarness.context.filesDir, "apps/selection.txt")

    private var saved: AppSelection? = null

    @Before
    fun setUp() {
        assumeTrue("needs a Portal; run conformance/scripts/e2e-fakeip.sh", E2eEnvironment.portal != null)
        E2eEnvironment.requireConsent(TunnelHarness.context)
        saved = AppSelectionStore(selectionFile).load()
    }

    @After
    fun tearDown() {
        runCatching { TunnelHarness.stop() }
        // Whatever this device had before the test, it has again. A test that
        // leaves a selection behind changes what every later test measures.
        saved?.let { AppSelectionStore(selectionFile).save(it) }
    }

    @Test
    fun anExcludedApplicationDoesNotReachThePortal() {
        val self = TunnelHarness.context.packageName
        AppSelectionStore(selectionFile).save(AppSelection(SelectionMode.AllButThese, setOf(self)))

        TunnelHarness.start()
        val origin = E2eEnvironment.requireOrigin()
        val outcome = runCatching { TunnelHarness.fetchAndDigest("http://$origin${TunnelHarness.SMALL_PATH}") }

        assertTrue(
            "an excluded application must not have reached the Portal; it fetched ${outcome.getOrNull()}",
            outcome.isFailure,
        )
    }

    @Test
    fun anIncludedApplicationReachesThePortalThroughTheTunnel() {
        // The other half. Without it the case above passes for a fetch that was
        // never going to work.
        AppSelectionStore(selectionFile).save(AppSelection(SelectionMode.AllButThese, emptySet()))

        TunnelHarness.start()
        val origin = E2eEnvironment.requireOrigin()
        val fetched = TunnelHarness.fetchAndDigest("http://$origin${TunnelHarness.SMALL_PATH}")

        assertEquals("the payload must survive the tunnel", fetched.declaredDigest, fetched.computedDigest)
        assertTrue("and it must be a payload", fetched.bodyBytes > 0)
    }

    @Test
    fun selectingOnlyAnApplicationThatIsNotThisOneKeepsThisOneOut() {
        // The allowed-list direction, which uses the other Android call
        // entirely. A selection naming some other installed package leaves this
        // process outside the tunnel exactly as an exclusion would.
        val other =
            TunnelHarness.context.packageManager
                .getInstalledApplications(0)
                .map { it.packageName }
                .firstOrNull { it != TunnelHarness.context.packageName }
        assumeTrue("needs a second installed application to name", other != null)

        AppSelectionStore(selectionFile).save(AppSelection(SelectionMode.OnlyThese, setOf(other!!)))

        TunnelHarness.start()
        val origin = E2eEnvironment.requireOrigin()
        val outcome = runCatching { TunnelHarness.fetchAndDigest("http://$origin${TunnelHarness.SMALL_PATH}") }

        assertTrue(
            "only another application was allowed, so this one must be outside the tunnel",
            outcome.isFailure,
        )
    }
}
