// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {
    private var now = 1_000L
    private val health = NodeHealth { now }

    @Test
    fun `only unreachability moves a connection`() {
        // The rule this file exists for. A Portal answers a rejected
        // authentication frame with silence rather than a close, which looks
        // exactly like a Portal that is down — and a client that failed over on
        // it would try every node in the list on one mistyped character, take
        // several seconds doing it, and then report the last node's failure
        // rather than the real one.
        assertTrue(Failover.shouldMove(Attempt.Unreachable))
        assertFalse("a refusal is configuration, and the next node will refuse too", Failover.shouldMove(Attempt.Refused))
        assertFalse(Failover.shouldMove(Attempt.Succeeded))
    }

    @Test
    fun `the next node is the best one this attempt has not already used`() {
        health.record("a", Attempt.Unreachable)
        val next = Failover.next(listOf("a", "b", "c"), health, tried = setOf("a"))
        assertEquals("b", next)
    }

    @Test
    fun `a node is tried once per attempt`() {
        // Without this, two nodes that each fail over to the other spin until
        // something else notices.
        val next = Failover.next(listOf("a", "b"), health, tried = setOf("a", "b"))
        assertNull(next)
    }

    @Test
    fun `a refusing node is never failed over to`() {
        // It said no. It will say no again, and doing that automatically would
        // spend a connection attempt to learn nothing.
        health.record("b", Attempt.Refused)
        assertNull(Failover.next(listOf("a", "b"), health, tried = setOf("a")))
    }

    @Test
    fun `failover prefers a healthy node over a degraded one`() {
        health.record("b", Attempt.Unreachable)
        health.record("c", Attempt.Succeeded)
        assertEquals("c", Failover.next(listOf("a", "b", "c"), health, tried = setOf("a")))
    }

    @Test
    fun `the preferred node is the best usable one`() {
        health.record("a", Attempt.Unreachable)
        health.record("b", Attempt.Succeeded)
        assertEquals("b", Failover.preferred(listOf("a", "b"), health))
    }

    @Test
    fun `an empty list has no preference and no next`() {
        assertNull(Failover.preferred(emptyList(), health))
        assertNull(Failover.next(emptyList(), health, tried = emptySet()))
    }

    @Test
    fun `a list of nothing but refusing nodes offers none`() {
        health.record("a", Attempt.Refused)
        health.record("b", Attempt.Refused)
        assertNull("offering one would spend an attempt to learn nothing", Failover.preferred(listOf("a", "b"), health))
    }
}
