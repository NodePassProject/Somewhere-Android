// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTrackerTest {
    private var now = 1_000L
    private val tracker = ConnectionTracker { now }

    @Test
    fun `a connection is listed with its own counts until it finishes`() {
        val handle = tracker.open(ConnectionProtocol.Tcp, "example.com:443", ConnectionRoute.Portal) {}
        handle.upstream(100)
        handle.downstream(4_000)
        handle.downstream(96)
        handle.owner("Browser")

        val listed = tracker.snapshot().single()
        assertEquals("example.com:443", listed.target)
        assertEquals(100L, listed.upstreamBytes)
        assertEquals(4_096L, listed.downstreamBytes)
        assertEquals("Browser", listed.app)
        assertEquals(1_000L, listed.openedAtElapsedMillis)

        tracker.finished(handle)
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `newest first`() {
        tracker.open(ConnectionProtocol.Tcp, "a:1", ConnectionRoute.Portal) {}
        tracker.open(ConnectionProtocol.Udp, "b:2", ConnectionRoute.Direct) {}
        assertEquals(listOf("b:2", "a:1"), tracker.snapshot().map { it.target })
    }

    @Test
    fun `closing asks the carrier, and only the one asked for`() {
        val closed = mutableListOf<String>()
        val a = tracker.open(ConnectionProtocol.Tcp, "a:1", ConnectionRoute.Portal) { closed += "a" }
        tracker.open(ConnectionProtocol.Tcp, "b:1", ConnectionRoute.Portal) { closed += "b" }
        tracker.close(a.id)
        assertEquals(listOf("a"), closed)
        tracker.closeAll()
        assertEquals(setOf("a", "b"), closed.toSet())
    }

    @Test
    fun `closing a connection that has gone does nothing`() {
        var calls = 0
        val handle = tracker.open(ConnectionProtocol.Tcp, "a:1", ConnectionRoute.Portal) { calls++ }
        tracker.finished(handle)
        tracker.close(handle.id)
        assertEquals(0, calls)
    }

    @Test
    fun `a carrier that finishes while closing all is not a problem`() {
        // closeAll iterates a copy: a closer that removes its own entry, which
        // every real one does, must not disturb the iteration.
        lateinit var self: ConnectionTracker.Handle
        self = tracker.open(ConnectionProtocol.Tcp, "a:1", ConnectionRoute.Portal) { tracker.finished(self) }
        tracker.open(ConnectionProtocol.Tcp, "b:1", ConnectionRoute.Portal) {}
        tracker.closeAll()
        assertEquals(listOf("b:1"), tracker.snapshot().map { it.target })
    }
}
