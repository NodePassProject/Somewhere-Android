// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target

/**
 * Turns a destination lwIP handed over into the target a flow opens to.
 *
 * The one place that decides between a name and an address, shared by the TCP
 * and UDP paths so the two cannot drift. Both need the same three things and in
 * the same order: recognise a synthetic address, hold it for as long as the flow
 * lasts, and fall back to a literal when it is not one of ours.
 *
 * Pure, and deliberately so — the alternative was two copies of this inside
 * classes that need a device to run, which is how the rule "an address is never
 * reassigned under a live flow" would come to hold on one path and not the
 * other.
 */
object FakeIpResolver {
    /**
     * The target for a flow to [destination], and whether the pool is now
     * holding a mapping on its behalf.
     *
     * [retained] is the caller's obligation: when true, exactly one
     * [FakeIpPool.release] is owed once the flow is finished, whether it
     * finished well or badly. Returned as a flag rather than left implicit
     * because the flow that fails to open has to release too, and that is the
     * path that gets forgotten.
     */
    data class Resolution(
        val target: DecodeResult<Target>,
        val retained: Boolean,
    )

    fun resolve(
        pool: FakeIpPool,
        destination: ByteArray,
        port: Int,
    ): Resolution {
        if (FakeIpPool.isFake(destination)) {
            val name = pool.retain(destination)
            if (name != null) {
                val target = Target.ofDomain(name, port)
                // A name that will not encode leaves the hold outstanding, so
                // give it back here rather than at a caller that has no reason
                // to know it was ever taken.
                if (target !is DecodeResult.Ok) pool.release(destination)
                return Resolution(target, retained = target is DecodeResult.Ok)
            }
            // In our range but unknown: a mapping that expired, or a device
            // that cached an address past the tunnel it belonged to. Falling
            // through to a literal target sends it to an address the Portal
            // cannot route, which is the honest outcome — the alternative is
            // guessing a name.
        }

        val target =
            when (destination.size) {
                FakeIpPool.IPV6_LENGTH -> Target.ofIpv6(destination, port)
                else -> Target.ofIpv4(destination, port)
            }
        return Resolution(target, retained = false)
    }
}
