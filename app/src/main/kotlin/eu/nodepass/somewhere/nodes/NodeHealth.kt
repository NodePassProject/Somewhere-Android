// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.nodes

/**
 * What happened the last time something asked a node to carry traffic.
 *
 * Three outcomes rather than success and failure, because **the difference
 * between the two failures decides whether moving to another node is sensible
 * or catastrophic.**
 *
 * A Portal answers a rejected authentication frame with silence rather than a
 * close — deliberately, so that failure is not an oracle for active probing.
 * That is the same shape on the wire as a Portal that is down. But the causes
 * could not be more different: one is a node that is unreachable right now, and
 * the other is a shared key that is wrong and will still be wrong on the next
 * node in the list. A client that failed over on the second walks the entire
 * list on one mistyped character and then reports the last node's failure,
 * which is the wrong node, the wrong message, and several seconds of connection
 * attempts against Portals that never had a chance.
 */
sealed interface Attempt {
    /** A tunnel came up on this node. */
    data object Succeeded : Attempt

    /**
     * The node could not be reached: no route, refused connection, timeout, a
     * TLS handshake that never completed.
     *
     * The only outcome that justifies trying a different node.
     */
    data object Unreachable : Attempt

    /**
     * The node was reached and said no — or said nothing, which for
     * authentication is the same thing.
     *
     * Configuration rather than availability. Another node will answer the same
     * way, so this ends the attempt rather than moving it along.
     */
    data object Refused : Attempt
}

/** What is known about a node, as a state rather than a colour. */
sealed interface Health {
    /** Nothing has been asked of it yet. Not the same as failing. */
    data object Untried : Health

    /** It worked, recently enough to still mean something. */
    data object Healthy : Health

    /** It has failed, and how many times in a row is the useful part. */
    data class Degraded(
        val consecutiveFailures: Int,
    ) : Health

    /** It has refused. Trying it again changes nothing until the user does. */
    data object Refusing : Health

    /** Whether this node is worth handing traffic to without being told to. */
    val isUsable: Boolean get() = this !is Refusing
}

/**
 * A memory of how nodes have behaved, with the memory fading.
 *
 * ## Why it decays
 *
 * A node that failed this morning is not a node that is failing now. Without
 * decay the first list a user builds is the last one they can use: every node
 * that ever failed stays condemned, the ranking freezes, and the only way out
 * is to reinstall. So a failure older than [MEMORY_WINDOW_MILLIS] no longer
 * counts against a node, and a node that has done nothing for that long is
 * [Health.Untried] again rather than permanently second best.
 *
 * ## Why it is not persisted
 *
 * The device's network is what this measures, and it changes when the device
 * moves. Health carried across a restart would describe the coffee shop's
 * Wi-Fi in the user's kitchen. Latency from a probe *is* persisted, by the
 * repository, because a measurement is a measurement; a verdict is not.
 *
 * Pure and clock-injected, so a decay window can be tested in microseconds
 * rather than waited out.
 */
class NodeHealth(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Record(
        var consecutiveFailures: Int = 0,
        var refused: Boolean = false,
        var lastOutcomeAt: Long = 0,
    )

    private val records = HashMap<String, Record>()
    private val lock = Any()

    fun record(
        node: String,
        attempt: Attempt,
    ) {
        synchronized(lock) {
            val record = records.getOrPut(node) { Record() }
            record.lastOutcomeAt = clock()
            when (attempt) {
                Attempt.Succeeded -> {
                    record.consecutiveFailures = 0
                    record.refused = false
                }

                Attempt.Unreachable -> {
                    record.consecutiveFailures++
                    record.refused = false
                }

                // Not counted as a failure as well. A refusal is a different
                // axis: it says the node will keep saying no, which no number
                // of retries changes, and adding it to the failure count would
                // make a rank order out of two incomparable things.
                Attempt.Refused -> record.refused = true
            }
        }
    }

    fun health(node: String): Health =
        synchronized(lock) {
            val record = records[node] ?: return Health.Untried
            if (clock() - record.lastOutcomeAt >= MEMORY_WINDOW_MILLIS) {
                records.remove(node)
                return Health.Untried
            }
            when {
                record.refused -> Health.Refusing
                record.consecutiveFailures > 0 -> Health.Degraded(record.consecutiveFailures)
                else -> Health.Healthy
            }
        }

    /** Forgets everything. For a user who has changed their list, or a test. */
    fun clear() = synchronized(lock) { records.clear() }

    /**
     * The order to try nodes in, best first.
     *
     * Health decides first and latency breaks ties, in that order and not the
     * other way round: a node that answers in 12 ms and then fails to carry
     * anything is worse than one that answers in 200 ms and works. A node
     * nothing is known about sorts between healthy and degraded — optimism
     * about the untried, because the alternative is a list where a node can
     * never earn its place back.
     *
     * A refusing node is **not removed**, only sorted last. Removing it would
     * mean a list of five nodes offering four, with no way to see why.
     */
    fun ranked(
        nodes: List<String>,
        latencyMillis: (String) -> Int? = { null },
    ): List<String> =
        nodes.sortedWith(
            compareBy<String> { rank(health(it)) }
                .thenBy { latencyMillis(it) ?: UNMEASURED_LATENCY }
                .thenBy { nodes.indexOf(it) },
        )

    private fun rank(health: Health): Int =
        when (health) {
            Health.Healthy -> 0
            Health.Untried -> 1
            is Health.Degraded -> 2 + health.consecutiveFailures
            Health.Refusing -> Int.MAX_VALUE
        }

    companion object {
        /**
         * How long an outcome still describes a node.
         *
         * Ten minutes: long enough that a burst of failures during one outage
         * is remembered as one outage, short enough that a node is not
         * condemned by a network the device has since left.
         */
        const val MEMORY_WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /**
         * Where a node with no latency measurement sorts.
         *
         * Behind every measured node rather than in front of them: an unknown
         * is not evidence of being fast. It is not `MAX_VALUE`, so that the
         * final tiebreak — the user's own list order — still decides between
         * two unmeasured nodes.
         */
        const val UNMEASURED_LATENCY: Int = Int.MAX_VALUE / 2
    }
}
