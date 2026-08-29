// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.nodes

/**
 * Whether to try another node, and which.
 *
 * Separate from [NodeHealth] because the two answer different questions and
 * only one of them is allowed to be clever. Health is a measurement; this is a
 * policy, and the policy is deliberately small.
 *
 * ## The rule that matters
 *
 * **Only unreachability moves a connection.** Everything else — a refused
 * authentication, a Portal that answered and said no, a node the user
 * configured wrongly — stays where it is and reports what happened. See
 * [Attempt] for why: a Portal answers a bad key with silence, so a client that
 * treated silence as unreachability would walk the whole list on one typo and
 * then blame the last node.
 *
 * ## The other rule
 *
 * **A node is tried once per attempt.** Without that, two nodes that each fail
 * over to the other spin until something else notices. The excluded set is the
 * caller's, so an attempt that spans a reconnect can carry it or drop it
 * deliberately rather than by accident.
 */
object Failover {
    /** Whether [attempt] is the kind of failure another node could fix. */
    fun shouldMove(attempt: Attempt): Boolean = attempt is Attempt.Unreachable

    /**
     * The next node to try, or null when there is none worth trying.
     *
     * @param candidates every node the user has, in their own order.
     * @param tried nodes this attempt has already used, including the one that
     *   just failed.
     */
    fun next(
        candidates: List<String>,
        health: NodeHealth,
        tried: Set<String>,
        latencyMillis: (String) -> Int? = { null },
    ): String? =
        health
            .ranked(candidates, latencyMillis)
            .firstOrNull { it !in tried && health.health(it).isUsable }

    /**
     * The node to start on when nobody has chosen one.
     *
     * **Not used when the user has chosen.** An automatic selection that
     * overrode a person's pick would be a defect wearing a feature's clothes:
     * they chose that node for a reason this client cannot see — a region, a
     * subscription's quota, a Portal they administer — and the ranking here
     * knows none of it.
     */
    fun preferred(
        candidates: List<String>,
        health: NodeHealth,
        latencyMillis: (String) -> Int? = { null },
    ): String? = health.ranked(candidates, latencyMillis).firstOrNull { health.health(it).isUsable }
}
