// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.mux.MuxCarrier
import eu.nodepass.somewhere.protocol.mux.MuxShardSet
import eu.nodepass.somewhere.protocol.target.Target
import java.io.Closeable

/**
 * One client session against one Portal. D1.
 *
 * A session owns the identity that spans every physical connection beneath it:
 * the 16-byte session id and the flow-id space. That is the whole reason it
 * exists as a type — a Portal pairs the two halves of a split flow on
 * `(session_id, flow_id)`, so those two values must be allocated in one place
 * even though they are used on connections that know nothing about each other.
 *
 * ## Why this is not a connection pool
 *
 * At L1 every flow gets its own TLS connection and closes it afterwards, so a
 * session looks like bookkeeping around single-use lanes. That changes at L2 and
 * L3 without the caller noticing, which is the point: a Mux carrier serves many
 * flows over one connection, and the caller still asks the session for a flow.
 *
 * Deliberately **not** a warm connection pool. Nowhere 1.7 had one, 1.8 removed
 * it, and reintroducing it here would be implementing a protocol feature that no
 * longer exists. A Mux carrier is not a pool either: it is one connection
 * carrying many flows at once, not one connection reused by flows in turn.
 *
 * ## The two carriers
 *
 * [mux] chooses between them and nothing else does. It comes from the node's
 * own `mux` parameter, so a node that does not ask for Mux takes the same path
 * it took before this existed — the dedicated-lane branch below is untouched,
 * which is the point of writing it as a branch rather than as a mode inside one
 * implementation.
 *
 * ## Concurrency
 *
 * Flow-id allocation is internally synchronised, so [openFlow] is safe to call
 * from several threads. Everything else — the lanes, the transports — belongs to
 * whoever called, because a lane at L1 is used by exactly one flow and there is
 * nothing to share.
 */
class NowhereSession(
    private val sharedKey: SharedKey,
    private val connect: TransportFactory,
    val id: SessionId = SessionId.random(),
    /**
     * Whether this session multiplexes. The node's `mux` parameter, and the
     * only thing that selects between the two carriers.
     */
    private val mux: Boolean = false,
) : Closeable {
    /** Opens a fresh authenticated-capable transport to the Portal. */
    fun interface TransportFactory {
        fun connect(): Transport
    }

    private val flowIds = FlowIdAllocator()
    private val lanes = mutableListOf<DedicatedTlsLane>()
    private val lock = Any()
    private var closed = false

    /**
     * The Mux carriers, when there are any.
     *
     * Null rather than empty for `mux=0`, so that nothing about the Mux layer —
     * not even a reaper thread — exists in a session that does not use it.
     */
    private val shards: MuxShardSet? =
        if (!mux) {
            null
        } else {
            MuxShardSet(
                openCarrier = {
                    val carrier = MuxCarrier(connect.connect(), sharedKey, id)
                    when (val started = carrier.start()) {
                        is DecodeResult.Ok -> DecodeResult.Ok(carrier)
                        is DecodeResult.Invalid -> {
                            carrier.close()
                            started
                        }
                    }
                },
            )
        }

    /** How many TLS connections this session is holding. One per flow at L1. */
    val carrierCount: Int
        get() = shards?.liveShardCount ?: synchronized(lock) { lanes.size }

    val liveFlowCount: Int get() = flowIds.liveCount

    /**
     * Opens one flow to [target].
     *
     * At L1 this means: a new TLS connection, authenticate, open, and hand back
     * the flow. The connection belongs to the flow and closes with it.
     *
     * @param firstPayload appended to the opening write, so that a caller who
     *   already has the client's first bytes spends one packet instead of two.
     */
    fun openFlow(
        target: Target,
        kind: FlowKind = FlowKind.Tcp,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        check(!closed) { "session is closed" }

        val flowId =
            flowIds.allocate()
                ?: return DecodeResult.Invalid(SessionReason.FlowIdsExhausted)

        val opened =
            if (shards != null) {
                openMultiplexed(shards, target, kind, flowId, firstPayload)
            } else {
                openDedicated(target, kind, flowId, firstPayload)
            }
        return when (opened) {
            is DecodeResult.Ok -> DecodeResult.Ok(TrackedFlow(opened.value, flowId))
            is DecodeResult.Invalid -> {
                // A failed open must not leak the id: it is bounded, and a
                // caller retrying after failures is the normal case rather than
                // the exception.
                flowIds.release(flowId)
                opened
            }
        }
    }

    /** L1: one TLS connection, one flow, closed together. */
    private fun openDedicated(
        target: Target,
        kind: FlowKind,
        flowId: UInt,
        firstPayload: ByteArray,
    ): DecodeResult<Flow> {
        val lane = DedicatedTlsLane(connect.connect(), sharedKey, id)
        synchronized(lock) { lanes.add(lane) }

        return when (val opened = lane.open(target, kind, flowId, firstPayload)) {
            is DecodeResult.Ok -> opened
            is DecodeResult.Invalid -> {
                // The connection is bounded too, so a failed open closes it.
                lane.close()
                synchronized(lock) { lanes.remove(lane) }
                opened
            }
        }
    }

    /**
     * L2: a stream on whichever carrier the shard set places it on.
     *
     * A carrier that has died between placement and open is retried once — a
     * connection reaped or reset in that window is ordinary, and failing the
     * flow for it would surface as a page that intermittently does not load.
     */
    private fun openMultiplexed(
        shards: MuxShardSet,
        target: Target,
        kind: FlowKind,
        flowId: UInt,
        firstPayload: ByteArray,
    ): DecodeResult<Flow> {
        repeat(2) { attempt ->
            val carrier =
                when (val placed = shards.place()) {
                    is DecodeResult.Ok -> placed.value
                    is DecodeResult.Invalid -> return placed
                }
            val opened = carrier.open(target, kind, flowId, firstPayload)
            if (opened is DecodeResult.Ok || attempt == 1 || carrier.isOpen) return opened
        }
        return DecodeResult.Invalid(SessionReason.NoCarrier)
    }

    override fun close() {
        val toClose =
            synchronized(lock) {
                closed = true
                lanes.toList().also { lanes.clear() }
            }
        toClose.forEach { runCatching { it.close() } }
        runCatching { shards?.close() }
    }

    /** Releases the flow id when the caller closes the flow. */
    private inner class TrackedFlow(
        private val delegate: Flow,
        private val trackedId: UInt,
    ) : Flow by delegate {
        private var released = false

        override fun close() {
            if (!released) {
                released = true
                flowIds.release(trackedId)
            }
            delegate.close()
        }
    }
}

sealed interface SessionReason : eu.nodepass.somewhere.protocol.DecodeReason {
    data object FlowIdsExhausted : SessionReason {
        override val detail: String =
            "this session has no flow ids left; open a new session"
    }

    data object NoCarrier : SessionReason {
        override val detail: String =
            "no Mux carrier could be opened for this flow"
    }
}
