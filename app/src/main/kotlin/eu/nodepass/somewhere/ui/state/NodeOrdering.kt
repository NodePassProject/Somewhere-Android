// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.data.NodeSort
import java.text.Collator

/**
 * The node list as the screen shows it: sorted, and perhaps filtered.
 *
 * Presentation only. The stored order is the user's and is never changed by
 * this — sorting by latency must not reshuffle a list someone arranged by hand,
 * and automatic selection reads health, not this order.
 */
object NodeOrdering {
    /**
     * @param chosen the chosen node's URL. Never hidden, whatever the filter
     *   says: a selection the list no longer shows is a selection the user
     *   cannot see or change.
     */
    fun arrange(
        entries: List<NodeEntry>,
        sort: NodeSort,
        hideUnreachable: Boolean,
        chosen: String? = null,
        collator: Collator = Collator.getInstance(),
    ): List<NodeEntry> {
        val visible =
            if (hideUnreachable) {
                entries.filter { it.answers || it.url.toUrl() == chosen }
            } else {
                entries
            }
        return when (sort) {
            NodeSort.ListOrder -> visible
            // Stable, so equal keys keep the user's order.
            NodeSort.Latency ->
                visible.sortedWith(
                    compareBy<NodeEntry> { rank(it.status) }
                        .thenBy { it.latencyMillis ?: Int.MAX_VALUE },
                )
            NodeSort.Name -> visible.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        }
    }

    /** Whether a node is worth showing when unreachable ones are hidden. */
    private val NodeEntry.answers: Boolean
        get() = status !is NodeStatus.Unreachable && status !is NodeStatus.RemovedByProvider

    /**
     * Measured first, then the ones still being measured, then the failures.
     *
     * A node with no measurement is not fast, so it does not sort among the
     * fast ones; a node that failed is not slow, it is a different answer, so
     * it goes after everything that might still work.
     */
    private fun rank(status: NodeStatus): Int =
        when (status) {
            is NodeStatus.Reachable -> 0
            NodeStatus.Probing, NodeStatus.Unknown -> 1
            is NodeStatus.Unreachable -> 2
            NodeStatus.RemovedByProvider -> 3
        }
}
