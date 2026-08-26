// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * NW-D-03. The exact shape `web/src/lib/subscriptions-api.ts` builds:
 *
 * ```
 * `anywhere://add-proxy?link=${encodeURIComponent(importUrl)}`
 * ```
 */
class ImportLinkTest {
    private fun wrapped(inner: String) = "anywhere://add-proxy?link=" + URLEncoder.encode(inner, "UTF-8").replace("+", "%2B")

    @Test
    fun `a dashboard's import button yields the link it wrapped`() {
        val inner = "https://dash.example/sub/portal?token=abc123&flag=1"
        assertEquals(inner, ImportLink.unwrap(wrapped(inner)))
    }

    @Test
    fun `the inner link keeps every parameter it had`() {
        // Splitting a *decoded* query would truncate this at the first `&`,
        // which is every subscription URL there is: the token follows the path.
        val inner = "https://dash.example/sub/portal?token=a&b=c&d=e"
        assertEquals(inner, ImportLink.unwrap(wrapped(inner)))
        assertTrue(ImportLink.unwrap(wrapped(inner)).endsWith("d=e"))
    }

    @Test
    fun `a wrapped node link parses as a node`() {
        val inner = "nowhere://secret@portal.example:20001?up=tcp&down=tcp#Frankfurt"
        val unwrapped = ImportLink.unwrap(wrapped(inner))
        val parsed = NowhereUrl.parse(unwrapped)
        assertTrue("expected a node, got $parsed", parsed is DecodeResult.Ok)
        assertEquals("Frankfurt", (parsed as DecodeResult.Ok).value.displayName)
    }

    @Test
    fun `a shared key's literal plus is not turned into a space`() {
        // URLDecoder would. That is the form-encoding rule, not the URI one,
        // and this project has already corrupted a key that way once.
        val inner = "nowhere://a%2Bb@portal.example:20001"
        val outer = "anywhere://add-proxy?link=nowhere%3A%2F%2Fa%252Bb%40portal.example%3A20001"
        assertEquals(inner, ImportLink.unwrap(outer))
        val parsed = NowhereUrl.parse(ImportLink.unwrap(outer))
        assertTrue(parsed is DecodeResult.Ok)
    }

    @Test
    fun `all three declared schemes carry the wrapper`() {
        // Whatever the manifest accepts, the parser has to understand. A scheme
        // that is dispatched to this app and then refused is worse than one
        // that was never claimed.
        listOf("somewhere", "anywhere", "nowhere").forEach { scheme ->
            val outer = "$scheme://add-proxy?link=https%3A%2F%2Fdash.example%2Fsub"
            assertEquals(scheme, "https://dash.example/sub", ImportLink.unwrap(outer))
        }
    }

    @Test
    fun `the opaque form is the same link`() {
        assertEquals("https://dash.example/sub", ImportLink.unwrap("anywhere:add-proxy?link=https%3A%2F%2Fdash.example%2Fsub"))
    }

    @Test
    fun `the action and the scheme are matched case-insensitively`() {
        assertEquals("https://dash.example/sub", ImportLink.unwrap("ANYWHERE://ADD-PROXY?LINK=https%3A%2F%2Fdash.example%2Fsub"))
    }

    @Test
    fun `anything that is not a wrapper comes back exactly as it went in`() {
        // Total on purpose: what this cannot make sense of has to reach the
        // parsers looking as it did, so the reason the user sees is the
        // parser's reason about their link.
        listOf(
            "nowhere://secret@portal.example:20001",
            "https://dash.example/sub/portal?token=abc",
            "anywhere://something-else?link=https%3A%2F%2Fx",
            "anywhere://add-proxy?other=https%3A%2F%2Fx",
            "anywhere://add-proxy",
            "vector://key@host:1",
            "not a url at all",
            "",
            "   ",
            "%%%",
            "anywhere://add-proxy?link=",
        ).forEach { text ->
            assertEquals("'$text' must be untouched", text, ImportLink.unwrap(text))
            assertFalse(ImportLink.isWrapped(text))
        }
    }

    @Test
    fun `a wrapper is recognised as one`() {
        assertTrue(ImportLink.isWrapped("anywhere://add-proxy?link=https%3A%2F%2Fdash.example%2Fsub"))
    }

    @Test
    fun `arbitrary text neither throws nor produces something longer than it was given`() {
        val alphabet = "abcXYZ0123:/?&=%.-_+@[]#"
        var seed = 20260826L
        repeat(20_000) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val length = ((seed ushr 33) % 40).toInt()
            val text =
                (0 until length)
                    .map {
                        seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
                        alphabet[((seed ushr 33) % alphabet.length).toInt()]
                    }.joinToString("")
            val result = ImportLink.unwrap(text)
            assertTrue(
                "unwrapping '$text' produced something longer than the input",
                result.length <= text.length,
            )
        }
    }
}
