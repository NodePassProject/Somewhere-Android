// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The two shapes a subscription body comes in: plain lines, and one base64 document. */
class SubscriptionBodyTest {
    private val plain =
        listOf(
            "nowhere://0123456789abcdef0123456789abcdef@fra.example.net:443#Frankfurt",
            "nowhere://0123456789abcdef0123456789abcdef@sgp.example.net:8443?net=udp#Singapore",
        ).joinToString("\n")

    private fun hosts(body: String): List<String> =
        when (val parsed = Subscription.from(body, null, null, plaintext = false)) {
            is DecodeResult.Ok -> parsed.value.nodes.map { it.host }
            is DecodeResult.Invalid -> emptyList()
        }

    @Test
    fun plainLinesAreReadAsThey() {
        assertEquals(listOf("fra.example.net", "sgp.example.net"), hosts(plain))
    }

    @Test
    fun aStandardBase64BodyIsDecoded() {
        assertEquals(listOf("fra.example.net", "sgp.example.net"), hosts(Base64.getEncoder().encodeToString(plain.toByteArray())))
    }

    @Test
    fun urlSafeUnpaddedAndWrappedBodiesAreDecodedToo() {
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.toByteArray())
        assertEquals(2, hosts(urlSafe).size)
        val wrapped = Base64.getMimeEncoder().encodeToString(plain.toByteArray())
        assertTrue("the MIME encoder wraps", wrapped.contains("\r\n"))
        assertEquals(2, hosts(wrapped).size)
    }

    @Test
    fun aBodyThatIsNeitherHasNoNodes() {
        assertTrue(Subscription.from("<html>not a feed</html>", null, null, false) is DecodeResult.Invalid)
        assertNull(Subscription.decodeBody("not base64 at all!"))
        assertNull(Subscription.decodeBody("   "))
    }
}
