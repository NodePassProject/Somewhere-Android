// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.SomewhereApplication
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.nodes.Attempt
import eu.nodepass.somewhere.nodes.Health
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Failing over on a device, where the node list and the service are real.
 *
 * The policy itself is unit-tested and needs no device — what needs one is the
 * wiring: that the service records what happened to the node it was given, and
 * that an unreachable node leads to another being tried rather than to a
 * failure the user has to act on.
 *
 * **A node that cannot be resolved is the reachable-looking failure to stage.**
 * A refused connection needs something listening to refuse it; a name that does
 * not exist fails the same way from every network, on every device, in under a
 * second, and reaches exactly the code path a Portal that is down reaches.
 */
@RunWith(AndroidJUnit4::class)
class FailoverOnDeviceTest {
    private val application get() = TunnelHarness.context.applicationContext as SomewhereApplication

    private fun url(host: String): NowhereUrl =
        when (val parsed = NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@$host:443")) {
            is DecodeResult.Ok -> parsed.value
            is DecodeResult.Invalid -> throw AssertionError("test URL does not parse: ${parsed.reason.detail}")
        }

    private var saved: List<NodeStore.Entry> = emptyList()

    @Before
    fun rememberTheUsersNodes() {
        E2eEnvironment.requireConsent(TunnelHarness.context)
        saved = application.nodes.nodes.value
        application.nodeHealth.clear()
    }

    @After
    fun restore() {
        application.nodeHealth.clear()
        runCatching { TunnelHarness.stop() }
    }

    /**
     * Waits for the service to have an opinion about [node].
     *
     * On the health rather than on the tunnel state, and that is the whole
     * lesson of writing this. `state` is process-wide and survives the test
     * that set it, so "wait until it is not Connecting" is true before the
     * service has run, and "wait until it is Failed" is true because the
     * *previous* test failed. Health is cleared in `@Before`, so waiting for it
     * to stop being Untried waits for this attempt and no other.
     */
    private fun awaitOutcome(node: NowhereUrl) =
        TunnelHarness.await("the service to record an outcome for ${node.host}") {
            application.nodeHealth.health(node.toUrl()) != Health.Untried
        }

    @Test
    fun anUnresolvableNodeIsRecordedAsUnreachableRatherThanRefused() {
        // The distinction the whole policy rests on. Unreachable moves to
        // another node; refused does not, because failing over on a setting
        // that is wrong everywhere would walk the entire list on one mistyped
        // character.
        val node = url("no-such-host.invalid")
        SomewhereVpnService.start(TunnelHarness.context, node)
        awaitOutcome(node)

        val health = application.nodeHealth.health(node.toUrl())
        assertTrue(
            "an unresolvable node should be degraded, not refusing; it is $health",
            health is Health.Degraded,
        )
    }

    @Test
    fun aNodeNamedByHostIsLookedUpRatherThanReportedUnresolvable() {
        // Every other case here uses an `.invalid` name, which fails to resolve
        // whether or not the lookup is allowed to run — so none of them could
        // tell a lookup that ran from one the platform refused. `localhost`
        // resolves on every device. Port 1 has nothing listening, so the
        // attempt must end as unreachable *after* the name was looked up.
        //
        // The service once resolved the Portal on the main thread, where
        // StrictMode refuses any lookup by name with NetworkOnMainThreadException.
        // The catch read that as "could not be looked up", so on a device every
        // node named by host failed, and only nodes given as an address worked.
        val node =
            when (val parsed = NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@localhost:1")) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid -> throw AssertionError(parsed.reason.detail)
            }
        SomewhereVpnService.start(TunnelHarness.context, node, mayFailOver = false)
        awaitOutcome(node)
        TunnelHarness.await("the attempt to end") { TunnelController.state.value is TunnelState.Failed }

        val failed = TunnelController.state.value as TunnelState.Failed
        // Not merely "not unresolvable": the name resolved, the dial was
        // refused, and that is what the user must be told.
        assertEquals(
            "a resolvable name with nothing listening should be reported as a TLS node that did not answer",
            eu.nodepass.somewhere.R.string.tunnel_tls_unreachable,
            failed.reason,
        )
    }

    @Test
    fun aSucceedingNodeClearsWhatWentBeforeIt() {
        // Recorded through the same object the service writes to, so a
        // regression in either half shows up here.
        val node = url("no-such-host.invalid")
        application.nodeHealth.record(node.toUrl(), Attempt.Unreachable)
        application.nodeHealth.record(node.toUrl(), Attempt.Succeeded)
        assertEquals(Health.Healthy, application.nodeHealth.health(node.toUrl()))
    }

    @Test
    fun theServiceRecordsHealthAgainstTheNodeItWasActuallyGiven() {
        // Keyed on the URL the service was started with, which is what the
        // node list holds. A key derived any other way — the display name, the
        // host alone — would collide between two nodes on one Portal and
        // condemn both when one failed.
        val first = url("no-such-host-one.invalid")
        val second = url("no-such-host-two.invalid")
        SomewhereVpnService.start(TunnelHarness.context, first)
        awaitOutcome(first)
        assertTrue(application.nodeHealth.health(first.toUrl()) is Health.Degraded)
        assertEquals(
            "an untouched node must not be marked by another node's failure",
            Health.Untried,
            application.nodeHealth.health(second.toUrl()),
        )
    }
}
