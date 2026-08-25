// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.session.TransportKind
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket

/**
 * A [Transport] over a completed TLS connection.
 *
 * Wraps a socket whose handshake has already finished and whose keying material
 * has already been exported. Both are done by the caller rather than here, so
 * that the two things that can fail before any Nowhere byte is written — the
 * handshake and the export — fail where the caller can tell them apart.
 *
 * The exporter is captured once at construction. It cannot change for the life of
 * the connection, and re-exporting per use would be work with no answer to the
 * question of what to do if the second call disagreed with the first.
 */
class TlsTransport private constructor(
    private val socket: SSLSocket,
    override val exporter: ByteArray,
) : Transport {
    private val out: OutputStream = BufferedOutputStream(socket.outputStream)
    private val input: InputStream = socket.inputStream

    override val transportKind: TransportKind = TransportKind.TlsTcp

    override val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    override fun write(bytes: ByteArray) = out.write(bytes)

    override fun flush() = out.flush()

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int = input.read(into, offset, length)

    override fun setReadTimeout(millis: Int) {
        runCatching { socket.soTimeout = millis }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Wraps a handshaken socket, exporting keying material with [exporter].
         *
         * @return the transport, or the exporter's own reason for failing —
         *   which is the failure a caller most needs to distinguish, because it
         *   means this device cannot authenticate at all rather than that this
         *   connection had a bad moment.
         */
        fun over(
            socket: SSLSocket,
            exporter: KeyingMaterialExporter,
        ): DecodeResult<Transport> =
            when (
                val material =
                    exporter.export(
                        socket,
                        Authentication.EXPORTER_LABEL,
                        ByteArray(0),
                        Authentication.EXPORTER_LENGTH,
                    )
            ) {
                is DecodeResult.Invalid -> material
                is DecodeResult.Ok -> DecodeResult.Ok(TlsTransport(socket, material.value))
            }
    }
}
