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
    private val lock = Any()

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
     * The carrier a new flow should open on.
     *
     * The least-loaded live one, or a new one when every live carrier already
     * holds the threshold. Least-loaded rather than first-fit because flows do
     * not end in the order they began: a carrier that has lost three of its
     * four has room, and first-fit would leave it idle while opening a fifth
     * connection.
     */
    fun place(): DecodeResult<MuxCarrier> {
        if (closed) return invalid(MuxShardReason.SetClosed)

        synchronized(lock) {
            dropDeadShards()
            val candidate =
                shards
                    .filter { it.activeFlowCount < MuxHeader.SHARD_FLOW_THRESHOLD }
                    .minByOrNull { it.activeFlowCount }
            if (candidate != null) return candidate.ok()
        }

        // Opened outside the lock: it is a TCP connect and a TLS handshake, and
        // holding the lock across it would stall every other flow's placement
        // for the length of a round trip.
        return when (val opened = openCarrier()) {
            is DecodeResult.Invalid -> opened
            is DecodeResult.Ok -> {
                synchronized(lock) {
                    if (closed) {
                        opened.value.close()
                        return invalid(MuxShardReason.SetClosed)
                    }
                    shards += opened.value
                }
                opened
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
                        it.activeFlowCount == 0 && it.idleMillis() >= MuxHeader.SHARD_IDLE_CLOSE_SECONDS * 1_000L
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
        dead.forEach { runCatching { it.close() } }
    }

    override fun close() {
        val all =
            synchronized(lock) {
                closed = true
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
    }
}

sealed interface MuxShardReason : DecodeReason {
    data object SetClosed : MuxShardReason {
        override val detail: String = "this session's Mux carriers have been closed"
    }
}
