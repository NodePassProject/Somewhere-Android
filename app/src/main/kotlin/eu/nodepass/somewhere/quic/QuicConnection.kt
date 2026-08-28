// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Why a connection could not be opened, or could not be driven further. */
sealed interface QuicFailure {
    val detail: String

    /** The socket could not be kept out of the tunnel, so it was not used. */
    data object NotProtected : QuicFailure {
        override val detail: String =
            "the socket could not be protected, and an unprotected socket routes back into the tunnel"
    }

    /** The native side refused to construct a connection at all. */
    data class NotOpened(
        val cause: String,
    ) : QuicFailure {
        override val detail: String = "the QUIC connection could not be created: $cause"
    }

    /** The transport reported an error, or the peer did. */
    data class Transport(
        val cause: String,
    ) : QuicFailure {
        override val detail: String = cause
    }

    /** The handshake did not finish inside the time allowed. */
    data object HandshakeTimeout : QuicFailure {
        override val detail: String = "the peer did not complete the QUIC handshake in time"
    }

    /** The connection has been closed, by this side or by the peer. */
    data class Closed(
        val cause: String,
    ) : QuicFailure {
        override val detail: String = cause
    }
}

class QuicException(
    val failure: QuicFailure,
) : Exception(failure.detail)

/**
 * One QUIC connection to one Portal, with its own thread.
 *
 * ## Why there is a thread, and why it is not optional
 *
 * `ngtcp2_conn` is not thread-safe, and the bridge does not merely document
 * that — it compares `pthread_self()` against the thread that opened the
 * connection and refuses everything else. A lock cannot satisfy that check,
 * because the check is about identity rather than exclusion.
 *
 * Meanwhile a QUIC connection carries many flows at once — that is the whole
 * reason to have one — and [eu.nodepass.somewhere.protocol.session.NowhereSession]
 * documents `openFlow` as safe from several threads. Something has to reconcile
 * those, and serialising on the caller's thread would not: a thread blocked
 * reading one flow would stop every other flow on the connection.
 *
 * So one thread owns the connection and runs a loop: drain the work other
 * threads have queued, pump the socket once, then move whatever arrived into
 * per-stream queues. Callers never touch the native side directly. A read is a
 * queue poll and blocks nobody; a write is a queued task.
 *
 * ## The socket is here, and the bridge cannot reach it
 *
 * Inside a VPN every outbound socket must be `VpnService.protect()`-ed or it
 * routes back into the TUN, reaches lwIP, and is dialled again — a loop that
 * looks like a hang and takes the device with it. ngtcp2 never owns a socket:
 * it is fed the datagrams that arrived and produces the ones to send. So the
 * socket lives here, under the same rule [eu.nodepass.somewhere.routing.DirectDialer]
 * keeps, and the bridge contains no networking function at all — not even
 * address parsing. `checkNativeBridge` greps for them.
 *
 * **The order differs from the TCP case.** `Socket()` has no file descriptor
 * until it is bound or connected, which is why the TCP path is bind, protect,
 * connect. `DatagramSocket()` binds on construction, so it is construct,
 * protect, connect — and `connect` still comes last, because only then does the
 * socket know which local address it chose, which is the address ngtcp2's path
 * is built from.
 */
class QuicConnection private constructor(
    private val remote: InetSocketAddress,
    private val alpn: String,
    private val serverName: String?,
    private val protect: (DatagramSocket) -> Boolean,
    private val openSocket: () -> DatagramSocket,
) : Closeable {
    private val work = LinkedBlockingQueue<FutureTask<*>>()
    private val streams = ConcurrentHashMap<Long, StreamQueue>()
    private val ready = CountDownLatch(1)
    private val startupFailure = AtomicReference<Throwable?>(null)
    private val fatal = AtomicReference<QuicFailure?>(null)

    /**
     * Created on the owning thread, like everything else here.
     *
     * `DatagramSocket.connect` counts as network under StrictMode, and
     * `startTunnel` runs on the main thread — so opening the socket where the
     * caller happens to be throws `NetworkOnMainThreadException`. The TLS path
     * never met this because its dialling is lazy and happens on a flow's own
     * thread. Doing it here is also simply where it belongs: the bridge wants
     * the handle created by the thread that will use it, and the socket is
     * half of the same object.
     */
    private lateinit var socket: DatagramSocket

    @Volatile private var handle: Long = 0

    @Volatile private var running = true

    @Volatile private var handshakeDone = false

    private val owner =
        Thread({ run() }, "quic-${remote.hostString}:${remote.port}").apply {
            isDaemon = true
        }

    /** What a stream has received, and whether the peer is finished with it. */
    private class StreamQueue {
        val arrived = LinkedBlockingQueue<ByteArray>()

        @Volatile var finished = false
        var leftover: ByteArray = EMPTY
        var leftoverOffset = 0

        companion object {
            val EMPTY = ByteArray(0)
        }
    }

    // ── the owning thread ───────────────────────────────────────────────────

    private fun run() {
        val out = ByteArray(MAX_DATAGRAM)
        val incoming = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(incoming, incoming.size)
        val harvest = ByteArray(HARVEST_BYTES)

        try {
            socket = openSocket()
            // Already bound, so there is a descriptor to protect. The TCP path
            // has to bind first; conflating the two is how that rule went wrong
            // the first time.
            if (!protect(socket)) throw QuicException(QuicFailure.NotProtected)
            // Last, because after it the routing decision is made — and because
            // only then does the socket know which local address it chose,
            // which is the address ngtcp2's path is built from.
            socket.connect(remote)
            // A Portal sends a large transfer as fast as its congestion window
            // allows, and the kernel's default receive buffer is a few hundred
            // kilobytes. Whatever this loop does not take in time is dropped,
            // QUIC retransmits it, and the transfer collapses into a stall that
            // looks like a dead network. Asking for more is a request, not a
            // guarantee — the OS may cap it — which is why the loop also drains
            // in bursts rather than one datagram at a time.
            runCatching { socket.receiveBufferSize = RECEIVE_BUFFER_BYTES }

            handle =
                nativeOpen(
                    socket.localAddress.address,
                    socket.localPort,
                    remote.address.address,
                    remote.port,
                    serverName,
                    alpn.toByteArray(Charsets.US_ASCII),
                )
            if (handle == 0L) {
                throw QuicException(QuicFailure.NotOpened("the transport refused the parameters"))
            }
        } catch (failure: Throwable) {
            startupFailure.set(failure)
            runCatching { if (::socket.isInitialized) socket.close() }
            ready.countDown()
            return
        }
        ready.countDown()

        try {
            while (running) {
                drainWork()
                flush(out)
                receiveBurst(packet, incoming)
                harvestStreams(harvest)
                if (!handshakeDone && nativeHandshakeCompleted(handle) == 1) {
                    handshakeDone = true
                }
            }
        } catch (failure: Throwable) {
            fatal.set(
                (failure as? QuicException)?.failure
                    ?: QuicFailure.Transport(failure.message ?: failure.toString()),
            )
        } finally {
            running = false
            // Wake anything waiting on a queue that will never fill again.
            streams.values.forEach { it.finished = true }
            drainWork()
            nativeClose(handle)
            handle = 0
        }
    }

    private fun drainWork() {
        while (true) {
            val task = work.poll() ?: return
            task.run()
        }
    }

    private fun flush(out: ByteArray) {
        while (true) {
            val written = nativeWrite(handle, out)
            if (written == 0) return
            require(written)
            socket.send(DatagramPacket(out, written))
        }
    }

    /**
     * Takes everything the socket already has, then waits for one more.
     *
     * One datagram per pass was the first version and it does not survive a
     * real transfer: twenty megabytes is fourteen thousand datagrams, and a
     * loop that takes one per pass leaves the rest in a kernel buffer that
     * overflows. The drop is invisible from here — QUIC simply retransmits,
     * the congestion window collapses, and the transfer stops making progress
     * with nothing anywhere reporting an error.
     *
     * The burst is bounded so that a fast sender cannot keep this loop from
     * ever reaching the work queue or the timers.
     */
    private fun receiveBurst(
        packet: DatagramPacket,
        incoming: ByteArray,
    ) {
        var taken = 0
        while (taken < RECEIVE_BURST) {
            // Only the first read waits; the rest take what is already there.
            val wait = if (taken == 0) waitMillis().coerceIn(1L, POLL_MILLIS).toInt() else 1
            if (socket.soTimeout != wait) socket.soTimeout = wait
            try {
                packet.length = incoming.size
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (taken == 0) require(nativeHandleExpiry(handle))
                return
            }
            require(nativeReceive(handle, incoming, packet.length))
            taken++
        }
    }

    /**
     * Moves what arrived into per-stream queues.
     *
     * Done on this thread because reading is a native call, and handed to
     * callers as ordinary Kotlin bytes so that a blocked reader blocks only
     * itself. Credit is returned inside the native read, which matters: L2
     * shipped the version of that defect where returned credit was truncated
     * and a transfer stalled short of the end looking like a dead network.
     */
    private fun harvestStreams(buffer: ByteArray) {
        streams.forEach { (id, queue) ->
            while (true) {
                val n = nativeRead(handle, id, buffer, 0, buffer.size)
                if (n > 0) {
                    queue.arrived.put(buffer.copyOf(n))
                    continue
                }
                if (n == -1) queue.finished = true
                break
            }
        }
    }

    private fun waitMillis(): Long {
        val expiry = nativeExpiry(handle)
        if (expiry < 0) return POLL_MILLIS
        return ((expiry - nativeNow()) / NANOS_PER_MILLI).coerceIn(1L, POLL_MILLIS)
    }

    private fun require(result: Int) {
        if (result < 0) {
            throw QuicException(QuicFailure.Transport(nativeLastMessage(handle) ?: "the transport failed with $result"))
        }
    }

    // ── what other threads call ─────────────────────────────────────────────

    private fun <T> onOwner(body: () -> T): T {
        fatal.get()?.let { throw QuicException(it) }
        if (!running) throw QuicException(QuicFailure.Closed("the QUIC connection is closed"))
        if (Thread.currentThread() === owner) return body()

        // No nudge: the loop's own wait is capped at POLL_MILLIS, so a queued
        // task waits at most that long. Reaching across to touch the socket's
        // timeout from another thread would be a race for the sake of a few
        // milliseconds.
        val task = FutureTask(body)
        work.put(task)
        return try {
            task.get(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw failure.cause ?: failure
        } catch (_: java.util.concurrent.TimeoutException) {
            throw QuicException(QuicFailure.Closed("the QUIC connection stopped answering"))
        }
    }

    /** Waits for the handshake, driven by the owning thread. */
    fun completeHandshake(timeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (!handshakeDone) {
            fatal.get()?.let { throw QuicException(it) }
            if (!running) throw QuicException(QuicFailure.Closed("the connection closed during the handshake"))
            if (System.nanoTime() >= deadline) throw QuicException(QuicFailure.HandshakeTimeout)
            Thread.sleep(HANDSHAKE_POLL_MILLIS)
        }
    }

    val handshakeCompleted: Boolean get() = handshakeDone

    /**
     * Opens one client-initiated bidirectional stream.
     *
     * Unidirectional streams are never opened: the specification does not use
     * them, this client advertises `initial_max_streams_uni = 0`, and
     * `checkNativeBridge` fails the build if the bridge ever calls for one.
     *
     * Before authentication the peer credits exactly one bidirectional stream
     * (NW-P-19), so a second one fails here rather than opening and stalling.
     * That refusal is left visible to the caller instead of being retried
     * behind its back.
     */
    fun openStream(timeoutMillis: Long = DEFAULT_STREAM_TIMEOUT_MILLIS): Long {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (true) {
            val id =
                onOwner {
                    val opened = nativeOpenStream(handle)
                    if (opened < 0 && opened != STREAM_BLOCKED) {
                        throw QuicException(
                            QuicFailure.Transport(nativeLastMessage(handle) ?: "no stream could be opened"),
                        )
                    }
                    if (opened >= 0) streams[opened] = StreamQueue()
                    opened
                }
            if (id >= 0) return id

            // Waiting, not failing. NW-P-19 credits exactly one bidirectional
            // stream before authentication, so a second flow opened early finds
            // none available and must wait for the peer to extend the limit —
            // the alternative the specification warns against is opening one
            // anyway and stalling. The wait happens here rather than inside the
            // owner, because the owner is what pumps, and the extension cannot
            // arrive while it is blocked.
            if (System.nanoTime() >= deadline) {
                throw QuicException(
                    QuicFailure.Transport(
                        "the peer credited no further bidirectional stream within ${timeoutMillis}ms",
                    ),
                )
            }
            Thread.sleep(STREAM_POLL_MILLIS)
        }
    }

    /** Queues bytes on a stream; they leave on the owning thread's next pass. */
    fun send(
        streamId: Long,
        bytes: ByteArray,
        fin: Boolean = false,
    ) {
        onOwner {
            val rv = nativeSend(handle, streamId, bytes, bytes.size, fin)
            if (rv < 0) {
                throw QuicException(QuicFailure.Transport(nativeLastMessage(handle) ?: "the stream refused $rv"))
            }
        }
    }

    /**
     * Takes what has arrived on a stream, waiting up to [timeoutMillis].
     *
     * @return the count copied, or `-1` at a clean end of stream — the contract
     *   [eu.nodepass.somewhere.protocol.session.Transport] already has.
     * @throws SocketTimeoutException when nothing arrives in time, which is the
     *   same signal a socket gives and the shape a caller already handles: a
     *   rejected AuthFrame is answered with silence rather than a close.
     */
    fun receive(
        streamId: Long,
        into: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Long,
    ): Int {
        val queue = streams[streamId] ?: throw QuicException(QuicFailure.Transport("no such stream"))
        val deadline = if (timeoutMillis <= 0) Long.MAX_VALUE else System.nanoTime() + timeoutMillis * NANOS_PER_MILLI

        while (true) {
            if (queue.leftoverOffset < queue.leftover.size) {
                val take = minOf(length, queue.leftover.size - queue.leftoverOffset)
                queue.leftover.copyInto(into, offset, queue.leftoverOffset, queue.leftoverOffset + take)
                queue.leftoverOffset += take
                return take
            }
            val chunk = queue.arrived.poll()
            if (chunk != null) {
                queue.leftover = chunk
                queue.leftoverOffset = 0
                continue
            }
            fatal.get()?.let { throw QuicException(it) }
            if (queue.finished) return -1
            if (!running) return -1
            if (System.nanoTime() >= deadline) {
                throw SocketTimeoutException("no bytes on stream $streamId within ${timeoutMillis}ms")
            }
            // Waiting on the queue rather than on the socket: the owning thread
            // is the only one that may touch the connection, and it is already
            // pumping.
            queue.arrived.poll(READ_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let {
                queue.leftover = it
                queue.leftoverOffset = 0
            }
        }
    }

    /** RFC 5705 keying material, which is what NW-P-01 authenticates with. */
    fun exportKeyingMaterial(
        label: String,
        length: Int,
    ): ByteArray =
        onOwner {
            nativeExportKeyingMaterial(handle, label.toByteArray(Charsets.US_ASCII), length)
                ?: throw QuicException(QuicFailure.Transport(nativeLastMessage(handle) ?: "the exporter refused"))
        }

    override fun close() {
        if (!running) return
        running = false
        owner.join(CLOSE_JOIN_MILLIS)
        // After the loop has stopped, nothing can be inside the bridge, so the
        // socket can go. Closing it first would race a pump already in flight.
        runCatching { if (::socket.isInitialized) socket.close() }
    }

    companion object {
        /**
         * The largest datagram this client will send or accept in one piece.
         * 1452 is ngtcp2's own default send size on a 1500-byte path, and the
         * bridge refuses a buffer smaller than that rather than truncating.
         */
        const val MAX_DATAGRAM = 1452

        private const val HARVEST_BYTES = 64 * 1024
        private const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000L
        private const val POLL_MILLIS = 25L

        /** Datagrams taken per pass before the loop looks at anything else. */
        private const val RECEIVE_BURST = 64
        private const val RECEIVE_BUFFER_BYTES = 4 * 1024 * 1024
        private const val READ_POLL_MILLIS = 25L
        private const val HANDSHAKE_POLL_MILLIS = 5L
        private const val TASK_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_STREAM_TIMEOUT_MILLIS = 15_000L
        private const val STREAM_POLL_MILLIS = 2L

        /** The bridge's "not yet credited", which is not a failure. */
        private const val STREAM_BLOCKED = -1003L
        private const val CLOSE_JOIN_MILLIS = 2_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        init {
            System.loadLibrary("somewhere_native")
        }

        /**
         * Opens a connection, protecting the socket before it is connected.
         *
         * @param protect `VpnService.protect`, or something standing in for it
         *   outside a tunnel. Not defaulted: a default returning true would make
         *   the one rule this class exists to keep invisible at every call site.
         */
        fun open(
            remote: InetSocketAddress,
            alpn: String,
            serverName: String?,
            protect: (DatagramSocket) -> Boolean,
            openSocket: () -> DatagramSocket = ::DatagramSocket,
        ): QuicConnection {
            val connection = QuicConnection(remote, alpn, serverName, protect, openSocket)
            connection.owner.start()
            connection.ready.await()
            connection.startupFailure.get()?.let { throw it }
            return connection
        }

        /**
         * Connections the native side currently holds. Zero when nothing is
         * open — a statement about the bridge's own bookkeeping rather than
         * about the allocator's high-water mark.
         */
        fun liveConnections(): Int = nativeLiveConnections()

        @JvmStatic
        private external fun nativeOpen(
            localIp: ByteArray,
            localPort: Int,
            remoteIp: ByteArray,
            remotePort: Int,
            serverName: String?,
            alpn: ByteArray,
        ): Long

        @JvmStatic
        private external fun nativeClose(handle: Long)

        @JvmStatic
        private external fun nativeReceive(
            handle: Long,
            packet: ByteArray,
            length: Int,
        ): Int

        @JvmStatic
        private external fun nativeWrite(
            handle: Long,
            out: ByteArray,
        ): Int

        @JvmStatic
        private external fun nativeExpiry(handle: Long): Long

        @JvmStatic
        private external fun nativeNow(): Long

        @JvmStatic
        private external fun nativeHandleExpiry(handle: Long): Int

        /** 1 completed, 0 not yet, negative a refusal — never a bare boolean. */
        @JvmStatic
        private external fun nativeHandshakeCompleted(handle: Long): Int

        @JvmStatic
        private external fun nativeExportKeyingMaterial(
            handle: Long,
            label: ByteArray,
            length: Int,
        ): ByteArray?

        @JvmStatic
        private external fun nativeOpenStream(handle: Long): Long

        @JvmStatic
        private external fun nativeSend(
            handle: Long,
            streamId: Long,
            data: ByteArray,
            length: Int,
            fin: Boolean,
        ): Int

        @JvmStatic
        private external fun nativeRead(
            handle: Long,
            streamId: Long,
            out: ByteArray,
            offset: Int,
            length: Int,
        ): Int

        @JvmStatic
        private external fun nativeLastMessage(handle: Long): String?

        @JvmStatic
        private external fun nativeLiveConnections(): Int
    }
}
