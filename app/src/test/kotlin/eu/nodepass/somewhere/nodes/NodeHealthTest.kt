// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeHealthTest {
    private var now = 1_000L
    private val health = NodeHealth { now }

    @Test
    fun `a node nothing has been asked of is untried rather than failing`() {
        // The distinction the node list already makes on screen, kept here so
        // the two cannot drift: never probed is not the same as probed and
        // broken, and a client that conflated them would rank a brand-new node
        // behind one that has failed four times.
        assertEquals(Health.Untried, health.health("a"))
    }

    @Test
    fun `consecutive failures are counted and a success clears them`() {
        health.record("a", Attempt.Unreachable)
        health.record("a", Attempt.Unreachable)
        assertEquals(Health.Degraded(2), health.health("a"))

        health.record("a", Attempt.Succeeded)
        assertEquals(Health.Healthy, health.health("a"))
    }

    @Test
    fun `a refusal is a different axis from a failure`() {
        // A refusal says the node will keep saying no, which no number of
        // retries changes. Counting it as a failure would rank two
        // incomparable things against each other.
        health.record("a", Attempt.Unreachable)
        health.record("a", Attempt.Refused)
        assertEquals(Health.Refusing, health.health("a"))
        assertFalse("a refusing node must not be handed traffic automatically", health.health("a").isUsable)
    }

    @Test
    fun `a success after a refusal clears it`() {
        // The user fixed the key. Nothing else here would notice.
        health.record("a", Attempt.Refused)
        health.record("a", Attempt.Succeeded)
        assertEquals(Health.Healthy, health.health("a"))
    }

    @Test
    fun `an outcome older than the memory window stops counting`() {
        // Without this the first list a user builds is the last one they can
        // use: every node that ever failed stays condemned and the ranking
        // freezes. A node that failed on the coffee shop's Wi-Fi is not a
        // failing node in the user's kitchen.
        health.record("a", Attempt.Unreachable)
        assertEquals(Health.Degraded(1), health.health("a"))

        now += NodeHealth.MEMORY_WINDOW_MILLIS - 1
        assertEquals("still inside the window", Health.Degraded(1), health.health("a"))

        now += 2
        assertEquals("and outside it, forgotten rather than forgiven", Health.Untried, health.health("a"))
    }

    @Test
    fun `health decides the order and latency only breaks ties`() {
        // A node that answers a probe in 12 ms and then carries nothing is
        // worse than one that answers in 200 ms and works. Sorting on latency
        // first would put the fast broken one at the top of the list forever.
        health.record("fast-but-broken", Attempt.Unreachable)
        health.record("slow-but-working", Attempt.Succeeded)

        val order =
            health.ranked(
                listOf("fast-but-broken", "slow-but-working"),
                latencyMillis = { if (it == "fast-but-broken") 12 else 200 },
            )
        assertEquals(listOf("slow-but-working", "fast-but-broken"), order)
    }

    @Test
    fun `an untried node sorts between healthy and degraded`() {
        health.record("healthy", Attempt.Succeeded)
        health.record("degraded", Attempt.Unreachable)
        assertEquals(
            listOf("healthy", "untried", "degraded"),
            health.ranked(listOf("degraded", "untried", "healthy")),
        )
    }

    @Test
    fun `more failures sort further back`() {
        health.record("once", Attempt.Unreachable)
        repeat(3) { health.record("thrice", Attempt.Unreachable) }
        assertEquals(listOf("once", "thrice"), health.ranked(listOf("thrice", "once")))
    }

    @Test
    fun `a refusing node is sorted last rather than removed`() {
        // A list of five nodes offering four, with no way to see why, is worse
        // than a list of five where one is at the bottom.
        health.record("refusing", Attempt.Refused)
        val order = health.ranked(listOf("refusing", "ok"))
        assertEquals(listOf("ok", "refusing"), order)
        assertEquals("nothing may be dropped from the order", 2, order.size)
    }

    @Test
    fun `the user's own order is the final tiebreak`() {
        // Two nodes nothing is known about and neither measured. Inventing a
        // preference here would reorder somebody's list for no reason.
        assertEquals(listOf("first", "second"), health.ranked(listOf("first", "second")))
        assertEquals(listOf("second", "first"), health.ranked(listOf("second", "first")))
    }

    @Test
    fun `a measured node sorts ahead of an unmeasured one`() {
        assertEquals(
            listOf("measured", "unmeasured"),
            health.ranked(listOf("unmeasured", "measured"), latencyMillis = { if (it == "measured") 300 else null }),
        )
    }

    @Test
    fun `clearing forgets everything`() {
        health.record("a", Attempt.Refused)
        health.clear()
        assertEquals(Health.Untried, health.health("a"))
    }

    @Test
    fun `recording is safe from several threads`() {
        // The tunnel service records outcomes from its own threads while the
        // screen ranks the list on the main one. A HashMap resized under a
        // concurrent read does not throw reliably; it corrupts and is found
        // later somewhere else.
        val threads =
            (1..8).map { index ->
                Thread {
                    repeat(200) {
                        health.record("node-${index % 3}", Attempt.Unreachable)
                        health.ranked(listOf("node-0", "node-1", "node-2"))
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("every node should have been recorded", health.health("node-0") is Health.Degraded)
    }
}
