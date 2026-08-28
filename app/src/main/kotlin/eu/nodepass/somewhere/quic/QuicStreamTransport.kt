// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.session.TransportKind

/**
 * One QUIC stream, seen as the byte transport the session layer already knows.
 *
 * ## Why a stream and not a connection
 *
 * `Transport` is an interface over ordered reliable bytes, and that is exactly
 * what a QUIC stream is — not what a QUIC connection is. Making the stream the
 * transport means the session layer's rules about write order, deadlines and
 * teardown carry over unchanged from the dedicated TLS lane, and the only thing
 * that had to be new is where the AuthFrame goes.
 *
 * The exporter is the **connection's**, not the stream's, which is correct:
 * NW-P-01 binds a session to a connection, so every stream on one connection
 * derives the same tag input. It is cached because RFC 5705 derivation is not
 * free and the value cannot change once the handshake is complete.
 *
 * ## Reads block only their own flow
 *
 * The connection has a thread of its own that owns the native side and moves
 * what arrives into per-stream queues, so a read here waits on a queue rather
 * than on a socket. That is what lets sixteen flows share one connection: a
 * flow waiting for its peer blocks itself and nothing else.
 */
class QuicStreamTransport(
    private val connection: QuicConnection,
    val streamId: Long,
) : Transport {
    private var readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS
    private var closed = false

    override val exporter: ByteArray by lazy {
        connection.exportKeyingMaterial(Authentication.EXPORTER_LABEL, Authentication.EXPORTER_LENGTH)
    }

    override val transportKind: TransportKind = TransportKind.Quic

    override val isOpen: Boolean get() = !closed

    override fun write(bytes: ByteArray) {
        connection.send(streamId, bytes)
    }

    /**
     * A no-op, and deliberately.
     *
     * The connection's own thread sends whatever the transport has ready on
     * every pass of its loop, and [QuicConnection.send] has already handed the
     * bytes to it by the time it returns. There is nothing left for a caller to
     * push. Implemented rather than omitted because the session layer calls it
     * and the contract — "the bytes are on their way" — is met.
     */
    override fun flush() = Unit

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int = connection.receive(streamId, into, offset, length, readTimeoutMillis.toLong())

    override fun setReadTimeout(millis: Int) {
        readTimeoutMillis = millis
    }

    /**
     * Closes the write half and stops reading.
     *
     * The connection is deliberately **not** closed: several streams share one,
     * and a flow ending is not a session ending.
     */
    override fun close() {
        if (closed) return
        closed = true
        // A FIN on this stream only. The connection is deliberately left
        // alone: several streams share one, and a flow ending is not a session
        // ending.
        runCatching { connection.send(streamId, ByteArray(0), fin = true) }
    }

    private companion object {
        /**
         * Long enough for a Portal that is working and short enough that a
         * wrong shared key does not hang: authentication is answered with
         * silence rather than a close, so this deadline is the only thing that
         * ends that wait.
         */
        const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
    }
}
