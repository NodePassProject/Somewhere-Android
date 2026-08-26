// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.content.Context
import android.net.VpnService
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Where the host-side Portal is, as told to the device.
 *
 * Supplied by `conformance/scripts/e2e-*.sh` through instrumentation arguments.
 * Absent is the ordinary case — `connectedAndroidTest` has to stay runnable on
 * a bare device — so the tests that need a Portal *skip*, loudly enough to read
 * in the report and quietly enough not to turn a green suite red.
 *
 * Never defaulted to a real address. A default would make a suite that silently
 * tested nothing look exactly like one that tested everything.
 */
object E2eEnvironment {
    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    /** `host:port` of the Portal, as reachable *from the device*. */
    val portal: String? get() = argument("nowhereE2ePortal")

    val sharedKey: String get() = argument("nowhereE2eKey") ?: "conformance-smoke-key"

    /** `host:port` of a plain HTTP service the Portal can reach. */
    val target: String? get() = argument("nowhereE2eTarget")

    /**
     * A **name** the Portal can resolve and the device cannot.
     *
     * The whole point of A1: it has to be a name, it has to mean nothing on
     * this device, and it has to resolve where the Portal runs. That is what
     * remote resolution is, and it is not provable with an address.
     */
    val origin: String? get() = argument("nowhereE2eOrigin")

    fun requirePortal(): String {
        val value = portal
        assumeTrue(
            "no Portal: run conformance/scripts/e2e-fakeip.sh, or pass -PnowhereE2ePortal=host:port",
            value != null,
        )
        return value!!
    }

    /**
     * VPN consent, pre-granted by the script with `appops`.
     *
     * A skip when nothing configured this run, and a **failure** when a Portal
     * was supplied. The difference matters: with a Portal set, the script ran,
     * the containers are up and the device is installed — a skip there is
     * indistinguishable from a pass, and four device cases went that way once
     * in a run that finished BUILD SUCCESSFUL. Reinstalling the app clears the
     * grant, so this is a live failure mode rather than a defensive check.
     */
    fun requireConsent(context: Context) {
        val granted = VpnService.prepare(context) == null
        if (portal == null) {
            assumeTrue("no Portal configured, so there is nothing to consent to", granted)
            return
        }
        if (!granted) {
            throw AssertionError(
                "a Portal is configured but VPN consent is not granted — the app was most likely " +
                    "reinstalled after the pre-grant. Re-run conformance/scripts/e2e-fakeip.sh, " +
                    "which grants it with `cmd appops` immediately before the run.",
            )
        }
    }

    fun requireOrigin(): String {
        val value = origin
        assumeTrue("no origin name: run conformance/scripts/e2e-fakeip.sh", value != null)
        return value!!
    }
}
