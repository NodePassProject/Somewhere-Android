// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRole
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.session.TransportKind
import eu.nodepass.somewhere.protocol.target.Target
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One TLS connection carrying many logical flows. NW-P-12 to NW-P-17,
 * `docs/protocol.md` sections 1 and 3.
 *
 * This is what L2 is for. At L1 a page with forty subresources opens forty TLS
 * connections to the Portal, each with its own handshake and its own
 * authentication frame; here it opens one and multiplexes.
 *
 * ```text
 * AuthFrame(32) ‖ 0xff ‖ MuxFrame ‖ MuxFrame ‖ ...
 * ```
 *
 * The marker is what switches the connection into Mux mode, and it works
 * because `0xff` cannot begin a FlowHeader — its role bits are `0b11`, which is
 * reserved. It belongs to the carrier and is not part of any frame. **The
 * Portal does not echo it**, so the inbound direction is frames from the first
 * byte.
 *
 * ## Two threads, and why not more
 *
 * A **reader** owns the transport's input: it is the only thing that reads, so
 * frame boundaries cannot be lost to a race. A **writer** owns the output and
 * drains a bounded queue. Callers never touch the transport; they hand payload
 * to a [Flow] and it reaches the queue only after passing both credit windows.
 *
 * Two rather than one because the two directions block independently — a reader
 * waiting on a quiet peer must not stop a stream from sending — and not more
 * than two because a second writer would interleave frames and a second reader
 * would split them.
 *
 * ## What closes the carrier, and what does not
 *
 * Closing the carrier fails every logical stream on it, so the bar is high and
 * the specification sets it: a DATAGRAM frame, a header that will not decode,
 * STREAM data for a flow that was never opened, and credit beyond the
 * advertised window. Each of those leaves the byte stream unresynchronisable or
 * is a peer this side cannot safely believe.
 *
 * A late WINDOW for a stream that has closed is **not** one of them — it is
 * ordinary, it is a frame that crossed a FIN in flight, and the specification
 * says to ignore it.
 */
class MuxCarrier(
    private val transport: Transport,
    private val sharedKey: SharedKey,
    private val sessionId: SessionId,
    /** Monotonic milliseconds. A parameter so idleness can be tested without waiting. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    /** One logical flow's state on this carrier. */
    private inner class Stream(
        val id: UInt,
        val target: Target,
        val kind: FlowKind,
    ) {
        /** Chunks from the peer. [EOF] marks a clean end; a failure is in [failure]. */
        val inbound = LinkedBlockingQueue<ByteArray>()

        val sendCredit = MuxCredit(MuxHeader.DEFAULT_STREAM_CREDIT)
        val receiveWindow = MuxReceiveWindow(MuxHeader.DEFAULT_STREAM_CREDIT)

        @Volatile var setupResult: SetupResult? = null

        /** Set once, and the reason every later read reports. */
        @Volatile var failure: DecodeReason? = null

        /** We have sent FIN or RST. Nothing more goes out. */
        val sendClosed = AtomicBoolean(false)

        /** The peer has sent FIN or RST. Nothing more comes in. */
        val receiveClosed = AtomicBoolean(false)

        /** Guards the exactly-once release of this stream's slot in the carrier. */
        val released = AtomicBoolean(false)

        /** Leftover from the last chunk, so a caller may read less than a frame. */
        var partial: ByteArray? = null
        var partialOffset = 0
    }

    private val streams = ConcurrentHashMap<UInt, Stream>()
    private val outbound = ArrayBlockingQueue<ByteArray>(MuxHeader.OUTBOUND_QUEUE_SLOTS)
    private val connectionCredit = MuxCredit(MuxHeader.DEFAULT_CONNECTION_CREDIT)
    private val connectionWindow = MuxReceiveWindow(MuxHeader.DEFAULT_CONNECTION_CREDIT)

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var closeReason: DecodeReason? = null

    @Volatile private var idleSince: Long = clock()

    private var reader: Thread? = null
    private var writer: Thread? = null

    /** Live flows, which is what shard placement is decided on. */
    val activeFlowCount: Int get() = streams.size

    val isOpen: Boolean get() = !closed.get() && transport.isOpen

    /** How long this carrier has had no flows at all. Zero while any is live. */
    fun idleMillis(): Long = if (streams.isNotEmpty()) 0 else clock() - idleSince

    /**
     * Authenticates and switches the connection into Mux mode.
     *
     * One write, for the same reason the dedicated lane uses one: the frame and
     * the marker in separate writes are two distinguishable packets with
     * client-side timing between them, on every connection, forever.
     */
    fun start(): DecodeResult<Unit> {
        if (!started.compareAndSet(false, true)) return invalid(MuxCarrierReason.AlreadyStarted)
        if (!transport.isOpen) return invalid(MuxCarrierReason.TransportClosed)

        val authTransport =
            when (transport.transportKind) {
                TransportKind.TlsTcp -> AuthTransport.TlsTcp
                // Mux frames never wrap QUIC (spec section 1). Reaching here
                // would mean a carrier was built over the wrong transport.
                TransportKind.Quic -> return invalid(MuxCarrierReason.QuicCannotCarryMux)
            }

        val frame =
            Authentication.encodeFrame(
                sharedKey = sharedKey,
                transport = authTransport,
                exporter = transport.exporter,
                sessionId = sessionId.toByteArray(),
            )

        return try {
            transport.write(frame + MuxHeader.MODE_MARKER)
            transport.flush()
            // The setup deadline comes off for the same reason it does on a
            // dedicated lane: afterwards, quiet is what a healthy carrier does.
            transport.setReadTimeout(0)
            reader =
                Thread({ readLoop() }, "mux-reader").apply {
                    isDaemon = true
                    start()
                }
            writer =
                Thread({ writeLoop() }, "mux-writer").apply {
                    isDaemon = true
                    start()
                }
            Unit.ok()
        } catch (error: Exception) {
            failCarrier(MuxCarrierReason.TransportFailed(error.javaClass.simpleName))
            invalid(MuxCarrierReason.TransportFailed(error.javaClass.simpleName))
        }
    }

    /**
     * Opens one logical flow on this carrier.
     *
     * The SYN frame carries `FlowHeader ‖ Target ‖ first payload`, which is the
     * same opening write a dedicated lane makes — the reconstructed logical
     * stream is defined to look identical, so the Portal's side of it is
     * unchanged.
     */
    fun open(
        target: Target,
        kind: FlowKind,
        flowId: UInt,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        if (!isOpen) return invalid(closeReason ?: MuxCarrierReason.TransportClosed)
        if (streams.size >= MuxHeader.MAX_ACTIVE_STREAMS) {
            return invalid(MuxCarrierReason.StreamLimit(MuxHeader.MAX_ACTIVE_STREAMS))
        }
        if (flowId == 0u) return invalid(MuxReason.StreamFlowIdZero)

        val header =
            when (val built = FlowHeader.forClient(FlowRole.Duplex, kind, FlowCarrier.TlsTcp, FlowCarrier.TlsTcp, flowId)) {
                is DecodeResult.Ok -> built.value
                is DecodeResult.Invalid -> return invalid(built.reason)
            }

        val stream = Stream(flowId, target, kind)
        if (streams.putIfAbsent(flowId, stream) != null) {
            return invalid(MuxCarrierReason.FlowIdInUse(flowId))
        }

        val opening = header.encode() + target.encode() + firstPayload
        if (!send(stream, opening, flags = MuxHeader.FLAG_SYN)) {
            releaseStream(stream)
            return invalid(stream.failure ?: closeReason ?: MuxCarrierReason.TransportClosed)
        }

        // The Portal's answer is the first byte of this stream's payload, in
        // exactly the place a dedicated lane reads it from the socket.
        val setup = ByteArray(1)
        if (readStream(stream, setup, 0, 1) != 1) {
            releaseStream(stream)
            return invalid(stream.failure ?: MuxCarrierReason.NoSetupByte)
        }

        val result =
            when (val decoded = SetupResult.decode(setup[0])) {
                is DecodeResult.Ok -> decoded.value
                is DecodeResult.Invalid -> {
                    failCarrier(decoded.reason)
                    return invalid(decoded.reason)
                }
            }
        stream.setupResult = result
        if (result.isRejection) {
            reset(stream)
            releaseStream(stream)
            return invalid(MuxCarrierReason.Rejected(result))
        }

        return MuxFlow(stream).ok()
    }

    /**
     * Queues [payload] as STREAM frames, splitting at the frame maximum.
     *
     * Both credit windows are taken before anything is queued, and in that
     * order — the specification's diagram, and the reason a stream cannot
     * reserve payload beyond either advertised window.
     *
     * @return false if the carrier or the stream went away while waiting.
     */
    private fun send(
        stream: Stream,
        payload: ByteArray,
        flags: Int,
    ): Boolean {
        var offset = 0
        // A SYN with no payload is still a frame: it is what opens the stream.
        do {
            val remaining = payload.size - offset
            val wanted = minOf(remaining, MuxHeader.MAX_STREAM_PAYLOAD)
            val take =
                if (wanted == 0) {
                    0
                } else {
                    // Take what the stream will give now, and only wait when it
                    // will give nothing: a short frame that moves beats a full
                    // one that waits for a window the peer may be slow to open.
                    val offered = stream.sendCredit.acquireAtMost(wanted)
                    val fromStream =
                        if (offered > 0) {
                            offered
                        } else {
                            if (!stream.sendCredit.acquire(1)) return false
                            1
                        }
                    if (!connectionCredit.acquire(fromStream)) return false
                    fromStream
                }

            val isLast = offset + take >= payload.size
            val frame =
                MuxHeader(
                    kind = MuxKind.Stream,
                    flags = if (offset == 0) flags else 0,
                    value = take,
                    flowId = stream.id,
                ).encode()

            val bytes = ByteArray(frame.size + take)
            frame.copyInto(bytes)
            if (take > 0) payload.copyInto(bytes, frame.size, offset, offset + take)
            if (!enqueue(bytes)) return false
            offset += take
        } while (offset < payload.size && !isLast)
        return true
    }

    /** Blocking put: a full queue is back-pressure, never a reason to drop stream bytes. */
    private fun enqueue(bytes: ByteArray): Boolean {
        while (isOpen) {
            if (outbound.offer(bytes, QUEUE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    // ── The reader ──────────────────────────────────────────────────────────

    private fun readLoop() {
        val header = ByteArray(MuxHeader.LENGTH)
        try {
            while (isOpen) {
                if (!readFully(header, header.size)) {
                    failCarrier(MuxCarrierReason.PeerClosed)
                    return
                }
                val frame =
                    when (val decoded = MuxHeader.decode(header)) {
                        is DecodeResult.Ok -> decoded.value
                        is DecodeResult.Invalid -> {
                            // Unresynchronisable: without a valid header the
                            // length of what follows is unknown, so there is no
                            // way to find the next frame boundary.
                            failCarrier(decoded.reason)
                            return
                        }
                    }
                if (!dispatch(frame)) return
            }
        } catch (error: Exception) {
            failCarrier(MuxCarrierReason.TransportFailed(error.javaClass.simpleName))
        }
    }

    /** @return false when the carrier has been closed and the loop must stop. */
    private fun dispatch(frame: MuxHeader): Boolean {
        when (frame.kind) {
            MuxKind.Window -> return window(frame)
            MuxKind.Stream -> return stream(frame)
            // Unreachable: the codec rejects DATAGRAM before it gets here, and
            // that rejection closes the carrier as unsupported.
            MuxKind.Datagram -> {
                failCarrier(MuxReason.DatagramUnsupported)
                return false
            }
        }
    }

    private fun window(frame: MuxHeader): Boolean {
        val credit = if (frame.isConnectionLevel) connectionCredit else streams[frame.flowId]?.sendCredit
        // A late stream-local WINDOW for a stream that has closed is ordinary —
        // a frame that crossed a FIN in flight — and the specification says to
        // ignore it rather than to treat it as an error.
        if (credit == null) return true
        return when (val released = credit.release(frame.value)) {
            is DecodeResult.Ok -> true
            is DecodeResult.Invalid -> {
                failCarrier(released.reason)
                false
            }
        }
    }

    private fun stream(frame: MuxHeader): Boolean {
        val payload = ByteArray(frame.value)
        if (frame.value > 0 && !readFully(payload, frame.value)) {
            failCarrier(MuxCarrierReason.PeerClosed)
            return false
        }

        val stream = streams[frame.flowId]
        if (stream == null) {
            // Three different things arrive here and only one is an error.
            //
            // A SYN is the Portal opening a stream, which this client does not
            // accept: it dials, it is not dialled.
            //
            // A bare FIN or RST is a frame that crossed this side's own close
            // in flight — the specification says late FIN and RST processing is
            // idempotent, and the first version of this class did not, so the
            // Portal's mirrored FIN tore down a carrier that was working.
            //
            // Anything else is *data* for a flow that was never opened, which
            // the specification does call a carrier error — and it has to be,
            // because the payload was already consumed to find the next frame
            // boundary, so there is no ignoring it and carrying on.
            if (!frame.isSyn && payload.isEmpty() && (frame.isFin || frame.isReset)) return true
            failCarrier(
                if (frame.isSyn) MuxCarrierReason.PeerOpenedAStream(frame.flowId) else MuxReason.UnknownFlow(frame.flowId),
            )
            return false
        }

        if (frame.isReset) {
            stream.failure = MuxCarrierReason.StreamReset(frame.flowId)
            stream.receiveClosed.set(true)
            stream.inbound.put(EOF)
            releaseStream(stream)
            return true
        }

        if (payload.isNotEmpty()) stream.inbound.put(payload)

        if (frame.isFin) {
            // Idempotent: a FIN after a FIN, or after a RST, changes nothing.
            if (stream.receiveClosed.compareAndSet(false, true)) stream.inbound.put(EOF)
            if (stream.sendClosed.get()) releaseStream(stream)
        }
        return true
    }

    private fun readFully(
        into: ByteArray,
        length: Int,
    ): Boolean {
        var filled = 0
        while (filled < length) {
            val read =
                try {
                    transport.read(into, filled, length - filled)
                } catch (_: Exception) {
                    return false
                }
            if (read < 0) return false
            filled += read
        }
        return true
    }

    // ── The writer ──────────────────────────────────────────────────────────

    private fun writeLoop() {
        try {
            while (isOpen) {
                val bytes = outbound.poll(QUEUE_WAIT_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                transport.write(bytes)
                transport.flush()
            }
        } catch (error: Exception) {
            failCarrier(MuxCarrierReason.TransportFailed(error.javaClass.simpleName))
        }
    }

    // ── Stream reading, from the caller's thread ────────────────────────────

    private fun readStream(
        stream: Stream,
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length <= 0) return 0
        var chunk = stream.partial
        if (chunk == null) {
            if (stream.receiveClosed.get() && stream.inbound.isEmpty()) return -1
            val next =
                stream.inbound.poll(READ_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                    ?: return if (isOpen && stream.failure == null) 0 else -1
            if (next === EOF) return -1
            chunk = next
            stream.partialOffset = 0
        }

        val available = chunk.size - stream.partialOffset
        val take = minOf(available, length)
        chunk.copyInto(into, offset, stream.partialOffset, stream.partialOffset + take)
        stream.partialOffset += take
        stream.partial = if (stream.partialOffset >= chunk.size) null else chunk

        returnCredit(stream, take)
        return take
    }

    /** Hands consumed bytes back to the peer, batched by both windows. */
    private fun returnCredit(
        stream: Stream,
        bytes: Int,
    ) {
        returnWindow(stream.id, stream.receiveWindow.consume(bytes))
        returnWindow(0u, connectionWindow.consume(bytes))
    }

    /**
     * Sends [credit] back, in as many frames as it takes.
     *
     * More than one because the `value` field is a u16 and the windows are
     * 512 KiB: a full window's worth of credit does not fit in a single frame.
     * The first version encoded it into one and lost the high bits, which the
     * peer would have read as a much smaller return — a transfer that stalls
     * short of the end and looks like a network that died.
     */
    private fun returnWindow(
        flowId: UInt,
        credit: Int,
    ) {
        var remaining = credit
        while (remaining > 0) {
            val take = minOf(remaining, MuxHeader.MAX_WINDOW_CREDIT)
            if (!enqueue(windowFrame(flowId, take))) return
            remaining -= take
        }
    }

    private fun windowFrame(
        flowId: UInt,
        credit: Int,
    ): ByteArray = MuxHeader(MuxKind.Window, flags = 0, value = credit, flowId = flowId).encode()

    // ── Teardown ────────────────────────────────────────────────────────────

    /** Half-closes: nothing more goes out, what is in flight still comes back. */
    private fun finish(stream: Stream) {
        if (!stream.sendClosed.compareAndSet(false, true)) return
        runCatching { returnWindow(stream.id, stream.receiveWindow.drain()) }
        runCatching {
            enqueue(MuxHeader(MuxKind.Stream, MuxHeader.FLAG_FIN, 0, stream.id).encode())
        }
        if (stream.receiveClosed.get()) releaseStream(stream)
    }

    /** RST is the only flag that may accompany it, and its value must be zero. */
    private fun reset(stream: Stream) {
        if (!stream.sendClosed.compareAndSet(false, true)) return
        runCatching {
            enqueue(MuxHeader(MuxKind.Stream, MuxHeader.FLAG_RST, 0, stream.id).encode())
        }
    }

    /** Gives back the carrier slot, exactly once however the stream ended. */
    private fun releaseStream(stream: Stream) {
        if (!stream.released.compareAndSet(false, true)) return
        streams.remove(stream.id, stream)
        stream.sendCredit.revoke()
        if (streams.isEmpty()) idleSince = clock()
    }

    /**
     * Closes the carrier and fails every stream on it.
     *
     * Each stream is failed with [reason] rather than with a generic message,
     * so that a caller can say why its flow ended — the specification requires
     * closing the carrier to fail every logical stream, and a report that all
     * of them "closed" says nothing about which of the four causes it was.
     */
    private fun failCarrier(reason: DecodeReason) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        connectionCredit.revoke()
        streams.values.forEach { stream ->
            if (stream.failure == null) stream.failure = reason
            stream.sendCredit.revoke()
            stream.receiveClosed.set(true)
            stream.inbound.offer(EOF)
        }
        streams.clear()
        runCatching { transport.close() }
    }

    override fun close() {
        failCarrier(closeReason ?: MuxCarrierReason.ClosedByCaller)
        reader?.interrupt()
        writer?.interrupt()
    }

    /** The [Flow] view of one stream. */
    private inner class MuxFlow(
        private val stream: Stream,
    ) : Flow {
        override val id: UInt get() = stream.id

        override val target: Target get() = stream.target

        override val kind: FlowKind get() = stream.kind

        override val setupResult: SetupResult? get() = stream.setupResult

        override val isOpen: Boolean get() = this@MuxCarrier.isOpen && !stream.sendClosed.get()

        override fun write(bytes: ByteArray) {
            if (stream.sendClosed.get()) throw java.io.IOException("the flow is half-closed")
            if (!send(stream, bytes, flags = 0)) {
                throw java.io.IOException((stream.failure ?: closeReason)?.detail ?: "the carrier closed")
            }
        }

        // Frames leave as soon as they are queued; there is nothing held back
        // for a flush to release.
        override fun flush() = Unit

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int = readStream(stream, into, offset, length)

        override fun close() {
            finish(stream)
            releaseStream(stream)
        }
    }

    private companion object {
        /** The sentinel that means "no more data on this stream". */
        val EOF = ByteArray(0)

        /**
         * How long a queue operation waits before checking whether the carrier
         * is still there.
         *
         * A plain blocking `put`/`take` would never notice a carrier that
         * closed underneath it — which is a hang rather than a failure, and the
         * one failure mode with nothing to report.
         */
        const val QUEUE_WAIT_MILLIS = 250L

        /** The same, for a caller blocked on a stream that has gone quiet. */
        const val READ_WAIT_MILLIS = 250L
    }
}

sealed interface MuxCarrierReason : DecodeReason {
    data object AlreadyStarted : MuxCarrierReason {
        override val detail: String = "this carrier has already been started"
    }

    data object TransportClosed : MuxCarrierReason {
        override val detail: String = "the transport closed before the carrier was established"
    }

    data object QuicCannotCarryMux : MuxCarrierReason {
        override val detail: String = "Mux frames never wrap QUIC"
    }

    data object ClosedByCaller : MuxCarrierReason {
        override val detail: String = "the carrier was closed by this client"
    }

    data object PeerClosed : MuxCarrierReason {
        override val detail: String = "the Portal closed the Mux carrier"
    }

    data object NoSetupByte : MuxCarrierReason {
        override val detail: String =
            "the Portal did not answer on this stream — authentication most likely failed"
    }

    data class TransportFailed(
        val kind: String,
    ) : MuxCarrierReason {
        override val detail: String = "the Mux carrier's transport failed with $kind"
    }

    data class StreamLimit(
        val limit: Int,
    ) : MuxCarrierReason {
        override val detail: String = "this carrier already holds its $limit active streams"
    }

    data class FlowIdInUse(
        val flowId: UInt,
    ) : MuxCarrierReason {
        override val detail: String = "flow $flowId is already open on this carrier"
    }

    data class StreamReset(
        val flowId: UInt,
    ) : MuxCarrierReason {
        override val detail: String = "the Portal reset flow $flowId"
    }

    data class PeerOpenedAStream(
        val flowId: UInt,
    ) : MuxCarrierReason {
        override val detail: String =
            "the Portal opened stream $flowId; this client dials and is not dialled"
    }

    data class Rejected(
        val result: SetupResult,
    ) : MuxCarrierReason {
        override val detail: String = "the Portal refused the flow: ${result.name}"
    }
}
