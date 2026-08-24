// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import eu.nodepass.somewhere.subscription.SubscriptionReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/**
 * A subscription refresh, end to end, over a real HTTP server.
 *
 * The whole path is exercised — request, response, parse, reconcile, state —
 * because the interesting behaviour is not in any one of those steps but in
 * what happens between them. NW-D-04 in particular is a rule about what the
 * *store* does with what the *fetcher* returned, and neither component can be
 * tested into agreement on its own.
 */
class SubscriptionRefreshTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var server: HttpServer? = null
    private var body: String = ""
    private var status: Int = 200
    private var headers: Map<String, String> = emptyMap()

    private fun serve(): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/sub") { exchange: HttpExchange ->
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/sub?token=not-a-real-token"
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun repository(): NodeRepository =
        NodeRepository(
            store = NodeStore(File(folder.root, "nodes/nodes.txt")),
            subscriptions = SubscriptionStore(File(folder.root, "sub/sub.txt")),
            fetcher = SubscriptionFetcher(clientVersion = "0.1.0"),
            io = Dispatchers.Unconfined,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            clock = { 1_756_000_000_000 },
        )

    private val frankfurt = "nowhere://k@fra04.example.net:443?up=tcp&down=tcp&mux=1#Frankfurt"
    private val singapore = "nowhere://k@sgp11.example.net:443?up=tcp&down=tcp#Singapore"

    @Test
    fun subscribingStoresTheNodesAndTheFigures() =
        runTest {
            body = "$frankfurt\n$singapore"
            headers =
                mapOf(
                    "subscription-userinfo" to "upload=0; download=88465162240; total=214748364800; expire=1796083200",
                    "profile-title" to "Aurora Networks",
                )
            val repository = repository()
            val result = repository.setSubscription(serve())

            assertTrue("expected a subscription, got $result", result is DecodeResult.Ok)
            assertEquals(2, repository.nodes.value.size)
            assertEquals(
                listOf(NodeStore.Origin.Subscription, NodeStore.Origin.Subscription),
                repository.nodes.value.map { it.origin },
            )
            assertEquals("Aurora Networks", repository.subscription.value?.title)
            assertEquals(
                88_465_162_240,
                repository.subscription.value
                    ?.usage
                    ?.downloadBytes,
            )
        }

    @Test
    fun aNodeTheFeedDropsIsKeptAndMarkedRatherThanDeleted() =
        runTest {
            // NW-D-04. The user's list must not silently get shorter.
            body = "$frankfurt\n$singapore"
            val repository = repository()
            repository.setSubscription(serve())
            assertEquals(2, repository.nodes.value.size)

            body = frankfurt
            repository.refreshSubscription()

            assertEquals("the node is kept", 2, repository.nodes.value.size)
            assertEquals(
                NodeStore.Origin.RemovedFromFeed,
                repository.nodes.value
                    .single { it.url.host == "sgp11.example.net" }
                    .origin,
            )
        }

    @Test
    fun anEmptyFeedMarksEveryNodeRatherThanReportingANetworkError() =
        runTest {
            // The failure this rule exists for. A dashboard empties the feed
            // when a subscription lapses, so an empty response is an expired
            // subscription far more often than it is a broken one — and
            // "network error" sends the reader to debug their wifi.
            body = "$frankfurt\n$singapore"
            val repository = repository()
            repository.setSubscription(serve())

            body = ""
            val result = repository.refreshSubscription()

            assertTrue(result is DecodeResult.Invalid)
            assertEquals(SubscriptionReason.NoNodes, (result as DecodeResult.Invalid).reason)
            assertEquals("both nodes are still listed", 2, repository.nodes.value.size)
            assertTrue(
                "and both are marked as gone from the feed",
                repository.nodes.value.all { it.origin == NodeStore.Origin.RemovedFromFeed },
            )
        }

    @Test
    fun aManuallyAddedNodeSurvivesEveryRefresh() =
        runTest {
            val repository = repository()
            repository.add(
                (
                    eu.nodepass.somewhere.protocol.url.NowhereUrl
                        .parse(singapore) as DecodeResult.Ok
                ).value,
            )

            body = frankfurt
            repository.setSubscription(serve())
            body = ""
            repository.refreshSubscription()

            val manual = repository.nodes.value.single { it.url.host == "sgp11.example.net" }
            assertEquals("a feed has no authority over a pasted node", NodeStore.Origin.Manual, manual.origin)
        }

    @Test
    fun aFailedFirstFetchKeepsTheSubscriptionRatherThanDiscardingIt() =
        runTest {
            // Someone on a train subscribes, the fetch fails, and the URL they
            // pasted is gone. The URL is written before the fetch is attempted
            // precisely so that cannot happen.
            status = 503
            body = "unavailable"
            val repository = repository()
            val url = serve()
            val result = repository.setSubscription(url)

            assertTrue(result is DecodeResult.Invalid)
            assertEquals(url, repository.subscription.value?.url)
            assertTrue(repository.lastRefreshFailure.value is SubscriptionReason.HttpStatus)
        }

    @Test
    fun subscribingSurvivesTheCallerGoingAway() =
        runTest {
            // The bug this exists for: the import screen launched the fetch
            // from rememberCoroutineScope() and then closed itself. That scope
            // belongs to the composition, so the fetch was cancelled before it
            // reached the network — the screen closed, the list stayed empty,
            // and nothing reported a failure because nothing had failed.
            //
            // subscribe() takes no scope from its caller. It cannot, which is
            // the point: there is no way to hand it one that dies.
            body = "$frankfurt\n$singapore"
            val repository = repository()

            repository.subscribe(serve())

            assertEquals(2, repository.nodes.value.size)
        }

    @Test
    fun forgettingTheSubscriptionRemovesTheCredentialAndLeavesTheNodes() =
        runTest {
            body = "$frankfurt\n$singapore"
            val repository = repository()
            repository.setSubscription(serve())

            repository.forgetSubscription()

            assertNull(repository.subscription.value)
            assertEquals("the nodes are the user's, not the subscription's", 2, repository.nodes.value.size)
        }

    @Test
    fun refreshingWithNoSubscriptionSaysSoInsteadOfReachingForTheNetwork() =
        runTest {
            val result = repository().refreshSubscription()
            assertTrue(result is DecodeResult.Invalid)
        }
}
