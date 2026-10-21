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
         * A chosen node that is no longer listed falls back to the automatic
         * answer rather than to nothing. The likeliest reason it is missing is
         * that the user deleted it or a subscription refresh replaced it, and a
         * Connect button that silently did nothing would be the worse outcome —
         * the screen shows "Automatic" in that case, so the fallback is visible.
         *
         * @param listed every node the user has, in their own order.
         * @param automaticCandidates the nodes automatic selection may use: the
         *   listed ones minus any the subscription has withdrawn, which would
         *   only be dialled to fail.
         */
        fun resolve(
            choice: NodeChoice,
            listed: List<String>,
            automaticCandidates: List<String>,
            health: NodeHealth,
            latencyMillis: (String) -> Int? = { null },
        ): String? {
            if (choice is Chosen && choice.node in listed) return choice.node
            return Failover.preferred(automaticCandidates, health, latencyMillis)
        }

        /** [choice] as it should be shown: a chosen node that is gone is automatic. */
        fun effective(
            choice: NodeChoice,
            listed: List<String>,
        ): NodeChoice = if (choice is Chosen && choice.node !in listed) Automatic else choice
    }
}
