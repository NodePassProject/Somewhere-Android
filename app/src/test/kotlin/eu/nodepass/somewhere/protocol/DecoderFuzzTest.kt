// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol

import eu.nodepass.somewhere.dns.DnsMessage
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.frame.UdpOverTcp
import eu.nodepass.somewhere.protocol.mux.MuxHeader
import eu.nodepass.somewhere.protocol.quic.DatagramFrame
import eu.nodepass.somewhere.protocol.quic.DatagramReassembler
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Every decoder, against arbitrary bytes. NW-Q-03.
 *
 * The specification requires validating the smallest outer header before reading
 * variable-length data; this is what holds the implementation to it. A decoder
 * that trusted a length field would show up here as an OutOfMemoryError or a
 * hang, not as a wrong answer.
 *
 * The result type earns its keep here: with exceptions, "correctly rejected
 * malformed input" and "crashed on malformed input" are the same observation. A
 * decoder that returns Invalid has demonstrably handled the input; one that
 * throws has not.
 *
 * Seeds are fixed. A fuzz test that cannot be replayed is not a test — it is a
 * flake generator.
 */
class DecoderFuzzTest {
    private companion object {
        const val ITERATIONS = 4_000
        val SEEDS = listOf(1L, 7L, 42L, 1337L, 20260824L)

        /** Sizes chosen around every header boundary in the protocol. */
        val LENGTHS = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 16, 31, 32, 33, 64, 255, 256, 1024)
    }

    /** Every decoder under test, as a name and a call that must not throw. */
    private val decoders: List<Pair<String, (ByteArray) -> Any?>> =
        listOf(
            "FlowHeader" to { bytes -> FlowHeader.decode(bytes) },
            "Target" to { bytes -> Target.decode(bytes) },
            "SetupResult" to { bytes -> SetupResult.decode(bytes) },
            "UdpOverTcp.next" to { bytes -> UdpOverTcp.next(bytes) },
            "UdpOverTcp.decodeAll" to { bytes -> UdpOverTcp.decodeAll(bytes) },
            "MuxHeader" to { bytes -> MuxHeader.decode(bytes) },
            "DatagramFrame" to { bytes -> DatagramFrame.decode(bytes) },
            "UoT.encode" to { bytes -> UdpOverTcp.encode(bytes) },
            "SharedKey" to { bytes -> SharedKey.of(bytes) },
            "DnsMessage.parseQuestion" to { bytes -> DnsMessage.parseQuestion(bytes) },
        )

    private fun eachInput(block: (ByteArray) -> Unit) {
        SEEDS.forEach { seed ->
            val random = Random(seed)
            repeat(ITERATIONS) {
                block(random.nextBytes(LENGTHS.random(random)))
            }
        }
        // Degenerate shapes that random bytes rarely produce.
        LENGTHS.forEach { length ->
            block(ByteArray(length))
            block(ByteArray(length) { 0xff.toByte() })
            block(ByteArray(length) { (it % 256).toByte() })
        }
    }

    @Test
    fun noDecoderThrowsOnArbitraryBytes() {
        decoders.forEach { (name, decode) ->
            eachInput { bytes ->
                try {
                    decode(bytes)
                } catch (error: Throwable) {
                    fail(
                        "$name threw ${error::class.simpleName} on ${bytes.size} bytes " +
                            "(${bytes.take(16).joinToString("") { "%02x".format(it) }}...): ${error.message}",
                    )
                }
            }
        }
    }

    @Test
    fun everyDecoderReturnsAResultRatherThanSignallingOutOfBand() {
        decoders.forEach { (name, decode) ->
            eachInput { bytes ->
                val result = decode(bytes)
                assertTrue("$name returned null for ${bytes.size} bytes", result != null)
            }
        }
    }

    @Test
    fun aHostileLengthFieldNeverDrivesAnUnboundedAllocation() {
        // Each of these declares far more data than is present. A decoder that
        // allocated on the declared length would fail here rather than reject.
        val hostile =
            listOf(
                "Target domain" to byteArrayOf(0x03, 0xff.toByte(), 0x61),
                "UoT" to byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x61),
                "Mux STREAM" to byteArrayOf(0x01, 0x00, 0xff.toByte(), 0xff.toByte(), 0, 0, 0, 1),
                "Datagram FRAGMENT" to byteArrayOf(0x01, 0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 0xff.toByte(), 0xff.toByte()),
            )
        hostile.forEach { (name, bytes) ->
            val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            decoders.forEach { (_, decode) -> runCatching { decode(bytes) } }
            val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            assertTrue(
                "$name appears to have driven a large allocation (${(after - before) / 1024} KiB)",
                after - before < 64 * 1024 * 1024,
            )
        }
    }

    @Test
    fun theUrlParserSurvivesArbitraryText() {
        val alphabet = "nowhere:/@?&=#%.[]0123456789abcdefzZ +-_é😀"
        SEEDS.forEach { seed ->
            val random = Random(seed)
            repeat(ITERATIONS) {
                val text = (0 until random.nextInt(0, 120)).map { alphabet.random(random) }.joinToString("")
                try {
                    NowhereUrl.parse(text)
                } catch (error: Throwable) {
                    fail("NowhereUrl.parse threw ${error::class.simpleName} on '$text': ${error.message}")
                }
            }
        }
    }

    @Test
    fun theUrlParserSurvivesTruncationOfAValidUrl() {
        val valid = "nowhere://a%2Bb@example.com:443?up=tcp&down=tcp&pin=${"ab".repeat(32)}#Node"
        for (length in 0..valid.length) {
            val prefix = valid.take(length)
            try {
                NowhereUrl.parse(prefix)
            } catch (error: Throwable) {
                fail("NowhereUrl.parse threw on prefix of $length chars: ${error.message}")
            }
        }
    }

    @Test
    fun authenticationSurvivesArbitraryFrames() {
        val key = (SharedKey.of("secret") as DecodeResult.Ok).value
        val exporter = ByteArray(32) { it.toByte() }
        eachInput { bytes ->
            try {
                Authentication.verifyFrame(bytes, key, AuthTransport.TlsTcp, exporter)
            } catch (error: Throwable) {
                fail("verifyFrame threw ${error::class.simpleName} on ${bytes.size} bytes: ${error.message}")
            }
        }
    }

    @Test
    fun theReassemblerSurvivesArbitraryFragments() {
        // Feeds decoded frames rather than raw bytes, since that is the layer
        // the reassembler actually sits at.
        SEEDS.forEach { seed ->
            val random = Random(seed)
            val reassembler = DatagramReassembler(maxSlots = 8).also { it.markReady() }
            repeat(ITERATIONS) {
                val frame =
                    DatagramFrame.Fragment(
                        flowId = random.nextInt(0, 4).toUInt().coerceAtLeast(1u),
                        packetId = random.nextInt(1, 6).toUInt(),
                        index = random.nextInt(0, 4),
                        count = random.nextInt(2, 5),
                        totalLength = random.nextInt(0, 200),
                        payload = random.nextBytes(random.nextInt(0, 40)),
                    )
                try {
                    reassembler.offer(frame, nowMillis = it.toLong())
                } catch (error: Throwable) {
                    fail("reassembler threw ${error::class.simpleName}: ${error.message}")
                }
            }
            assertTrue("slots must stay bounded, saw ${reassembler.slotCount}", reassembler.slotCount <= 8)
        }
    }

    @Test
    fun decodingIsDeterministic() {
        // The same bytes must always produce the same answer. A decoder with
        // hidden state would make every other test in this suite unreliable.
        //
        // Compared structurally rather than by toString: a DecodeResult wrapping
        // a ByteArray renders its identity hash, so toString differs between two
        // identical results. That is a property of the generic container, not a
        // defect in any decoder.
        eachInput { bytes ->
            decoders.forEach { (name, decode) ->
                val first = decode(bytes)
                val second = decode(bytes)
                assertTrue("$name is not deterministic on ${bytes.size} bytes", sameOutcome(first, second))
            }
        }
    }

    private fun sameOutcome(
        first: Any?,
        second: Any?,
    ): Boolean {
        fun unwrap(value: Any?): Any? =
            when (value) {
                is DecodeResult.Ok<*> -> value.value
                is DecodeResult.Invalid -> value.reason
                else -> value
            }
        return deepEquals(unwrap(first), unwrap(second))
    }

    /**
     * Structural comparison that sees through byte arrays at any depth.
     *
     * Needed because a ByteArray compares by identity, so any result carrying
     * one — directly, or inside a list — would look non-deterministic. That is
     * a property of the container, not of the decoder.
     */
    private fun deepEquals(
        first: Any?,
        second: Any?,
    ): Boolean =
        when {
            first is ByteArray && second is ByteArray -> first.contentEquals(second)
            first is List<*> && second is List<*> ->
                first.size == second.size && first.indices.all { deepEquals(first[it], second[it]) }
            else -> first == second
        }
}
