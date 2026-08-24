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
}

enum class TransportKind {
    TlsTcp,
    Quic,
}
