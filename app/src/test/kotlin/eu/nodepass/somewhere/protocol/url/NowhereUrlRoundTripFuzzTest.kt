// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `parse(toUrl(node)) == node`, attacked.
 *
 * This property used to be a nicety. It is now load-bearing: `NodeStore`
 * persists a node **as the text `toUrl` produces** and reads it back with
 * `parse`, so anything that does not survive the trip is a node that silently
 * changes on disk — and a shared key that changes is an authentication failure
 * with no message attached to it, days later, on someone else's device.
 *
 * Deterministic seed: a fuzz failure that cannot be reproduced is a rumour.
 */
class NowhereUrlRoundTripFuzzTest {
    private fun key(bytes: ByteArray): SharedKey = (SharedKey.of(bytes) as DecodeResult.Ok).value

    private fun roundTrip(node: NowhereUrl): NowhereUrl {
        val rendered = node.toUrl()
        return when (val parsed = NowhereUrl.parse(rendered)) {
            is DecodeResult.Ok -> parsed.value
            is DecodeResult.Invalid ->
                throw AssertionError("toUrl() produced something parse() rejects: ${parsed.reason.detail}\n$rendered")
        }
    }

    private fun node(
        sharedKey: SharedKey,
        host: String = "portal.example.net",
        port: Int = 443,
        up: NextHopCarrier = NextHopCarrier.Tcp,
        down: NextHopCarrier = NextHopCarrier.Tcp,
        mux: Boolean = false,
        alpn: String = NowhereUrl.DEFAULT_ALPN,
        verification: CertificateVerification = CertificateVerification.Skipped,
        rate: Int = 0,
        etar: Int = 0,
        displayName: String? = null,
    ) = NowhereUrl(sharedKey, host, port, up, down, mux, alpn, verification, rate, etar, displayName)

    @Test
    fun aKeyOfArbitraryBytesSurvivesTheRoundTrip() {
        // SharedKey.of accepts any 1..255 bytes, and the URL decoder produces
        // bytes rather than text precisely because a key is not required to be
        // text. So a key that is not valid UTF-8 is reachable — `%FF%FE@…` is a
        // URL upstream accepts — and it must come back byte for byte.
        val notUtf8 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte())
        val original = node(key(notUtf8))
        assertEquals(original.sharedKey, roundTrip(original).sharedKey)
    }

    @Test
    fun everySingleByteValueSurvivesAsAKey() {
        // One byte at a time, all 256 of them: the cheapest way to find the
        // ranges an encoder mangles.
        (0..255).forEach { value ->
            val original = node(key(byteArrayOf(value.toByte())))
            val returned = roundTrip(original).sharedKey
            assertEquals(
                "a key of the single byte 0x%02X did not survive".format(value),
                original.sharedKey,
                returned,
            )
        }
    }

    @Test
    fun randomKeysSurviveTheRoundTrip() {
        val random = Random(20260825)
        repeat(500) { attempt ->
            val bytes = ByteArray(random.nextInt(1, 64)) { random.nextInt(0, 256).toByte() }
            val original = node(key(bytes))
            assertEquals(
                "attempt $attempt with a ${bytes.size}-byte key",
                original.sharedKey,
                roundTrip(original).sharedKey,
            )
        }
    }

    @Test
    fun everyOtherFieldSurvivesTheRoundTrip() {
        val random = Random(20260826)
        val names =
            listOf(
                null,
                "Frankfurt",
                "Frankfurt · Portal 04",
                "东京 · 节点 02",
                "a#b&c=d?e",
                "  padded  ",
                "100% real",
                "emoji 🛰",
                "a".repeat(120),
            )
        val alpns = listOf(NowhereUrl.DEFAULT_ALPN, "h2", "now/1-test", "x".repeat(64))
        val verifications =
            listOf(
                CertificateVerification.Skipped,
                CertificateVerification.Sni("portal.example.net"),
                CertificateVerification.Sni("xn--fsq.example"),
                CertificateVerification.Pin("a".repeat(64)),
            )

        repeat(400) { attempt ->
            val original =
                node(
                    sharedKey = key(ByteArray(random.nextInt(1, 32)) { random.nextInt(0, 256).toByte() }),
                    host = listOf("a.example.net", "127.0.0.1", "xn--fsq.example", "h").random(random),
                    port = random.nextInt(1, 65536),
                    up = NextHopCarrier.entries.random(random),
                    down = NextHopCarrier.entries.random(random),
                    mux = random.nextBoolean(),
                    alpn = alpns.random(random),
                    verification = verifications.random(random),
                    rate = random.nextInt(0, 10_000),
                    etar = random.nextInt(0, 10_000),
                    displayName = names.random(random),
                )
            val returned = roundTrip(original)
            assertEquals("attempt $attempt: shared key", original.sharedKey, returned.sharedKey)
            assertEquals("attempt $attempt: host", original.host, returned.host)
            assertEquals("attempt $attempt: port", original.port, returned.port)
            assertEquals("attempt $attempt: up", original.up, returned.up)
            assertEquals("attempt $attempt: down", original.down, returned.down)
            assertEquals("attempt $attempt: mux", original.mux, returned.mux)
            assertEquals("attempt $attempt: alpn", original.alpn, returned.alpn)
            assertEquals(
                "attempt $attempt: certificate verification",
                original.certificateVerification,
                returned.certificateVerification,
            )
            assertEquals("attempt $attempt: rate", original.rateMbps, returned.rateMbps)
            assertEquals("attempt $attempt: etar", original.etarMbps, returned.etarMbps)
            assertEquals("attempt $attempt: display name", original.displayName, returned.displayName)
        }
    }

    @Test
    fun aRenderedUrlIsAlwaysParseable() {
        // Weaker than field equality and worth asserting separately: a renderer
        // that emits something the parser rejects turns a stored node into a
        // dropped one, which the store reports as "you have fewer nodes today".
        val random = Random(20260827)
        repeat(300) {
            val original =
                node(
                    sharedKey = key(ByteArray(random.nextInt(1, 255)) { random.nextInt(0, 256).toByte() }),
                    displayName = "n".repeat(random.nextInt(0, 40)).ifEmpty { null },
                )
            assertTrue(NowhereUrl.parse(original.toUrl()) is DecodeResult.Ok)
        }
    }
}
