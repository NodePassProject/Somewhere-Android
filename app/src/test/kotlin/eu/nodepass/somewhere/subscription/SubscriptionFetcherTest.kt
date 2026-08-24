// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * NW-D-01 through NW-D-06, against a real HTTP server rather than a mock.
 *
 * The JDK ships one, so there is no reason to assert against a stubbed client
 * and hope the real request looks the same: these tests see the actual bytes the
 * fetcher puts on the wire, including the query parameters and headers.
 */
class SubscriptionFetcherTest {
    private var server: HttpServer? = null
    private val requests = mutableListOf<String>()

    private fun serve(
        body: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/sub/portal") { exchange: HttpExchange ->
            requests += exchange.requestURI.toString()
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/sub/portal?token=s3cr3t"
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun fetcher() = SubscriptionFetcher(clientVersion = "0.1.0")

    private val twoNodes =
        """
        nowhere://secret@fra04.example.net:443?up=tcp&down=tcp&mux=1#Frankfurt
        nowhere://secret@sin11.example.net:443?up=tcp&down=tcp#Singapore
        """.trimIndent()

    @Test
    fun nodesAreFetchedAndParsed() {
        val url = serve(twoNodes)
        val result = fetcher().fetch(url)
        val subscription = (result as? DecodeResult.Ok)?.value
        assertTrue("fetch failed: ${result.reasonOrNull()?.detail}", subscription != null)
        assertEquals(2, subscription!!.nodes.size)
        assertEquals("Frankfurt", subscription.nodes[0].displayName)
        assertEquals(443, subscription.nodes[1].port)
    }

    @Test
    fun capabilityParametersAreActuallySent() {
        // NW-D-06 asserted against the request the server received, not against
        // the string we built.
        val url = serve(twoNodes)
        fetcher().fetch(url)
        val request = requests.single()
        assertTrue("token must survive", request.contains("token=s3cr3t"))
        assertTrue("type must be sent", request.contains("type=somewhere"))
        assertTrue("version must be sent", request.contains("ver=0.1.0"))
        assertTrue("capabilities must be sent", request.contains("caps=mux"))
    }

    @Test
    fun usageHeaderIsParsed() {
        val url =
            serve(
                twoNodes,
                headers = mapOf("subscription-userinfo" to "upload=0; download=88465182720; total=214748364800; expire=1796169600"),
            )
        val usage = (fetcher().fetch(url) as DecodeResult.Ok).value.usage!!
        assertEquals(88465182720L, usage.downloadBytes)
        assertEquals(214748364800L, usage.totalBytes)
        assertEquals(1796169600L, usage.expiresAtEpochSeconds)
        assertTrue(!usage.isUnlimited)
    }

    @Test
    fun thereIsNoWayToReadAnUploadFigure() {
        // NW-D-02: upstream always reports upload=0. The type deliberately has
        // no field for it, so a UI cannot render "0 B uploaded" as a
        // measurement. This test exists to keep it that way.
        val fields = SubscriptionUsage::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("SubscriptionUsage must not expose an upload figure", fields.none { it.contains("upload") })
    }

    @Test
    fun unlimitedIsRepresentedAsUnlimitedNotAsMinusOne() {
        val url = serve(twoNodes, headers = mapOf("subscription-userinfo" to "upload=0; download=100; total=-1"))
        val usage = (fetcher().fetch(url) as DecodeResult.Ok).value.usage!!
        assertTrue(usage.isUnlimited)
        assertNull("a fraction of no limit is not a number", usage.fractionCounted)
    }

    @Test
    fun aBase64TitleIsDecoded() {
        val encoded =
            java.util.Base64
                .getEncoder()
                .encodeToString("Aurora Networks".toByteArray())
        val url = serve(twoNodes, headers = mapOf("profile-title" to "base64:$encoded"))
        assertEquals("Aurora Networks", (fetcher().fetch(url) as DecodeResult.Ok).value.title)
    }

    @Test
    fun aMalformedTitleIsDroppedRatherThanFailingTheFetch() {
        val url = serve(twoNodes, headers = mapOf("profile-title" to "base64:!!!not base64!!!"))
        val subscription = (fetcher().fetch(url) as DecodeResult.Ok).value
        assertNull(subscription.title)
        assertEquals("the nodes still arrive", 2, subscription.nodes.size)
    }

    @Test
    fun anEmptyFeedIsReportedAsQuotaExhaustionNotAsAnError() {
        // NW-D-04. The dashboard removes nodes when a subscription is over quota
        // or expired, so this is the common case, and calling it a network error
        // would send someone to debug their connection.
        val url = serve("")
        val reason = fetcher().fetch(url).reasonOrNull()
        assertEquals(SubscriptionReason.NoNodes, reason)
        assertTrue(reason!!.detail.contains("expired") || reason.detail.contains("quota"))
    }

    @Test
    fun oneMalformedLineDoesNotCostTheOtherNodes() {
        val url =
            serve(
                """
                nowhere://secret@a.example:443?up=tcp&down=tcp#Good
                this is not a url at all
                vector://secret@b.example:443
                nowhere://secret@c.example:443?up=tcp&down=tcp#Also%20good
                """.trimIndent(),
            )
        val subscription = (fetcher().fetch(url) as DecodeResult.Ok).value
        assertEquals("the two valid nodes survive", 2, subscription.nodes.size)
        assertEquals(listOf("Good", "Also good"), subscription.nodes.map { it.displayName })
    }

    @Test
    fun aNodeNameWithAnUnencodedSpaceIsDroppedRatherThanGuessedAt() {
        // Found while writing the test above, with test data that was itself
        // invalid: a raw space in a fragment is not a legal URL, and the parser
        // refuses it. Recorded rather than quietly relaxed — a fragment is where
        // node names live, so this is the shape a hand-typed link is most likely
        // to arrive in, and it fails the whole line rather than half of it.
        //
        // The dashboard percent-encodes, so its feeds are unaffected.
        val url =
            serve(
                """
                nowhere://secret@a.example:443?up=tcp&down=tcp#Fine
                nowhere://secret@b.example:443?up=tcp&down=tcp#Two Words
                """.trimIndent(),
            )
        val subscription = (fetcher().fetch(url) as DecodeResult.Ok).value
        assertEquals("only the encoded one survives", 1, subscription.nodes.size)
        assertEquals("Fine", subscription.nodes.single().displayName)
    }

    @Test
    fun anHttpErrorIsReportedWithItsStatus() {
        val url = serve("nope", status = 403)
        val reason = fetcher().fetch(url).reasonOrNull()
        assertTrue(reason is SubscriptionReason.HttpStatus)
        assertEquals(403, (reason as SubscriptionReason.HttpStatus).code)
    }

    @Test
    fun plaintextTransportIsReportedToTheCaller() {
        // The test server is http, which is the point: the caller has to be told
        // that a password crossed the network in the clear.
        val url = serve(twoNodes)
        assertTrue((fetcher().fetch(url) as DecodeResult.Ok).value.fetchedOverPlaintext)
    }

    @Test
    fun noFailureReasonEverContainsTheToken() {
        // The rule the whole class is shaped around. Checked across every
        // failure path rather than trusted.
        val secret = "s3cr3t"
        val reasons =
            listOf(
                fetcher().fetch(serve("", status = 500)).reasonOrNull(),
                fetcher().fetch("not-a-url").reasonOrNull(),
                fetcher().fetch("https://127.0.0.1:1/sub/portal?token=$secret").reasonOrNull(),
            )
        reasons.forEach { reason ->
            assertTrue("a reason was expected", reason != null)
            assertTrue("'${reason!!.detail}' must not contain the token", !reason.detail.contains(secret))
        }
    }

    @Test
    fun redirectsAreNotFollowedAutomatically() {
        // A redirect can move a token from an HTTPS host to an HTTP one, or to a
        // host the user never agreed to. Neither may happen without a decision.
        val url = serve("", status = 302, headers = mapOf("Location" to "http://elsewhere.example/steal"))
        val reason = fetcher().fetch(url).reasonOrNull()
        assertTrue(reason is SubscriptionReason.HttpStatus)
        assertEquals(302, (reason as SubscriptionReason.HttpStatus).code)
    }

    @Test
    fun anUnreachableHostFailsAsTransportRatherThanHanging() {
        val reason = fetcher().fetch("http://127.0.0.1:1/sub/portal?token=x").reasonOrNull()
        assertTrue(reason is SubscriptionReason.Transport)
    }
}
