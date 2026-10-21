// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
}
