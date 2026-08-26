// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Where a new flow goes. `docs/protocol.md` section 3, last paragraph.
 *
 * One Mux carrier could serve every flow — the stream cap is 256 — and upstream
 * deliberately does not do that. Flows are spread over several carriers, a new
 * one opening once every live carrier holds [MuxHeader.SHARD_FLOW_THRESHOLD]
 * active flows, and a carrier with no flows at all closing after
 * [MuxHeader.SHARD_IDLE_CLOSE_SECONDS].
 *
 * **Sharding is runtime placement and adds no wire fields.** Nothing on the
 * connection says which shard a flow landed on, which is why this is a decision
 * this client makes rather than a protocol it speaks — and why the constants
 * are read from the pinned fixture rather than reasoned about: they are
 * upstream's numbers, and one of them moved in v1.8.1 while nothing here was
 * reading it.
 *
 * ## Placement is a reservation, not a look
 *
 * The obvious implementation — read the loads, pick the least, return it —
 * is wrong under exactly the load this layer exists for. Sixteen flows opening
 * at once all read an empty set, all decide a carrier is needed, and all open
 * one: sixteen connections for sixteen flows, which is what Mux was supposed
 * to avoid. Measured on a device at fifteen of sixteen before this was fixed.
 *
 * So a placement *reserves* a slot on the carrier it picked, and the reservation
 * counts toward that carrier's load until the flow is open or has failed. And
 * only one carrier is opened at a time: a thread that finds nothing with room
 * while another is already opening waits for it rather than opening a second.
 *
 * ## Placed, never migrated
 *
 * A flow is bound to the carrier it opened on for its whole life. Moving it
 * would mean a new stream id on a different connection with the Portal's own
 * pairing state left behind, and the Portal has no way to be told. So placement
 * is a decision made once, and a carrier stays alive as long as any flow on it
 * does — even when better-loaded carriers exist beside it.
 *
 * ## One set, not two
 *
 * Upstream describes separate uplink and downlink sets, because a split flow
 * puts its two directions on different carriers. A split flow needs `up` and
 * `down` to differ, which means QUIC, which is L3. At L1 and L2 every flow is
 * symmetric `tcp/tcp` and takes one duplex stream from the uplink set, so the
 * downlink set would be a permanently empty structure. It arrives with the
 * layer that fills it.
 */
class MuxShardSet(
    /** Opens and starts one new carrier. */
    private val openCarrier: () -> DecodeResult<MuxCarrier>,
    /** Monotonic milliseconds, so idleness is testable without waiting. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Whether to run the background reaper. Off in tests, which call [reap]. */
    automaticReaping: Boolean = true,
) : AutoCloseable {
    private val shards = mutableListOf<MuxCarrier>()

    /** A plain monitor rather than `Any()`, because waiters need wait/notify. */
    private val lock = Object()

    /** Whether a carrier is being opened right now. At most one at a time. */
    private var opening = false

    @Volatile private var closed = false

    private val reaper: ScheduledExecutorService? =
        if (!automaticReaping) {
            null
        } else {
            Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "mux-shard-reaper").apply { isDaemon = true }
                }.also {
                    (it as? ThreadPoolExecutor)?.rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy()
                    it.scheduleWithFixedDelay(
                        { runCatching { reap() } },
                        REAP_INTERVAL_SECONDS,
                        REAP_INTERVAL_SECONDS,
                        TimeUnit.SECONDS,
                    )
                }
        }

    val liveShardCount: Int get() = synchronized(lock) { shards.size }

    val activeFlowCount: Int get() = synchronized(lock) { shards.sumOf { it.activeFlowCount } }

    /**
     * Places one flow and opens it, holding the placement for as long as that
     * takes.
     *
     * The lambda rather than a bare `place()` because the reservation has to be
     * released however the open ends, and an API that can be called out of
     * balance eventually is: a leaked reservation makes a carrier look busier
     * than it is, forever, and nothing reports it.
     */
    fun <T : Any> placing(open: (MuxCarrier, MuxCarrier.Slot) -> DecodeResult<T>): DecodeResult<T> {
        val carrier =
            when (val placed = place()) {
                is DecodeResult.Ok -> placed.value
                is DecodeResult.Invalid -> return placed
            }
        val slot = carrier.reserve()
        return try {
            open(carrier, slot)
        } finally {
            // A no-op when `open` already released it at registration, which is
            // the ordinary case. This is for the paths that never got there.
            carrier.release(slot)
        }
    }

    /**
     * The carrier a new flow should open on, with a slot reserved on it.
     *
     * The least-loaded live one, or a new one when every live carrier already
     * holds the threshold. Least-loaded rather than first-fit because flows do
     * not end in the order they began: a carrier that has lost three of its
     * four has room, and first-fit would leave it idle while opening another
     * connection.
     *
     * Every call must be paired with [release]. Prefer [placing], which cannot
     * be called out of balance.
     */
    fun place(): DecodeResult<MuxCarrier> {
        while (true) {
            // A flag rather than an early exit from the `synchronized` lambda:
            // `return@synchronized` leaves the lambda and then carries straight
            // on to the code below it, so every waiting thread opened a carrier
            // anyway and the fix fixed nothing. The test that caught that is
            // `aBurstOfSimultaneousFlowsDoesNotOpenACarrierEach`.
            var mineToOpen = false
            synchronized(lock) {
                if (closed) return invalid(MuxShardReason.SetClosed)
                dropDeadShards()
                val candidate =
                    shards
                        .filter { it.load < MuxHeader.SHARD_FLOW_THRESHOLD }
                        .minByOrNull { it.load }
                if (candidate != null) return candidate.ok()

                // Somebody else is already opening one. Wait for them rather
                // than opening a second: without this, a burst of flows opens a
                // carrier each and multiplexes nothing.
                if (opening) {
                    lock.wait(OPEN_WAIT_MILLIS)
                } else {
                    opening = true
                    mineToOpen = true
                }
            }
            if (!mineToOpen) continue

            // Only the thread that claimed `opening` reaches here. Opened
            // outside the lock: it is a TCP connect and a TLS handshake, and
            // holding the lock across it would stall every other placement for
            // the length of a round trip.
            val opened = openCarrier()
            synchronized(lock) {
                opening = false
                lock.notifyAll()
                when (opened) {
                    is DecodeResult.Invalid -> return opened
                    is DecodeResult.Ok -> {
                        if (closed) {
                            opened.value.close()
                            return invalid(MuxShardReason.SetClosed)
                        }
                        shards += opened.value
                        return opened
                    }
                }
            }
        }
    }

    /**
     * Closes carriers that have been empty for the idle timeout.
     *
     * A carrier with no flows is a TLS connection and a pair of threads costing
     * nothing but existing anyway, and the Portal applies the same timeout from
     * its side — so a client that never reaped would be relying on the Portal
     * to close connections it had forgotten about.
     */
    fun reap() {
        val doomed =
            synchronized(lock) {
                dropDeadShards()
                val idle =
                    shards.filter {
                        // A reservation counts: a carrier picked a moment ago
                        // has no flows yet and is not idle.
                        it.load == 0 && it.idleMillis() >= MuxHeader.SHARD_IDLE_CLOSE_SECONDS * 1_000L
                    }
                shards.removeAll(idle)
                idle
            }
        doomed.forEach { runCatching { it.close() } }
    }

    /** A carrier whose transport died is not a placement candidate. */
    private fun dropDeadShards() {
        val dead = shards.filter { !it.isOpen }
        shards.removeAll(dead)
        dead.forEach {
            runCatching { it.close() }
        }
    }

    override fun close() {
        val all =
            synchronized(lock) {
                closed = true
                lock.notifyAll()
                shards.toList().also { shards.clear() }
            }
        all.forEach { runCatching { it.close() } }
        reaper?.shutdownNow()
    }

    private companion object {
        /**
         * How often the reaper looks.
         *
         * A fraction of the idle timeout, so a carrier closes within a few
         * seconds of becoming due rather than up to a whole timeout late.
         */
        const val REAP_INTERVAL_SECONDS = 5L

        /**
         * How long a placement waits for somebody else's carrier to come up
         * before looking again.
         *
         * A bound rather than a plain wait, so that an opener which dies
         * without notifying cannot leave every other placement asleep.
         */
        const val OPEN_WAIT_MILLIS = 250L
    }
}

sealed interface MuxShardReason : DecodeReason {
    data object SetClosed : MuxShardReason {
        override val detail: String = "this session's Mux carriers have been closed"
    }
}
