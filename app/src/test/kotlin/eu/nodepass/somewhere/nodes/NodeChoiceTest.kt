// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.nodes

import eu.nodepass.somewhere.nodes.NodeChoice.Companion.toStored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeChoiceTest {
    private var now = 1_000L
    private val health = NodeHealth { now }

    @Test
    fun `a chosen node is used as given, even when another is faster`() {
        // The user picked it for a reason the client cannot see.
        val picked =
            NodeChoice.resolve(
                NodeChoice.Chosen("b"),
                listed = listOf("a", "b"),
                automaticCandidates = listOf("a", "b"),
                health = health,
                latencyMillis = { if (it == "a") 10 else 900 },
            )
        assertEquals("b", picked)
    }

    @Test
    fun `a chosen node may be one automatic selection would never use`() {
        // A node the subscription withdrew is not an automatic candidate, but a
        // person who picks it anyway gets it.
        val picked =
            NodeChoice.resolve(
                NodeChoice.Chosen("withdrawn"),
                listed = listOf("a", "withdrawn"),
                automaticCandidates = listOf("a"),
                health = health,
            )
        assertEquals("withdrawn", picked)
    }

    @Test
    fun `automatic takes the best usable candidate`() {
        health.record("a", Attempt.Unreachable)
        val picked =
            NodeChoice.resolve(
                NodeChoice.Automatic,
                listed = listOf("a", "b", "c"),
                automaticCandidates = listOf("a", "b", "c"),
                health = health,
                latencyMillis = { if (it == "c") 40 else 300 },
            )
        assertEquals("c", picked)
    }

    @Test
    fun `a chosen node that is gone falls back to automatic`() {
        // Deleted, or replaced by a refresh. A Connect button that did nothing
        // would be the worse answer, and the screen shows "Automatic".
        val picked =
            NodeChoice.resolve(
                NodeChoice.Chosen("gone"),
                listed = listOf("a"),
                automaticCandidates = listOf("a"),
                health = health,
            )
        assertEquals("a", picked)
        assertEquals(NodeChoice.Automatic, NodeChoice.effective(NodeChoice.Chosen("gone"), listOf("a")))
        assertEquals(NodeChoice.Chosen("a"), NodeChoice.effective(NodeChoice.Chosen("a"), listOf("a")))
    }

    @Test
    fun `nothing usable resolves to nothing`() {
        health.record("a", Attempt.Refused)
        assertNull(
            NodeChoice.resolve(
                NodeChoice.Automatic,
                listed = listOf("a"),
                automaticCandidates = listOf("a"),
                health = health,
            ),
        )
        assertNull(NodeChoice.resolve(NodeChoice.Automatic, emptyList(), emptyList(), health))
    }

    @Test
    fun `only automatic may fail over`() {
        assertTrue(NodeChoice.Automatic.mayFailOver)
        assertFalse(NodeChoice.Chosen("a").mayFailOver)
    }

    @Test
    fun `a choice survives being stored`() {
        val url = "nowhere://0123456789abcdef0123456789abcdef@a.example:443#A"
        assertEquals(NodeChoice.Chosen(url), NodeChoice.fromStored(NodeChoice.Chosen(url).toStored()))
        assertEquals(NodeChoice.Automatic, NodeChoice.fromStored(NodeChoice.Automatic.toStored()))
    }

    @Test
    fun `nothing stored, or nothing readable, is automatic`() {
        assertEquals(NodeChoice.Automatic, NodeChoice.fromStored(null))
        assertEquals(NodeChoice.Automatic, NodeChoice.fromStored(""))
        assertEquals(NodeChoice.Automatic, NodeChoice.fromStored("  \n"))
    }
}
