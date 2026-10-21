// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class ImportBatchTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val frankfurt = "nowhere://$key@fra.example.net:443#Frankfurt"
    private val singapore = "nowhere://$key@sgp.example.net:443?net=udp#Singapore"
    private val feed = "https://dash.example.net/sub?token=abc"

    @Test
    fun `one link is not a batch, two are`() {
        assertFalse(ImportBatch.isBatch(frankfurt))
        assertFalse(ImportBatch.isBatch("\n  $frankfurt \n\n"))
        assertTrue(ImportBatch.isBatch("$frankfurt\n$singapore"))
    }

    @Test
    fun `nodes and subscriptions are sorted apart, in order`() {
        val batch = ImportBatch.read("$frankfurt\n$feed\n\n$singapore\n")
        assertEquals(listOf("fra.example.net", "sgp.example.net"), batch.nodes.map { it.host })
        assertEquals(listOf(feed), batch.subscriptions)
        assertTrue(batch.rejected.isEmpty())
    }

    @Test
    fun `a line counted twice is one node`() {
        val batch = ImportBatch.read("$frankfurt\n$frankfurt\n$feed\n$feed")
        assertEquals(1, batch.nodes.size)
        assertEquals(1, batch.subscriptions.size)
    }

    @Test
    fun `a line that is neither is reported with its line number`() {
        val batch = ImportBatch.read("$frankfurt\nnot a link\n\nvector://x@y:1")
        assertEquals(listOf(2, 4), batch.rejected.map { it.line })
        assertEquals(1, batch.nodes.size)
    }

    @Test
    fun `a plaintext subscription is refused, as it is on its own`() {
        val batch = ImportBatch.read("$frankfurt\nhttp://dash.example.net/sub?token=abc")
        assertTrue(batch.subscriptions.isEmpty())
        assertEquals(ImportBatch.PlaintextSubscription, batch.rejected.single().reason)
    }

    @Test
    fun `a dashboard's wrapped link is unwrapped line by line`() {
        val wrapped = "anywhere://add-proxy?link=" + URLEncoder.encode(feed, "UTF-8")
        val batch = ImportBatch.read("$wrapped\n$frankfurt")
        assertEquals(listOf(feed), batch.subscriptions)
    }

    @Test
    fun `nothing understood is empty`() {
        assertTrue(ImportBatch.read("a\nb").isEmpty)
        assertFalse(ImportBatch.read("$frankfurt\nb").isEmpty)
    }
}
