// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.session.TransportKind
import eu.nodepass.somewhere.protocol.target.Target
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A [Transport] over a pair of streams, so two halves can be wired together. */
class LoopbackTransport(
    private val input: InputStream,
    private val output: OutputStream,
    override val exporter: ByteArray = ByteArray(32) { it.toByte() },
    override val transportKind: TransportKind = TransportKind.TlsTcp,
) : Transport {
    @Volatile private var open = true

    override val isOpen: Boolean get() = open

    override fun write(bytes: ByteArray) {
        if (!open) throw IOException("write on a closed transport")
        output.write(bytes)
        output.flush()
    }

    override fun flush() = output.flush()

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (!open) return -1
        return input.read(into, offset, length)
    }

    override fun close() {
        open = false
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        /**
         * Two transports whose writes are each other's reads.
         *
         * The pipe buffers are large because a Mux carrier writes a whole frame
         * at a time and the peer may not be reading yet — a default 1 KiB pipe
         * would deadlock on the first 32 KiB frame, which is a property of the
         * test rig rather than of anything under test.
         */
        fun pair(bufferBytes: Int = 1 shl 20): Pair<LoopbackTransport, LoopbackTransport> {
            val clientOut = PipedOutputStream()
            val portalIn = PipedInputStream(clientOut, bufferBytes)
            val portalOut = PipedOutputStream()
            val clientIn = PipedInputStream(portalOut, bufferBytes)
            return LoopbackTransport(clientIn, clientOut) to LoopbackTransport(portalIn, portalOut)
        }
    }
}

/**
 * A Portal that speaks Mux, for the carrier's tests.
 *
 * Enough of one to be a real peer: it reads the authentication frame and the
 * marker, decodes frames, answers a SYN with a `SetupResult`, echoes payload,
 * mirrors FIN, and returns credit. What it is *for* is the rest — it can be
 * told to do each of the things a carrier has to survive, on cue, which against
 * a real Portal is rare, timing dependent and impossible to demand.
 *
 * It is deliberately not a second implementation of the client's own logic.
 * Where the two would have to agree, it works from the specification's bytes
 * directly, so a test cannot pass because both sides share a misreading.
 */
class FakeMuxPortal(
    private val transport: Transport,
    /** What to answer a SYN with. Default accepts. */
    private val setupResult: (Target) -> SetupResult = { SetupResult.Ready },
    /** Called once the marker has been read, before any frame is answered. */
    private val onReady: (FakeMuxPortal) -> Unit = {},
    /** Called for each STREAM frame that carries payload after the opening one. */
    private val onPayload: (FakeMuxPortal, UInt, ByteArray) -> Unit = { portal, id, bytes -> portal.sendStream(id, bytes) },
) {
    /** Every frame header the client sent, in order. */
    val received: MutableList<MuxHeader> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * A snapshot of [received], safe to iterate.
     *
     * A synchronized list synchronises its own operations and not iteration
     * over it, so a test reading the list while the Portal thread appends gets
     * a ConcurrentModificationException — which reads as a flake rather than as
     * the missing lock it is.
     */
    fun frames(): List<MuxHeader> = synchronized(received) { received.toList() }

    /** Payload bytes the client sent per flow, concatenated. */
    val payloadByFlow: ConcurrentHashMap<UInt, ByteArray> = ConcurrentHashMap()

    /** The 32-byte authentication frame, and the byte that followed it. */
    @Volatile var authFrame: ByteArray? = null

    @Volatile var markerByte: Int = -1

    /** Opening writes, decoded — what the reconstructed logical stream carried. */
    val opened: ConcurrentHashMap<UInt, Target> = ConcurrentHashMap()

    val windowFramesSent = AtomicInteger(0)

    /** What the client has advertised it will read, per stream and overall. */
    private val streamCredit = mutableMapOf<UInt, Long>()

    private var connectionCredit = MuxHeader.DEFAULT_CONNECTION_CREDIT.toLong()
    private val creditLock = Object()

    @Volatile private var running = true

    @Volatile var stopped = false
        private set

    private var worker: Thread? = null

    /**
     * Payload handlers run here, off the read loop.
     *
     * Necessary now that sending respects credit: a handler that blocks waiting
     * for a WINDOW would, on the read loop, be blocking the only thread that
     * could ever read that WINDOW. A real Portal does not have that problem
     * because its directions are independent; the first version of this fake
     * did, and it deadlocked a transfer at exactly one window.
     *
     * Single-threaded, so a flow's frames stay in the order they were produced.
     */
    private val senders =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-mux-portal-send").apply { isDaemon = true }
        }

    fun start() {
        worker = thread(isDaemon = true, name = "fake-mux-portal") { serve() }
    }

    fun stop() {
        running = false
        synchronized(creditLock) { creditLock.notifyAll() }
        senders.shutdownNow()
        runCatching { transport.close() }
    }

    // ── Things a test can make the Portal do ────────────────────────────────

    /**
     * Sends stream payload, **respecting the credit the client advertised**.
     *
     * This is what makes the fake a peer rather than a firehose. Without it the
     * whole of a response lands in the pipe buffer before the client reads a
     * byte, and every flow-control assertion downstream is satisfied by the
     * buffer rather than by the protocol — which is exactly how a defect in the
     * client's WINDOW frames survived a test that looked like it covered them.
     *
     * Blocks until credit arrives, or until the Portal is stopped.
     */
    fun sendStream(
        flowId: UInt,
        payload: ByteArray,
        flags: Int = 0,
    ) {
        var offset = 0
        do {
            val remaining = payload.size - offset
            val take = if (remaining == 0) 0 else takeCredit(flowId, minOf(remaining, MuxHeader.MAX_STREAM_PAYLOAD))
            if (take < 0) return
            val header = MuxHeader(MuxKind.Stream, if (offset == 0) flags else 0, take, flowId).encode()
            write(header + payload.copyOfRange(offset, offset + take))
            offset += take
        } while (offset < payload.size)
    }

    /**
     * Waits for [wanted] bytes of both windows, returning what it got.
     *
     * @return -1 once the Portal has been stopped, so a blocked send unwinds
     *   rather than hanging a test.
     */
    private fun takeCredit(
        flowId: UInt,
        wanted: Int,
    ): Int =
        synchronized(creditLock) {
            while (running) {
                val stream = streamCredit.getOrPut(flowId) { MuxHeader.DEFAULT_STREAM_CREDIT.toLong() }
                val take = minOf(wanted.toLong(), stream, connectionCredit)
                if (take > 0) {
                    streamCredit[flowId] = stream - take
                    connectionCredit -= take
                    return take.toInt()
                }
                creditLock.wait(200)
            }
            -1
        }

    fun sendFin(flowId: UInt) = write(MuxHeader(MuxKind.Stream, MuxHeader.FLAG_FIN, 0, flowId).encode())

    fun sendReset(flowId: UInt) = write(MuxHeader(MuxKind.Stream, MuxHeader.FLAG_RST, 0, flowId).encode())

    fun sendWindow(
        flowId: UInt,
        credit: Int,
    ) {
        windowFramesSent.incrementAndGet()
        write(MuxHeader(MuxKind.Window, 0, credit, flowId).encode())
    }

    /** A DATAGRAM frame, which the specification says closes the carrier. */
    fun sendDatagram(flowId: UInt) = write(byteArrayOf(MuxKind.Datagram.byte.toByte(), 0, 0, 0) + flowIdBytes(flowId))

    /** Raw bytes, for the frames a valid encoder will not produce. */
    fun write(bytes: ByteArray) {
        runCatching {
            transport.write(bytes)
            transport.flush()
        }
    }

    private fun flowIdBytes(flowId: UInt) =
        byteArrayOf(
            (flowId shr 24).toByte(),
            (flowId shr 16).toByte(),
            (flowId shr 8).toByte(),
            flowId.toByte(),
        )

    // ── The loop ────────────────────────────────────────────────────────────

    private fun serve() {
        try {
            val frame = ByteArray(32)
            if (!readFully(frame, frame.size)) return
            authFrame = frame

            val marker = ByteArray(1)
            if (!readFully(marker, 1)) return
            markerByte = marker[0].toInt() and 0xFF
            onReady(this)

            val header = ByteArray(MuxHeader.LENGTH)
            while (running) {
                if (!readFully(header, header.size)) break
                val decoded = MuxHeader.decode(header)
                val muxHeader =
                    when (decoded) {
                        is DecodeResult.Ok -> decoded.value
                        // A frame this Portal cannot decode is one the test
                        // wrote deliberately; record nothing and stop, the way
                        // a real Portal closes an unresynchronisable carrier.
                        is DecodeResult.Invalid -> break
                    }
                received += muxHeader

                val payload = ByteArray(muxHeader.value)
                if (muxHeader.kind == MuxKind.Stream && muxHeader.value > 0) {
                    if (!readFully(payload, muxHeader.value)) break
                }
                handle(muxHeader, payload)
            }
        } catch (_: Exception) {
            // The client closed, or a test closed the transport underneath.
        } finally {
            stopped = true
        }
    }

    private fun handle(
        header: MuxHeader,
        payload: ByteArray,
    ) {
        if (header.kind == MuxKind.Window) {
            synchronized(creditLock) {
                if (header.isConnectionLevel) {
                    connectionCredit += header.value
                } else {
                    streamCredit[header.flowId] =
                        (streamCredit[header.flowId] ?: MuxHeader.DEFAULT_STREAM_CREDIT.toLong()) + header.value
                }
                creditLock.notifyAll()
            }
            return
        }

        if (header.isSyn) {
            // The reconstructed logical stream: FlowHeader ‖ Target ‖ payload.
            val flowHeader = FlowHeader.decode(payload.copyOfRange(0, FlowHeader.LENGTH))
            val target = Target.decode(payload, FlowHeader.LENGTH)
            if (flowHeader is DecodeResult.Ok && target is DecodeResult.Ok) {
                opened[header.flowId] = target.value.target
                val result = setupResult(target.value.target)
                sendStream(header.flowId, byteArrayOf(result.byte.toByte()))
                if (result.isRejection) return
                val consumed = FlowHeader.LENGTH + target.value.consumed
                val rest = payload.copyOfRange(consumed, payload.size)
                if (rest.isNotEmpty()) {
                    record(header.flowId, rest)
                    submit { onPayload(this, header.flowId, rest) }
                }
            }
            return
        }

        if (payload.isNotEmpty()) {
            record(header.flowId, payload)
            submit { onPayload(this, header.flowId, payload) }
        }
        if (header.isFin) submit { sendFin(header.flowId) }
    }

    private fun submit(action: () -> Unit) {
        runCatching { senders.execute { runCatching(action) } }
    }

    private fun record(
        flowId: UInt,
        bytes: ByteArray,
    ) {
        payloadByFlow.compute(flowId) { _, existing -> (existing ?: ByteArray(0)) + bytes }
    }

    private fun readFully(
        into: ByteArray,
        length: Int,
    ): Boolean {
        var filled = 0
        while (filled < length) {
            val read = transport.read(into, filled, length - filled)
            if (read < 0) return false
            filled += read
        }
        return true
    }
}
