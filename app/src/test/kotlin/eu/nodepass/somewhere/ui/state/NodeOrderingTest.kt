// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.data.NodeSort
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.protocol.url.UrlReason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class NodeOrderingTest {
    private fun node(
        name: String,
        status: NodeStatus = NodeStatus.Unknown,
    ): NodeEntry {
        val url = (NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@$name.example:443#$name") as DecodeResult.Ok).value
        return NodeEntry(url, status)
    }

    private val unreachable = NodeStatus.Unreachable(UrlReason.MissingHost)

    private val charlie = node("charlie", NodeStatus.Reachable(300))
    private val alpha = node("alpha", unreachable)
    private val bravo = node("bravo", NodeStatus.Reachable(40))
    private val delta = node("delta", NodeStatus.Probing)
    private val echo = node("echo", NodeStatus.RemovedByProvider)
    private val list = listOf(charlie, alpha, bravo, delta, echo)

    private fun names(entries: List<NodeEntry>) = entries.map { it.displayName }

    private val english = Collator.getInstance(Locale.ENGLISH)

    @Test
    fun `the user's order is left alone`() {
        assertEquals(names(list), names(NodeOrdering.arrange(list, NodeSort.ListOrder, false, collator = english)))
    }

    @Test
    fun `latency puts measured nodes first, fastest first, and failures last`() {
        assertEquals(
            listOf("bravo", "charlie", "delta", "alpha", "echo"),
            names(NodeOrdering.arrange(list, NodeSort.Latency, false, collator = english)),
        )
    }

    @Test
    fun `name sorts by display name`() {
        assertEquals(
            listOf("alpha", "bravo", "charlie", "delta", "echo"),
            names(NodeOrdering.arrange(list, NodeSort.Name, false, collator = english)),
        )
    }

    @Test
    fun `hiding drops nodes that did not answer and nodes the provider withdrew`() {
        assertEquals(
            listOf("charlie", "bravo", "delta"),
            names(NodeOrdering.arrange(list, NodeSort.ListOrder, true, collator = english)),
        )
    }

    @Test
    fun `the chosen node is never hidden`() {
        // A selection the list no longer shows is one the user cannot see or
        // change.
        assertEquals(
            listOf("charlie", "alpha", "bravo", "delta"),
            names(NodeOrdering.arrange(list, NodeSort.ListOrder, true, chosen = alpha.url.toUrl(), collator = english)),
        )
    }

    @Test
    fun `equal latencies keep the user's order`() {
        val first = node("first", NodeStatus.Reachable(100))
        val second = node("second", NodeStatus.Reachable(100))
        assertEquals(
            listOf("second", "first"),
            names(NodeOrdering.arrange(listOf(second, first), NodeSort.Latency, false, collator = english)),
        )
    }
}
