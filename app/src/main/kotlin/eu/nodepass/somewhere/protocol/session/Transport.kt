// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import java.io.Closeable

/**
 * A completed, authenticated-capable byte transport.
 *
 * Abstracted rather than using a socket directly for one reason: the session
 * layer's rules — write order, deadlines, teardown — are worth testing without a
 * network, and a fake transport is the only way to test the failure paths a real
 * one produces rarely and unrepeatably.
 *
 * [exporter] is the RFC 8446 keying material from the completed handshake. It is
 * on the transport because only the transport knows it, and the session layer
 * must not be able to authenticate without one.
 */
interface Transport : Closeable {
    /** 32 bytes exported from this specific connection. */
    val exporter: ByteArray

    /** Which carrier this is, for the transport byte in the authentication tag. */
    val transportKind: TransportKind

    fun write(bytes: ByteArray)

    fun flush()

    /**
     * Reads up to [length] bytes.
     *
     * @return the count read, or -1 at clean end of stream. A clean end is not
     *   an error: it is how a Portal signals a closed half.
     */
    fun read(
        into: ByteArray,
        offset: Int = 0,
        length: Int = into.size - offset,
    ): Int

    val isOpen: Boolean

    /**
     * Sets how long a read may block, or zero to wait indefinitely.
     *
     * The setup phase needs a deadline: a Portal that accepts a connection and
     * then says nothing must not hang the caller forever, and upstream answers
     * a rejected authentication frame with exactly that silence.
     *
     * **The data phase needs the opposite.** A tunnel carrying an idle SSH
     * session, a websocket or a long poll must not close it because nothing
     * was said for a while; quiet is the normal state of most connections most
     * of the time. So the deadline is lifted once the flow is open, and this
     * is the seam that lifts it.
     *
     * Default is a no-op so that a fake transport in a test need not implement
     * a timeout it does not have.
     */
    fun setReadTimeout(millis: Int) = Unit
}

enum class TransportKind {
    TlsTcp,
    Quic,
}
