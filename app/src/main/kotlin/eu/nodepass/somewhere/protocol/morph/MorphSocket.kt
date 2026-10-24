// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import java.security.SecureRandom

/**
 * A connected TCP socket with Morph applied, for TLS to be layered over.
 * `docs/protocol.md` § Morph, TCP encapsulation.
 *
 * ```
 * client -> server  | prelude: 64 | nonce: 12 | ChaCha20-XOR(TLS bytes) ...
 * server -> client                            | ChaCha20-XOR(TLS bytes) ...
 * ```
 *
 * The client speaks first and alone: 76 unmasked bytes, then every byte in
 * either direction XORed with that direction's keystream under the client's
 * nonce. The server sends no bootstrap of its own, so there is nothing to wait
 * for before the TLS handshake can start.
 *
 * ## Why a socket and not a pair of streams
 *
 * The TLS layer above takes a `Socket`. It is layered with Conscrypt in
 * engine-socket mode, which reads and writes through this object's streams;
 * the other mode reaches past the object to its file descriptor and would
 * send the TLS bytes unmasked. `NowhereDialer` therefore selects engine mode
 * explicitly, and the `Socket` methods below delegate everything except the
 * streams to the real connected socket, so deadlines, shutdown and close keep
 * meaning what they mean.
 *
 * The cipher positions advance by exactly the bytes that crossed, in each
 * direction independently, across reads, writes and TLS records — the stream
 * has no framing of its own to resynchronise on.
 */
class MorphSocket private constructor(
    private val inner: Socket,
    keys: MorphKeys,
    nonce: ByteArray,
) : Socket() {
    private val input = MaskedInput(inner.getInputStream(), ChaCha20(keys.tcpServerToClient(), nonce))
    private val output = MaskedOutput(inner.getOutputStream(), ChaCha20(keys.tcpClientToServer(), nonce))

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun close() = inner.close()

    override fun isClosed(): Boolean = inner.isClosed

    override fun isConnected(): Boolean = inner.isConnected

    override fun isBound(): Boolean = inner.isBound

    override fun isInputShutdown(): Boolean = inner.isInputShutdown

    override fun isOutputShutdown(): Boolean = inner.isOutputShutdown

    override fun shutdownInput() = inner.shutdownInput()

    override fun shutdownOutput() = inner.shutdownOutput()

    override fun getInetAddress(): InetAddress? = inner.inetAddress

    override fun getPort(): Int = inner.port

    override fun getLocalAddress(): InetAddress = inner.localAddress

    override fun getLocalPort(): Int = inner.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress

    override fun setSoTimeout(timeout: Int) {
        inner.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = inner.soTimeout

    override fun setTcpNoDelay(on: Boolean) {
        inner.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay

    override fun setKeepAlive(on: Boolean) {
        inner.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = inner.keepAlive

    override fun setSoLinger(
        on: Boolean,
        linger: Int,
    ) = inner.setSoLinger(on, linger)

    override fun getSoLinger(): Int = inner.soLinger

    override fun setReceiveBufferSize(size: Int) {
        inner.receiveBufferSize = size
    }

    override fun getReceiveBufferSize(): Int = inner.receiveBufferSize

    override fun setSendBufferSize(size: Int) {
        inner.sendBufferSize = size
    }

    override fun getSendBufferSize(): Int = inner.sendBufferSize

    /**
     * No channel. A channel would be a second, unmasked way to the bytes,
     * which is the one thing this class exists to not have.
     */
    override fun getChannel(): SocketChannel? = null

    override fun connect(
        endpoint: SocketAddress?,
        timeout: Int,
    ) = throw UnsupportedOperationException("a MorphSocket wraps a socket that is already connected")

    override fun bind(bindpoint: SocketAddress?) = throw UnsupportedOperationException("a MorphSocket wraps a socket that is already bound")

    override fun toString(): String = "MorphSocket($inner)"

    private class MaskedInput(
        private val raw: InputStream,
        private val cipher: ChaCha20,
    ) : InputStream() {
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = raw.read(bytes, offset, length)
            if (count > 0) cipher.apply(bytes, offset, count)
            return count
        }

        override fun available(): Int = raw.available()

        override fun close() = raw.close()
    }

    private class MaskedOutput(
        private val raw: OutputStream,
        private val cipher: ChaCha20,
    ) : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        /**
         * Masks a copy, never the caller's array: the caller still owns it,
         * and TLS reuses its record buffers.
         */
        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (length == 0) return
            val masked = bytes.copyOfRange(offset, offset + length)
            cipher.apply(masked)
            raw.write(masked)
        }

        override fun flush() = raw.flush()

        override fun close() = raw.close()
    }

    companion object {
        private val random = SecureRandom()

        /**
         * Writes the bootstrap onto [connected] and returns the masked socket.
         *
         * Prelude and nonce go out as one write, so they leave as one segment
         * rather than two with a gap between them that only this client would
         * produce.
         *
         * @param prelude the 64 bytes to open with; [MorphPrelude.Fade] unless
         *   a test is pinning the wire image.
         * @param nonce the 12-byte nonce both directions' keystreams use.
         * @param authentication what the Authenticated Prelude carries; null
         *   only for a test pinning the policy's bytes. Allocated here, as
         *   late as possible, because the specification spends a Counter at
         *   encoding whether or not the connection then succeeds.
         */
        fun over(
            connected: Socket,
            keys: MorphKeys,
            prelude: ByteArray = MorphPrelude.Fade.generate(),
            nonce: ByteArray = ByteArray(ChaCha20.NONCE_BYTES).also(random::nextBytes),
            authentication: (() -> AuthenticatedPrelude.Fields)? = { AuthenticatedPrelude.allocatorFor(keys).allocate() },
        ): MorphSocket {
            require(prelude.size == MorphPrelude.LENGTH) { "a prelude is ${MorphPrelude.LENGTH} bytes, got ${prelude.size}" }
            require(nonce.size == ChaCha20.NONCE_BYTES) { "a nonce is ${ChaCha20.NONCE_BYTES} bytes, got ${nonce.size}" }
            val bootstrap = prelude.copyOf()
            authentication?.let { AuthenticatedPrelude.encode(bootstrap, nonce, keys, it()) }
            val out = connected.getOutputStream()
            out.write(bootstrap + nonce)
            out.flush()
            return MorphSocket(connected, keys, nonce)
        }

        /** Bytes on the wire before the first masked byte. */
        const val BOOTSTRAP_LENGTH: Int = MorphPrelude.LENGTH + ChaCha20.NONCE_BYTES
    }
}
