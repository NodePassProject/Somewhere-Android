// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import eu.nodepass.somewhere.protocol.target.Target

/** Whether rules are consulted at all. */
enum class RoutingMode {
    /** Everything goes through the Portal. What the app did before rules existed. */
    Everything,

    /** Rules decide, and [Router.fallback] answers whatever they do not mention. */
    Rules,
}

/**
 * The one place that answers "where does this flow go".
 *
 * ## One consultation, not two
 *
 * The obvious design consults the rules twice — once in the DNS interceptor,
 * to decide whether to mint a synthetic address, and once here. It is not what
 * this does, and the reason is worth writing down because the two-consultation
 * design looks more careful and is worse.
 *
 * Everything on the device is routed into the TUN: the tunnel adds
 * `0.0.0.0/0` and nothing else. So a name the resolver declined to synthesise
 * an address for is *still* dialled through this client — the application
 * simply arrives holding a real address instead of a synthetic one, and by then
 * the name is gone. The rule that named it can no longer be found, and the flow
 * is decided by whatever the address rules say, which is usually nothing.
 *
 * A synthetic address is therefore not an artefact of tunnelling. **It is how
 * the name survives the trip from the resolver to here**, and every encodable
 * name needs one whichever way it is eventually routed. What changes with the
 * decision is what happens *after* this point: a tunnelled flow sends the name
 * to the Portal, a direct flow dials the name itself.
 *
 * The invariant that replaces the second consultation is narrow and testable:
 * **a direct flow dials a name, never the synthetic address that carried it.**
 * `DirectDialer` is where that is enforced.
 */
class Router(
    private val rules: () -> RoutingRules,
    private val mode: () -> RoutingMode,
    /** What a rule set that mentions nothing about a destination means. */
    val fallback: RouteAction = RouteAction.Tunnel,
) {
    /**
     * Where [target] goes.
     *
     * A domain is decided by name. An address is decided by address — an
     * IP-literal connection never had a name, and inventing one by reverse
     * lookup would put a network's answer in charge of this device's routing.
     */
    fun decide(target: Target): RouteAction {
        if (mode() == RoutingMode.Everything) return RouteAction.Tunnel
        val decided =
            when (target) {
                is Target.Domain -> rules().decide(target.host)
                is Target.Ip -> rules().decide(target.octets)
            }
        return decided ?: fallback
    }
}
