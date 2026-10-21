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
        net: NextHopCarrier = NextHopCarrier.Tcp,
        morph: Boolean = true,
        verification: CertificateVerification = CertificateVerification.Sni(host),
        rate: Int = 0,
        etar: Int = 0,
        displayName: String? = null,
    ) = NowhereUrl(sharedKey, host, port, net, morph, verification, rate, etar, displayName)

    private fun serviceKey(random: Random): SharedKey = key(ByteArray(32) { "0123456789abcdef"[random.nextInt(16)].code.toByte() })

    @Test
    fun randomServiceKeysSurviveTheRoundTrip() {
        // v3 narrowed a key to 32 lowercase hex characters, which are all
        // unreserved in a URL — so the encoder's job got easier, and this
        // checks it did not get it wrong anyway.
        val random = Random(20260825)
        repeat(500) { attempt ->
            val original = node(serviceKey(random))
            assertEquals("attempt $attempt", original.sharedKey, roundTrip(original).sharedKey)
        }
    }

    @Test
    fun aKeyOutsideServiceFormIsNeverEmitted() {
        // A node can only be built with a key parse() accepted, but the type
        // permits any 1-255 bytes. Should one be built by hand, the rendering
        // must still be faithful, and parse must refuse it rather than accept
        // something else — never a silent change of key.
        val notService = node(key(byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte())))
        val parsed = NowhereUrl.parse(notService.toUrl())
        assertTrue(parsed is DecodeResult.Invalid)
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
        val hosts = listOf("a.example.net", "127.0.0.1", "xn--fsq.example", "h", "2001:db8::1")

        repeat(400) { attempt ->
            val host = hosts.random(random)
            val verifications =
                listOf(
                    CertificateVerification.Sni(host),
                    CertificateVerification.Sni("portal.example.net"),
                    CertificateVerification.Sni("xn--fsq.example"),
                    CertificateVerification.Pin("a".repeat(64)),
                )
            val original =
                node(
                    sharedKey = serviceKey(random),
                    host = host,
                    port = random.nextInt(1, 65536),
                    net = NextHopCarrier.entries.random(random),
                    morph = random.nextBoolean(),
                    verification = verifications.random(random),
                    rate = random.nextInt(0, 10_000),
                    etar = random.nextInt(0, 10_000),
                    displayName = names.random(random),
                )
            assertEquals("attempt $attempt: ${original.toUrl()}", original, roundTrip(original))
        }
    }

    @Test
    fun aRenderedUrlIsAlwaysParseable() {
        // Weaker than equality and worth asserting separately: a renderer that
        // emits something the parser rejects turns a stored node into a dropped
        // one, which the store reports as "you have fewer nodes today".
        val random = Random(20260827)
        repeat(300) {
            val original =
                node(
                    sharedKey = serviceKey(random),
                    displayName = "n".repeat(random.nextInt(0, 40)).ifEmpty { null },
                )
            assertTrue(NowhereUrl.parse(original.toUrl()) is DecodeResult.Ok)
        }
    }
}
