// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

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

    fun requireOrigin(): String {
        val value = origin
        assumeTrue("no origin name: run conformance/scripts/e2e-fakeip.sh", value != null)
        return value!!
    }
}
