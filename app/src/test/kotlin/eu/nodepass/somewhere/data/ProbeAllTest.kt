// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

/**
 * Re-testing the whole list.
 *
 * Against ports nothing listens on, so every probe ends quickly and the same
 * way on every machine; what is under test is which nodes are dialled and what
 * is recorded, not what a Portal says.
 */
class ProbeAllTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A local port that was free a moment ago, so a dial to it is refused. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun url(port: Int): NowhereUrl =
        (NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@127.0.0.1:$port#n$port") as DecodeResult.Ok).value

    @Test
    fun `every node is probed except the ones the subscription withdrew`() {
        val live = List(5) { url(closedPort()) }
        val withdrawn = url(closedPort())
        NodeStore(File(folder.root, "nodes.txt")).saveEntries(
            live.map { NodeStore.Entry(it, it.toUrl(), NodeStore.Origin.Manual) } +
                NodeStore.Entry(withdrawn, withdrawn.toUrl(), NodeStore.Origin.RemovedFromFeed),
        )
        val repository =
            NodeRepository(
                store = NodeStore(File(folder.root, "nodes.txt")),
                subscriptions = SubscriptionStore(File(folder.root, "sub.txt")),
                fetcher = SubscriptionFetcher(clientVersion = "0.1.0"),
                io = Dispatchers.IO,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )

        runBlocking { repository.probeAll() }

        val probes = repository.probes.value
        assertEquals(live.map { it.toUrl() }.toSet(), probes.keys)
        assertTrue(
            "every live node should have an answer, not still be probing: $probes",
            probes.values.all { it is ProbeResult.Unreachable },
        )
        assertNull("a withdrawn node is not dialled (NW-D-04)", probes[withdrawn.toUrl()])
    }

    @Test
    fun `a QUIC node is probed over QUIC and never dialled over TCP`() {
        // Port 1 is closed on TCP, so a TLS dial would fail; the fake QUIC
        // probe answers. If the repository fell back to TCP the node would read
        // as unreachable.
        val quic = (NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@127.0.0.1:1?net=udp#q") as DecodeResult.Ok).value
        NodeStore(File(folder.root, "nodes.txt")).save(listOf(quic))
        val asked = mutableListOf<NowhereUrl>()
        val repository =
            NodeRepository(
                store = NodeStore(File(folder.root, "nodes.txt")),
                subscriptions = SubscriptionStore(File(folder.root, "sub.txt")),
                fetcher = SubscriptionFetcher(clientVersion = "0.1.0"),
                quicProbe = { node ->
                    asked += node
                    null
                },
                io = Dispatchers.IO,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )

        runBlocking { repository.probeAll() }

        assertEquals(listOf(quic), asked)
        assertTrue(repository.probes.value[quic.toUrl()] is ProbeResult.Reachable)
    }

    @Test
    fun `a QUIC refusal is carried through as the probe's answer`() {
        val quic = (NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@127.0.0.1:1?net=udp#q") as DecodeResult.Ok).value
        val repository =
            NodeRepository(
                store = NodeStore(File(folder.root, "other.txt")),
                subscriptions = SubscriptionStore(File(folder.root, "sub.txt")),
                fetcher = SubscriptionFetcher(clientVersion = "0.1.0"),
                quicProbe = {
                    eu.nodepass.somewhere.net.DialReason
                        .Untrusted("pin")
                },
                io = Dispatchers.IO,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
        val result = runBlocking { repository.probe(quic) }
        assertEquals(
            ProbeResult.Unreachable(
                eu.nodepass.somewhere.net.DialReason
                    .Untrusted("pin"),
            ),
            result,
        )
    }

    @Test
    fun `overlapping runs never dial a node twice, and never more than four at once`() {
        val nodes =
            List(
                12,
            ) { i ->
                (
                    NowhereUrl.parse(
                        "nowhere://0123456789abcdef0123456789abcdef@127.0.0.1:${i + 1}?net=udp#q$i",
                    ) as DecodeResult.Ok
                ).value
            }
        NodeStore(File(folder.root, "many.txt")).save(nodes)
        val inFlight =
            java.util.concurrent.atomic
                .AtomicInteger()
        var peak = 0
        val dialled = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val repository =
            NodeRepository(
                store = NodeStore(File(folder.root, "many.txt")),
                subscriptions = SubscriptionStore(File(folder.root, "sub.txt")),
                fetcher = SubscriptionFetcher(clientVersion = "0.1.0"),
                quicProbe = { node ->
                    val now = inFlight.incrementAndGet()
                    synchronized(this) { peak = maxOf(peak, now) }
                    dialled.merge(node.toUrl(), 1, Int::plus)
                    Thread.sleep(30)
                    inFlight.decrementAndGet()
                    null
                },
                io = Dispatchers.IO,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
        runBlocking {
            kotlinx.coroutines.coroutineScope {
                launch { repository.probeAll() }
                launch { repository.probeAll() }
                launch { repository.probeAll(onlyUnprobed = true) }
            }
        }
        assertTrue("every node dialled once, got $dialled", dialled.values.all { it == 1 })
        assertTrue("at most ${NodeRepository.PROBE_PARALLELISM} at once, saw $peak", peak <= NodeRepository.PROBE_PARALLELISM)
    }
}
