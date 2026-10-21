// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.nodes

/**
 * Which node the user wants traffic to go through.
 *
 * Two answers, and the difference between them is who is allowed to decide.
 * [Chosen] is a person's pick: it is used as given, and an unreachable chosen
 * node is reported rather than swapped for another — they chose it for a reason
 * this client cannot see, a region or a quota or a Portal they run. [Automatic]
 * hands the decision to [Failover], which is the only place the client is
 * allowed to be clever about it.
 */
sealed interface NodeChoice {
    data object Automatic : NodeChoice

    data class Chosen(
        /** The node's rendered URL, which is its identity everywhere else. */
        val node: String,
    ) : NodeChoice

    /** Whether an unreachable node may be replaced by another without asking. */
    val mayFailOver: Boolean get() = this is Automatic

    companion object {
        /** What is written to disk for [Automatic]. A URL can never be this. */
        const val AUTOMATIC_MARKER: String = "automatic"

        /** Reads a stored choice. Anything unreadable is [Automatic]. */
        fun fromStored(text: String?): NodeChoice {
            val trimmed = text?.trim().orEmpty()
            return if (trimmed.isEmpty() || trimmed == AUTOMATIC_MARKER) Automatic else Chosen(trimmed)
        }

        fun NodeChoice.toStored(): String =
            when (this) {
                Automatic -> AUTOMATIC_MARKER
                is Chosen -> node
            }

        /**
         * The node a choice comes to, or null when there is none to use.
         *
         * A chosen node that can no longer be chosen falls back to the
         * automatic answer rather than to nothing: deleted, replaced by a
         * refresh, or withdrawn by its subscription (NW-D-04 — expired or out
         * of quota, so dialling it only fails). A Connect button that silently
         * did nothing would be worse, and the list shows "Automatic" selected in
         * that case, so the fallback is visible. The stored choice is kept, so a
         * node that comes back is chosen again.
         *
         * @param selectable the nodes that may be used at all: listed, and not
         *   withdrawn by their subscription. Automatic picks among these too.
         */
        fun resolve(
            choice: NodeChoice,
            selectable: List<String>,
            health: NodeHealth,
            latencyMillis: (String) -> Int? = { null },
        ): String? {
            if (choice is Chosen && choice.node in selectable) return choice.node
            return Failover.preferred(selectable, health, latencyMillis)
        }

        /** [choice] as it should be shown: a chosen node that cannot be used is automatic. */
        fun effective(
            choice: NodeChoice,
            selectable: List<String>,
        ): NodeChoice = if (choice is Chosen && choice.node !in selectable) Automatic else choice
    }
}
