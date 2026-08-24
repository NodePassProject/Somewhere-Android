// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import java.util.concurrent.atomic.AtomicLong

/**
 * Hands out flow ids for one session. NW-P-02.
 *
 * The specification is precise about three things and each has a failure mode:
 * ids are **non-zero** (zero means connection-level in a Mux WINDOW frame),
 * **unique within the session**, and **allocated monotonically, reusable only
 * after release**.
 *
 * That last rule is why released ids go to the back of a queue rather than being
 * handed straight back: reusing an id immediately would let a late frame from
 * the closed flow land on its replacement, and the two flows would be
 * indistinguishable on the wire.
 */
class FlowIdAllocator {
    private val next = AtomicLong(1)
    private val released = ArrayDeque<UInt>()
    private val live = HashSet<UInt>()
    private val lock = Any()

    val liveCount: Int get() = synchronized(lock) { live.size }

    val releasedCount: Int get() = synchronized(lock) { released.size }

    /**
     * @return the next id, or null once the u32 space is exhausted and nothing
     *   has been released. Exhaustion is not an exception: it is a condition the
     *   session can act on by opening a new one.
     */
    fun allocate(): UInt? =
        synchronized(lock) {
            released.removeFirstOrNull()?.let { reused ->
                live.add(reused)
                return reused
            }
            val candidate = next.getAndIncrement()
            if (candidate > MAX_FLOW_ID) return null
            val id = candidate.toUInt()
            live.add(id)
            id
        }

    /**
     * Returns an id to the pool.
     *
     * Releasing an id that was never allocated, or releasing twice, is ignored
     * rather than treated as an error — teardown paths race, and an allocator
     * that threw on a double release would turn a harmless race into a crash.
     */
    fun release(id: UInt) {
        synchronized(lock) {
            if (live.remove(id)) released.addLast(id)
        }
    }

    fun isLive(id: UInt): Boolean = synchronized(lock) { id in live }

    companion object {
        /** u32 max. Ids are non-zero, so the usable space starts at 1. */
        const val MAX_FLOW_ID: Long = 0xFFFFFFFFL
    }
}
