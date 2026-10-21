// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * [MorphSocket] over a real loopback connection, with the other end playing
 * the Portal by hand: consume 76 bootstrap bytes, then mask with the s2c key.
 *
 * Real sockets because the class's whole claim is that it is a socket — that
 * TLS layered over it behaves as over the one it wraps — and a fake would only
 * prove the fake.
 */
class MorphSocketTest {
    private val keys = MorphKeys.derive("0123456789abcdef0123456789abcdef".encodeToByteArray())
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val raw = Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)) }
    private val peer: Socket = server.accept()

    @After
    fun tearDown() {
        runCatching { raw.close() }
        runCatching { peer.close() }
        runCatching { server.close() }
    }

    @Test
    fun bothDirectionsRoundTripThroughAHandWrittenPortal() {
        val socket = MorphSocket.over(raw, keys)
        val portal =
            thread {
                val input = DataInputStream(peer.getInputStream())
                val bootstrap = ByteArray(MorphSocket.BOOTSTRAP_LENGTH).also(input::readFully)
                val nonce = bootstrap.copyOfRange(MorphPrelude.LENGTH, MorphSocket.BOOTSTRAP_LENGTH)
                val fromClient = ByteArray(5).also(input::readFully)
                ChaCha20(keys.tcpClientToServer(), nonce).apply(fromClient)
                assertEquals("hello", String(fromClient))
                val reply = "world".encodeToByteArray()
                ChaCha20(keys.tcpServerToClient(), nonce).apply(reply)
                peer.getOutputStream().write(reply)
            }
        socket.getOutputStream().write("hello".encodeToByteArray())
        socket.getOutputStream().flush()
        val read = ByteArray(5).also { DataInputStream(socket.getInputStream()).readFully(it) }
        portal.join()
        assertEquals("world", String(read))
    }

    @Test
    fun theCallersArrayIsNotMaskedInPlace() {
        // TLS reuses its record buffers; masking one in place would corrupt
        // the next record built in it.
        val socket = MorphSocket.over(raw, keys)
        val payload = "unchanged".encodeToByteArray()
        val copy = payload.copyOf()
        socket.getOutputStream().write(payload, 0, payload.size)
        assertArrayEquals(copy, payload)
    }

    @Test
    fun aSingleByteWriteAndReadKeepTheKeystreamInStep() {
        val socket = MorphSocket.over(raw, keys)
        val input = DataInputStream(peer.getInputStream())
        val nonce =
            ByteArray(
                MorphSocket.BOOTSTRAP_LENGTH,
            ).also(input::readFully).copyOfRange(MorphPrelude.LENGTH, MorphSocket.BOOTSTRAP_LENGTH)

        socket.getOutputStream().write('a'.code)
        socket.getOutputStream().write('b'.code)
        val wire = ByteArray(2).also(input::readFully)
        ChaCha20(keys.tcpClientToServer(), nonce).apply(wire)
        assertEquals("ab", String(wire))

        val back = byteArrayOf('x'.code.toByte())
        ChaCha20(keys.tcpServerToClient(), nonce).apply(back)
        peer.getOutputStream().write(back)
        assertEquals('x'.code, socket.getInputStream().read())
    }

    @Test
    fun theEndOfTheStreamIsPassedThrough() {
        val socket = MorphSocket.over(raw, keys)
        peer.shutdownOutput()
        assertEquals(-1, socket.getInputStream().read())
        assertEquals(-1, socket.getInputStream().read(ByteArray(4), 0, 4))
    }

    @Test
    fun theSocketReportsWhatTheWrappedOneDoes() {
        val socket = MorphSocket.over(raw, keys)
        assertTrue(socket.isConnected)
        assertTrue(socket.isBound)
        assertFalse(socket.isClosed)
        assertEquals(raw.port, socket.port)
        assertEquals(raw.localPort, socket.localPort)
        assertEquals(raw.inetAddress, socket.inetAddress)
        assertEquals(raw.localAddress, socket.localAddress)
        assertEquals(raw.remoteSocketAddress, socket.remoteSocketAddress)
        assertEquals(raw.localSocketAddress, socket.localSocketAddress)
        assertNull("no channel: it would be an unmasked way to the bytes", socket.channel)

        socket.soTimeout = 1234
        assertEquals(1234, raw.soTimeout)
        assertEquals(1234, socket.soTimeout)
        socket.tcpNoDelay = true
        assertTrue(socket.tcpNoDelay)
        socket.keepAlive = true
        assertTrue(socket.keepAlive)
        socket.setSoLinger(true, 1)
        assertEquals(1, socket.soLinger)
        socket.receiveBufferSize = 65536
        assertTrue(socket.receiveBufferSize > 0)
        socket.sendBufferSize = 65536
        assertTrue(socket.sendBufferSize > 0)
        assertTrue(socket.getInputStream().available() >= 0)
        assertTrue(socket.toString().startsWith("MorphSocket("))

        socket.shutdownOutput()
        assertTrue(socket.isOutputShutdown)
        socket.shutdownInput()
        assertTrue(socket.isInputShutdown)
        socket.close()
        assertTrue(raw.isClosed)
        assertTrue(socket.isClosed)
    }

    @Test
    fun connectingOrBindingAgainIsRefused() {
        val socket = MorphSocket.over(raw, keys)
        assertTrue(runCatching { socket.connect(InetSocketAddress(1)) }.isFailure)
        assertTrue(runCatching { socket.bind(null) }.isFailure)
    }

    @Test
    fun aPreludeOrNonceOfTheWrongLengthIsRefused() {
        assertTrue(runCatching { MorphSocket.over(raw, keys, prelude = ByteArray(63)) }.isFailure)
        assertTrue(runCatching { MorphSocket.over(raw, keys, nonce = ByteArray(11)) }.isFailure)
    }

    @Test
    fun aKeyOrNonceOfTheWrongLengthIsRefused() {
        assertTrue(runCatching { ChaCha20(ByteArray(31), ByteArray(12)) }.isFailure)
        assertTrue(runCatching { ChaCha20(ByteArray(32), ByteArray(8)) }.isFailure)
    }
}
