// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.mux.MuxCarrier
import eu.nodepass.somewhere.protocol.mux.MuxCarrierReason
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
    /**
     * Present when the node's carriers select QUIC, and then it decides:
     * `up=udp` or `down=udp` is a different carrier, not a variation of a TLS
     * one, so it takes precedence over [mux] rather than combining with it.
     * QUIC multiplexes by construction — every flow is a stream on one
     * connection — which is why there is nothing for `mux` to add here.
     *
     * A factory of *streams* rather than of carriers, deliberately: the carrier
     * is built here so that its session id is **this** session's id. NW-P-01
     * binds a session to a connection, and the pairing scope for split flows is
     * `(session_id, flow_id)`, so a QUIC carrier holding some other id would be
     * a different session wearing this one's name.
     */
    private val quicStreams: QuicCarrier.StreamFactory? = null,
    /**
     * The QUIC connection's DATAGRAM side. Absent means UDP flows are refused
     * over QUIC rather than carried over a stream — section 9 puts them in
     * DATAGRAMs and a Portal is not listening for anything else.
     */
    private val quicDatagrams: QuicCarrier.Datagrams? = null,
    /**
     * The two lane sources of a split configuration, when `up` and `down` name
     * different carriers. Present together or not at all: half a split flow is
     * a flow the Portal will pair with nothing.
     */
    private val splitUplink: SplitCarrier.LaneFactory? = null,
    private val splitDownlink: SplitCarrier.LaneFactory? = null,
) : Closeable {
    /** Opens a fresh authenticated-capable transport to the Portal. */
    fun interface TransportFactory {
        fun connect(): Transport
    }

    /**
     * The QUIC carrier, when the node selected one. One connection carries
     * every flow, so there is exactly one of these or none.
     */
    private val quic: QuicCarrier? = quicStreams?.let { QuicCarrier(it, sharedKey, id, quicDatagrams) }

    /**
     * The split carrier, when the node's two directions differ.
     *
     * Takes precedence over every other branch: `up != down` is not a variation
     * of a duplex flow but a different shape on the wire, with two lanes, two
     * FlowHeaders and an answer that arrives on only one of them.
     */
    private val split: SplitCarrier? =
        if (splitUplink != null && splitDownlink != null) {
            SplitCarrier(splitUplink, splitDownlink, sharedKey, id)
        } else {
            null
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

    /**
     * How many connections this session is holding. One per flow at L1, one per
     * shard with Mux, and exactly one over QUIC — which is the measurement that
     * distinguishes the three carriers from outside.
     */
    val carrierCount: Int
        get() =
            when {
                split != null -> split.laneCount
                quic != null -> 1
                shards != null -> shards.liveShardCount
                else -> synchronized(lock) { lanes.size }
            }

    val liveFlowCount: Int get() = flowIds.liveCount

    /**
     * Whether a UDP flow from this session takes packets rather than a framed
     * byte stream. True exactly when a QUIC carrier with DATAGRAMs is in use.
     *
     * Asked rather than inferred: a caller with a packet in hand needs to know
     * whether to frame it, and working that out from the node's parameters
     * would put the same decision in two places.
     */
    val carriesPackets: Boolean get() = quic != null

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
            when {
                split != null -> split.openFlow(target, kind, flowId, firstPayload)
                quic != null -> quic.openFlow(target, kind, flowId, firstPayload)
                shards != null -> openMultiplexed(shards, target, kind, flowId, firstPayload)
                else -> openDedicated(target, kind, flowId, firstPayload)
            }
        return when (opened) {
            is DecodeResult.Ok -> DecodeResult.Ok(track(opened.value, flowId))
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
        // `placing` rather than a bare place: the placement reserves a slot on
        // the carrier it chose, and the reservation has to be given back
        // however the open ends. Without the reservation a burst of flows all
        // read the same empty carrier set and open one connection each, which
        // is the whole of what Mux exists to avoid — measured at fifteen
        // connections for sixteen concurrent flows before it existed.
        var result = shards.placing { carrier, slot -> carrier.open(target, kind, flowId, firstPayload, slot) }

        // A carrier reaped or reset between placement and open is ordinary.
        // Failing the flow for it would surface as a page that intermittently
        // does not load, so it is retried once on a fresh placement.
        if (result is DecodeResult.Invalid && result.reason is MuxCarrierReason.TransportClosed) {
            result = shards.placing { carrier, slot -> carrier.open(target, kind, flowId, firstPayload, slot) }
        }
        return result
    }

    override fun close() {
        val toClose =
            synchronized(lock) {
                closed = true
                lanes.toList().also { lanes.clear() }
            }
        toClose.forEach { runCatching { it.close() } }
        runCatching { shards?.close() }
        runCatching { quic?.close() }
        runCatching { split?.close() }
    }

    /**
     * Wraps a flow so its id is released when the caller closes it.
     *
     * **Two wrappers, because one would erase an interface.** `Flow by delegate`
     * implements exactly `Flow`, so a [PacketFlow] handed through it comes out
     * the other side as an ordinary flow and a caller with a packet in hand
     * frames it for a stream that is not being used. That is not a type
     * subtlety — it is a UDP packet framed twice, and it cost a run of
     * datagram cases that failed on "a QUIC UDP flow must take packets".
     */
    private fun track(
        flow: Flow,
        flowId: UInt,
    ): Flow =
        if (flow is PacketFlow) {
            TrackedPacketFlow(flow, flowId)
        } else {
            TrackedFlow(flow, flowId)
        }

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

    private inner class TrackedPacketFlow(
        private val delegate: PacketFlow,
        private val trackedId: UInt,
    ) : PacketFlow by delegate {
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
