// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.session.TransportKind
import java.io.InterruptedIOException

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
 * ## There is no pump thread
 *
 * A read drives the connection's IO loop itself. That is not a shortcut: a
 * second thread touching the same `ngtcp2_conn` is the lwIP defect again, and
 * the bridge refuses it outright. So the thread that wants bytes is the thread
 * that goes and gets them.
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

    override fun flush() {
        connection.flush()
    }

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        // Zero means wait indefinitely, which is what the interface says and
        // what the data phase needs: quiet is the normal state of most
        // connections most of the time, and a tunnel that hung up on an idle
        // SSH session would be the L1 defect again.
        val deadline =
            if (readTimeoutMillis == 0) {
                Long.MAX_VALUE
            } else {
                System.nanoTime() + readTimeoutMillis * NANOS_PER_MILLI
            }

        while (true) {
            val n = connection.receive(streamId, into, offset, length)
            if (n != 0) return n

            val remaining =
                if (deadline == Long.MAX_VALUE) {
                    POLL_MILLIS
                } else {
                    (deadline - System.nanoTime()) / NANOS_PER_MILLI
                }
            if (remaining <= 0) {
                // The same signal a socket gives, so callers that already
                // treat a timeout as "no answer came" — which is how a
                // rejected AuthFrame presents, since a Portal answers one with
                // silence — need no second case.
                throw InterruptedIOException("no bytes on stream $streamId within ${readTimeoutMillis}ms")
            }
            connection.pump(minOf(remaining, POLL_MILLIS))
        }
    }

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
        runCatching {
            connection.send(streamId, ByteArray(0), fin = true)
            connection.flush()
        }
    }

    private companion object {
        const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
        const val POLL_MILLIS = 250L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
