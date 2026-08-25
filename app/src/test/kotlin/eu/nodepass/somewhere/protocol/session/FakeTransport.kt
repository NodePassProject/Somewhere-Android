// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import java.io.ByteArrayOutputStream

/**
 * A transport whose peer is a script.
 *
 * Exists so the session layer's failure paths can be tested at all: a Portal
 * that closes without answering, a Portal that answers with a rejection, a
 * transport that dies mid-write. Against a real Portal those are rare, timing
 * dependent, and impossible to demand on cue.
 */
class FakeTransport(
    override val exporter: ByteArray = ByteArray(32) { it.toByte() },
    override val transportKind: TransportKind = TransportKind.TlsTcp,
    /** Bytes the peer will return, in order. Empty means an immediate clean end. */
    peerBytes: ByteArray = ByteArray(0),
    private val failOnWrite: Boolean = false,
    /**
     * Reproduce a Portal that neither answers nor closes.
     *
     * Observed against a live Portal: a rejected AuthFrame is met with silence,
     * so reads time out rather than reaching end of stream. Without this the
     * unit tests would only ever exercise the EOF path, and the real client
     * would hang on the one failure that matters most.
     */
    private val silentPeer: Boolean = false,
) : Transport {
    /**
     * Every read timeout the lane asked for, in order.
     *
     * Recorded rather than merely applied because the interesting assertion is
     * *when* it changes: a deadline that protects setup and then never comes
     * off closes healthy idle connections.
     */
    val readTimeouts: MutableList<Int> = mutableListOf()

    override fun setReadTimeout(millis: Int) {
        readTimeouts += millis
    }

    private val written = ByteArrayOutputStream()
    private var peer = peerBytes
    private var peerOffset = 0
    private var open = true
    var flushCount: Int = 0
        private set

    /** Everything the client has written, in order. */
    fun writtenBytes(): ByteArray = written.toByteArray()

    override val isOpen: Boolean get() = open

    override fun write(bytes: ByteArray) {
        check(open) { "write on a closed transport" }
        if (failOnWrite) throw java.io.IOException("simulated write failure")
        written.write(bytes)
    }

    override fun flush() {
        flushCount++
    }

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (silentPeer) throw java.net.SocketTimeoutException("simulated silent Portal")
        if (!open) return -1
        val available = peer.size - peerOffset
        if (available <= 0) return -1
        val count = minOf(length, available)
        peer.copyInto(into, offset, peerOffset, peerOffset + count)
        peerOffset += count
        return count
    }

    override fun close() {
        open = false
    }
}
