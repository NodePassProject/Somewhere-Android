// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import java.util.concurrent.atomic.AtomicInteger

/**
 * Packet ids for fragmented UDP packets on one flow. NW-P-21.
 *
 * ## Zero is skipped, always
 *
 * `packet_id` is nonzero, so the counter walks past zero rather than emitting
 * it. That matters after 2^32 packets, which no flow will reach — and it
 * matters on the very first allocation of a counter that started at zero, which
 * every flow reaches immediately.
 *
 * ## Replanning takes a new id
 *
 * QUIC's maximum datagram size is not fixed: path MTU discovery moves it, and
 * it can shrink mid-connection. A packet already partly sent under the old size
 * and then re-planned under the new one would have two different fragment
 * layouts sharing an id, and a peer that received some of each would reassemble
 * a packet that was never sent. So a replan allocates a fresh id and abandons
 * the old one, which the peer's ten-second lifetime cleans up.
 */
class PacketIds {
    private val next = AtomicInteger(0)

    /** The next id for this flow. Never zero. */
    fun allocate(): UInt {
        while (true) {
            val candidate = next.incrementAndGet().toUInt()
            if (candidate != 0u) return candidate
        }
    }

    /**
     * A fresh id for a packet being re-planned at a new datagram size.
     *
     * The same thing [allocate] does, named differently on purpose: the call
     * site reads as the rule it is keeping rather than as a counter being
     * bumped, and a future reader looking for "what happens when the MTU
     * shrinks" finds this.
     */
    fun replan(): UInt = allocate()
}
