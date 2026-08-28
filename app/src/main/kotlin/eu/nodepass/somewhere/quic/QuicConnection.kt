// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

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
}

class QuicException(
    val failure: QuicFailure,
) : Exception(failure.detail)

/**
 * One QUIC connection to one Portal.
 *
 * ## The socket is here, and the transport cannot reach it
 *
 * ngtcp2 never owns a socket: it is fed the datagrams that arrived and produces
 * the ones to send. That is not an inconvenience to work around — it is the
 * only architecture that fits a VPN. Every outbound socket must be
 * `VpnService.protect()`-ed or it routes back into the TUN, reaches lwIP, and
 * is dialled again, which looks like a hang and takes the device with it. This
 * project has already shipped the version of that defect where `protect()`
 * returned false for every socket.
 *
 * So the socket lives in this class, under the same rule [DirectDialer] keeps,
 * and the native bridge has no networking function in it at all — not even
 * address parsing. `checkNativeBridge` greps for the syscalls.
 *
 * **The order differs from the TCP case, and the difference matters.**
 * `Socket()` has no file descriptor until it is bound or connected, which is
 * why the TCP path is bind, protect, connect. `DatagramSocket()` binds on
 * construction, so it has one immediately: construct, protect, connect. The
 * `connect` still comes last, because after it the routing decision is already
 * made — and because only then does the socket know which local address it
 * chose, which is the address ngtcp2's path is built from.
 *
 * ## One thread
 *
 * `ngtcp2_conn` is not thread-safe and nothing here makes it so. The thread
 * that opens a connection owns it; the bridge refuses calls from any other,
 * rather than letting them corrupt state that fails later somewhere else. lwIP
 * taught this project the same lesson at a much higher price.
 */
class QuicConnection private constructor(
    private val handle: Long,
    private val socket: DatagramSocket,
) : Closeable {
    private var closed = false

    /**
     * Drives the connection until the handshake completes, or the deadline
     * passes.
     *
     * A QUIC handshake is several round trips, so this is a loop rather than a
     * call: send what the transport has ready, wait for a reply for no longer
     * than its own timer allows, feed it back in, repeat.
     */
    fun completeHandshake(timeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI

        while (!handshakeCompleted) {
            if (System.nanoTime() >= deadline) {
                throw QuicException(QuicFailure.HandshakeTimeout)
            }
            pump((deadline - System.nanoTime()) / NANOS_PER_MILLI)
        }
        // Whatever the completed handshake left to say — the client's final
        // flight, usually — goes out before the caller is told it is ready.
        flush()
    }

    /**
     * Opens one client-initiated bidirectional stream.
     *
     * Unidirectional streams are never opened. The specification says they are
     * not used, and this client advertises `initial_max_streams_uni = 0`, so
     * the statement is on the wire as well as in the source.
     *
     * Before authentication the peer credits exactly one bidirectional stream
     * (NW-P-19), so a second one here fails rather than opening and stalling.
     * That refusal is the observable form of the rule and is left visible to
     * the caller instead of being retried behind its back.
     */
    fun openStream(): Long {
        val id = nativeOpenStream(handle)
        if (id < 0) {
            throw QuicException(QuicFailure.Transport(message() ?: "no stream could be opened"))
        }
        return id
    }

    /** Queues bytes on a stream; they leave on a later [pump]. */
    fun send(
        streamId: Long,
        bytes: ByteArray,
        fin: Boolean = false,
    ) {
        check(nativeSend(handle, streamId, bytes, bytes.size, fin))
    }

    /**
     * Takes what has arrived on a stream.
     *
     * @return the count copied, `0` when nothing has arrived yet, and `-1` at a
     *   clean end of stream — the same contract [Transport.read] has, so the
     *   session layer needs no second spelling of "the peer is done".
     */
    fun receive(
        streamId: Long,
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val n = nativeRead(handle, streamId, into, offset, length)
        if (n < -1) check(n)
        return n
    }

    /**
     * Sends everything ready and waits up to [timeoutMillis] for one datagram.
     *
     * This is the whole IO loop, and it is called from wherever the owning
     * thread happens to be — the handshake, a stream read, a flush. There is no
     * separate pump thread, deliberately: a second thread driving the same
     * connection is the lwIP defect again, and this bridge refuses it anyway.
     */
    fun pump(timeoutMillis: Long) {
        flush()
        val incoming = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(incoming, incoming.size)
        socket.soTimeout = minOf(waitMillis(), timeoutMillis).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        try {
            socket.receive(packet)
            check(nativeReceive(handle, incoming, packet.length))
        } catch (_: SocketTimeoutException) {
            check(nativeHandleExpiry(handle))
        }
        flush()
    }

    /**
     * RFC 5705 keying material, which is what NW-P-01 authenticates with.
     *
     * The context is empty and present, which is not the same as absent: RFC
     * 5705 derives different bytes for the two, and the specification's vectors
     * were computed with an empty one. Getting it wrong yields 32 plausible
     * bytes that no Portal accepts.
     */
    fun exportKeyingMaterial(
        label: String,
        length: Int,
    ): ByteArray =
        nativeExportKeyingMaterial(handle, label.toByteArray(Charsets.US_ASCII), length)
            ?: throw QuicException(QuicFailure.Transport(message() ?: "the exporter refused"))

    val handshakeCompleted: Boolean
        get() {
            val state = nativeHandshakeCompleted(handle)
            check(state)
            return state == 1
        }

    override fun close() {
        if (closed) return
        closed = true
        // The socket first: after this nothing can arrive, so nothing can be
        // in the bridge when its memory goes.
        runCatching { socket.close() }
        nativeClose(handle)
    }

    /**
     * Sends every datagram the transport currently has ready, and waits for
     * nothing. This is what a caller means by "flush": the bytes are on their
     * way, not answered.
     */
    fun flush() {
        val out = ByteArray(MAX_DATAGRAM)
        while (true) {
            val written = nativeWrite(handle, out)
            if (written == 0) return
            check(written)
            socket.send(DatagramPacket(out, written))
        }
    }

    /** How long the transport is willing to wait before its next timer fires. */
    private fun waitMillis(): Long {
        val expiry = nativeExpiry(handle)
        if (expiry < 0) return DEFAULT_POLL_MILLIS
        val now = nativeNow()
        return ((expiry - now) / NANOS_PER_MILLI).coerceIn(1L, DEFAULT_POLL_MILLIS)
    }

    private fun check(result: Int) {
        if (result < 0) {
            throw QuicException(QuicFailure.Transport(message() ?: "the transport failed with $result"))
        }
    }

    private fun message(): String? = nativeLastMessage(handle)

    companion object {
        /**
         * The largest datagram this client will send or accept in one piece.
         * 1452 is ngtcp2's own default send size on a 1500-byte path, and the
         * bridge refuses a buffer smaller than that rather than silently
         * truncating a packet.
         */
        const val MAX_DATAGRAM = 1452

        private const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000L
        private const val DEFAULT_POLL_MILLIS = 250L
        private const val NANOS_PER_MILLI = 1_000_000L

        init {
            System.loadLibrary("somewhere_native")
        }

        /**
         * Opens a connection, protecting the socket before it is connected.
         *
         * @param protect `VpnService.protect`, or something standing in for it
         *   outside a tunnel. It is not optional and it is not defaulted: a
         *   default that returned true would make the one rule this class
         *   exists to keep invisible at every call site.
         */
        fun open(
            remote: InetSocketAddress,
            alpn: String,
            serverName: String?,
            protect: (DatagramSocket) -> Boolean,
            openSocket: () -> DatagramSocket = ::DatagramSocket,
        ): QuicConnection {
            val socket = openSocket()
            try {
                // Already bound, so there is a descriptor to protect. The TCP
                // path has to bind first; this one does not, and conflating
                // the two is how the first version of that rule went wrong.
                if (!protect(socket)) {
                    throw QuicException(QuicFailure.NotProtected)
                }
                socket.connect(remote)

                val handle =
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
                return QuicConnection(handle, socket)
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }

        /**
         * Connections the native side currently holds. Zero when nothing is
         * open, which is what a leak test asserts — a statement about the
         * bridge's bookkeeping rather than about the allocator's high-water
         * mark.
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
