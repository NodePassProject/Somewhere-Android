// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Drives every vector in the `morph` family, `docs/protocol.md` § Morph. */
class MorphVectorTest {
    private val family = VectorFixture.family("morph")

    @Test
    fun theDirectionalKeysAreUpstreams() {
        val cases = VectorFixture.cases("morph")
        assertEquals(1, cases.size)
        cases.forEach { case ->
            val keys = MorphKeys.derive(case.str("sharedKeyUtf8").encodeToByteArray())
            assertEquals(case.str("tcpC2sHex"), keys.tcpClientToServer().toHex())
            assertEquals(case.str("tcpS2cHex"), keys.tcpServerToClient().toHex())
            assertEquals(case.str("udpC2sHex"), keys.udpClientToServer().toHex())
            assertEquals(case.str("udpS2cHex"), keys.udpServerToClient().toHex())
        }
    }

    @Test
    fun chaCha20StartsAtBlockZero() {
        family["chacha20"]!!.jsonArray.map { it.jsonObject }.forEach { case ->
            val block = ByteArray(ChaCha20.BLOCK_BYTES)
            ChaCha20(case.str("keyHex").hexToByteArrayCompat(), case.str("nonceHex").hexToByteArrayCompat()).apply(block)
            assertEquals(case.str("name"), case.str("keystreamHex"), block.toHex())
        }
    }

    @Test
    fun chaCha20MatchesRfc8439() {
        // RFC 8439 § 2.4.2: the sunscreen vector, which starts at counter 1.
        // One block of zeros is spent to get there.
        val key = ByteArray(32) { it.toByte() }
        val nonce = "000000000000004a00000000".hexToByteArrayCompat()
        val cipher = ChaCha20(key, nonce)
        cipher.apply(ByteArray(ChaCha20.BLOCK_BYTES))
        val text =
            (
                "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for " +
                    "the future, sunscreen would be it."
            ).encodeToByteArray()
        cipher.apply(text)
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b",
            text.copyOf(32).toHex(),
        )
    }

    @Test
    fun theKeystreamContinuesAcrossCalls() {
        // A TLS record does not end on a 64-byte boundary, and the mask must
        // not care where the calls fall.
        val key = ByteArray(32) { 3 }
        val nonce = ByteArray(12) { 5 }
        val whole = ByteArray(200).also { ChaCha20(key, nonce).apply(it) }
        val pieces = ByteArray(200)
        val cipher = ChaCha20(key, nonce)
        var at = 0
        for (size in listOf(1, 7, 63, 64, 65)) {
            cipher.apply(pieces, at, size)
            at += size
        }
        assertEquals(whole.toHex(), pieces.toHex())
    }

    @Test
    fun theAuthenticatedPreludeChangesOnlyTheTailsLowBits() {
        val tcp = family["tcp"]!!.jsonObject
        val keys = MorphKeys.derive(tcp.str("sharedKeyUtf8").encodeToByteArray())
        val prelude = tcp.str("preludeHex").hexToByteArrayCompat()
        val nonce = tcp.str("nonceHex").hexToByteArrayCompat()
        val fields = AuthenticatedPrelude.Fields(identity = 0x0123456789abcdefL, counter = 1)
        val written = ByteArrayOutputStream()
        val socket =
            MorphSocket.over(
                FakeSocket(ByteArrayInputStream(ByteArray(0)), written),
                keys,
                prelude = prelude,
                nonce = nonce,
                authentication = { fields },
            )
        socket.getOutputStream().write("hello".encodeToByteArray())

        val expectedPrelude = prelude.copyOf().also { AuthenticatedPrelude.encode(it, nonce, keys, fields) }
        val wire = written.toByteArray()
        assertEquals(expectedPrelude.toHex(), wire.copyOf(64).toHex())
        assertEquals(fields, AuthenticatedPrelude.decode(wire.copyOf(64), nonce, keys))
        // Everything after the prelude is the Core envelope, unchanged.
        assertEquals(
            tcp.str("expectedClientWireHex").substring(128),
            wire.copyOfRange(64, wire.size).toHex(),
        )
    }

    @Test
    fun theTcpWireIsUpstreamsLayout() {
        val tcp = family["tcp"]!!.jsonObject
        val keys = MorphKeys.derive(tcp.str("sharedKeyUtf8").encodeToByteArray())
        val serverBytes = ByteArrayInputStream(tcp.str("expectedServerWireHex").hexToByteArrayCompat())
        val written = ByteArrayOutputStream()
        val socket =
            MorphSocket.over(
                FakeSocket(serverBytes, written),
                keys,
                prelude = tcp.str("preludeHex").hexToByteArrayCompat(),
                nonce = tcp.str("nonceHex").hexToByteArrayCompat(),
                // The Core envelope, as the fixture records it. The Prelude
                // Extension rewrites the tail's low bits; the next case says
                // that it does, and only that.
                authentication = null,
            )

        // Written in two pieces, so that a cipher restarted per write would show.
        socket.getOutputStream().write("he".encodeToByteArray())
        socket.getOutputStream().write("llo".encodeToByteArray())
        assertEquals(tcp.str("expectedClientWireHex"), written.toByteArray().toHex())

        val read = ByteArray(5)
        var got = 0
        while (got < read.size) got += socket.getInputStream().read(read, got, read.size - got)
        assertEquals(tcp.str("serverPlainUtf8"), String(read))
    }

    @Test
    fun theBootstrapIsSeventySixUnmaskedBytes() {
        assertEquals(76, MorphSocket.BOOTSTRAP_LENGTH)
    }

    @Test
    fun theUdpWireIsUpstreamsLayout() {
        val udp = family["udp"]!!.jsonObject
        val keys = MorphKeys.derive(udp.str("sharedKeyUtf8").encodeToByteArray())
        val nonce = udp.str("nonceHex").hexToByteArrayCompat()
        val sealer = MorphDatagram(keys.udpClientToServer(), keys.udpServerToClient(), nonces = { nonce.copyInto(it) })
        val wire = sealer.seal(udp.str("plainHex").hexToByteArrayCompat())
        assertEquals(udp.str("expectedWireHex"), wire.toHex())
        assertEquals(udp.str("overhead").toInt(), MorphDatagram.OVERHEAD)

        // And the Portal's side of it: what the client sealed, the server opens.
        val server = MorphDatagram(keys.udpServerToClient(), keys.udpClientToServer())
        assertEquals(udp.str("plainHex"), server.open(wire)!!.toHex())
    }

    @Test
    fun aDatagramNoLongerThanTheNonceIsDiscarded() {
        val keys = MorphKeys.derive("shared".encodeToByteArray())
        val datagram = MorphDatagram.forClient(keys)
        assertNull(datagram.open(ByteArray(12)))
        assertNull(datagram.open(ByteArray(3)))
        assertEquals(1, datagram.open(ByteArray(13))!!.size)
    }

    @Test
    fun everyDatagramGetsItsOwnNonce() {
        val datagram = MorphDatagram.forClient(MorphKeys.derive("shared".encodeToByteArray()))
        val one = datagram.seal(ByteArray(20))
        val two = datagram.seal(ByteArray(20))
        assertTrue("two datagrams must not share a nonce", !one.copyOf(12).contentEquals(two.copyOf(12)))
    }

    @Test
    fun theKeysDoNotRenderThemselves() {
        assertEquals("MorphKeys(REDACTED)", MorphKeys.derive("shared".encodeToByteArray()).toString())
    }

    /** A connected socket that is really two byte buffers. */
    private class FakeSocket(
        private val input: InputStream,
        private val output: OutputStream,
    ) : Socket() {
        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun isConnected(): Boolean = true
    }
}
