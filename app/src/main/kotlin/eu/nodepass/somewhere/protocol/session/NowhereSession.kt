// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.target.Target
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * One client's flows against one Portal, over the one carrier its node names.
 *
 * ## Two carriers, and the node chooses
 *
 * - **TLS** (`net=tcp`): every flow is its own connection, authenticated on
 *   its own, closed with the flow. `docs/protocol.md`: "TLS uses one connection
 *   per Flow."
 * - **QUIC** (`net=udp`): one connection, authenticated once, every flow a
 *   bidirectional stream on it.
 *
 * Present [quicStreams] and it is QUIC; absent, it is TLS. There is no third
 * shape. v1 had two more — a Mux carrier multiplexing flows over TLS, and split
 * flows whose two directions rode different carriers and were paired by a
 * session id — and v3 removed both, which is also why this class no longer
 * owns a session id or a flow-id space. Over QUIC the flow id is derived from
 * the stream; over TLS there is none.
 *
 * Deliberately **not** a connection pool: the 1.7 `pool` parameter was removed
 * in 1.8 and nothing in v3 brings it back.
 *
 * ## Concurrency
 *
 * [openFlow] is safe from several threads. A TLS lane is used by exactly one
 * flow; the QUIC carrier serialises what has to be serialised on its own.
 */
class NowhereSession(
    private val sharedKey: SharedKey,
    private val connect: TransportFactory,
    /**
     * Present when the node's `net` is `udp`, and then it decides. A factory of
     * *streams* rather than of carriers, so that the carrier — which knows
     * whether this connection has authenticated yet — is built here, once.
     */
    private val quicStreams: QuicCarrier.StreamFactory? = null,
    /**
     * The QUIC connection's DATAGRAM side. Absent means UDP flows are refused
     * over QUIC rather than carried over a stream: the specification puts them
     * in DATAGRAMs, and a Portal is not listening for anything else.
     */
    private val quicDatagrams: QuicCarrier.Datagrams? = null,
) : Closeable {
    /** Opens a fresh TLS connection to the Portal, ready to authenticate. */
    fun interface TransportFactory {
        fun connect(): Transport
    }

    private val quic: QuicCarrier? = quicStreams?.let { QuicCarrier(it, sharedKey, quicDatagrams) }

    private val lanes = mutableListOf<DedicatedTlsLane>()
    private val lock = Any()
    private var closed = false
    private val live = AtomicInteger(0)
    private val tlsSequence = AtomicInteger(0)

    /**
     * How many connections this session is holding: one per open flow over
     * TLS, and exactly one over QUIC. The measurement that tells the two
     * carriers apart from outside.
     */
    val carrierCount: Int
        get() = if (quic != null) 1 else synchronized(lock) { lanes.size }

    val liveFlowCount: Int get() = live.get()

    /**
     * Whether a UDP flow from this session takes packets rather than a framed
     * byte stream. True exactly when the carrier is QUIC.
     *
     * Asked rather than inferred: a caller with a packet in hand needs to know
     * whether to frame it, and working that out from the node's parameters
     * would put the same decision in two places.
     */
    val carriesPackets: Boolean get() = quic != null

    /**
     * Opens one flow to [target].
     *
     * @param firstPayload the client's first bytes, if the caller already has
     *   them. Sent once the Portal answers READY — never in the opening write,
     *   which v3 forbids.
     */
    fun openFlow(
        target: Target,
        kind: FlowKind = FlowKind.Tcp,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        check(!closed) { "session is closed" }
        val opened =
            if (quic != null) {
                quic.openFlow(target, kind, firstPayload)
            } else {
                openDedicated(target, kind, firstPayload)
            }
        return when (opened) {
            is DecodeResult.Ok -> {
                live.incrementAndGet()
                DecodeResult.Ok(track(opened.value))
            }
            is DecodeResult.Invalid -> opened
        }
    }

    /** One TLS connection, one flow, closed together. */
    private fun openDedicated(
        target: Target,
        kind: FlowKind,
        firstPayload: ByteArray,
    ): DecodeResult<Flow> {
        val lane = DedicatedTlsLane(connect.connect(), sharedKey)
        synchronized(lock) { lanes.add(lane) }

        val logId = tlsSequence.incrementAndGet().toUInt()
        return when (val opened = lane.open(target, kind, logId, firstPayload)) {
            is DecodeResult.Ok -> opened
            is DecodeResult.Invalid -> {
                lane.close()
                synchronized(lock) { lanes.remove(lane) }
                opened
            }
        }
    }

    override fun close() {
        val toClose =
            synchronized(lock) {
                closed = true
                lanes.toList().also { lanes.clear() }
            }
        toClose.forEach { runCatching { it.close() } }
        runCatching { quic?.close() }
    }

    /**
     * Wraps a flow so the live count falls when the caller closes it.
     *
     * **Two wrappers, because one would erase an interface.** `Flow by delegate`
     * implements exactly `Flow`, so a [PacketFlow] handed through it comes out
     * the other side as an ordinary flow and a caller with a packet in hand
     * frames it for a stream that is not being used.
     */
    private fun track(flow: Flow): Flow =
        if (flow is PacketFlow) {
            TrackedPacketFlow(flow)
        } else {
            TrackedFlow(flow)
        }

    private fun released(flow: Flow) {
        live.decrementAndGet()
        if (quic == null) {
            synchronized(lock) { lanes.removeAll { it.carries(flow) } }
        }
    }

    private inner class TrackedFlow(
        private val delegate: Flow,
    ) : Flow by delegate {
        private var done = false

        override fun close() {
            if (!done) {
                done = true
                released(delegate)
            }
            delegate.close()
        }
    }

    private inner class TrackedPacketFlow(
        private val delegate: PacketFlow,
    ) : PacketFlow by delegate {
        private var done = false

        override fun close() {
            if (!done) {
                done = true
                released(delegate)
            }
            delegate.close()
        }
    }
}
