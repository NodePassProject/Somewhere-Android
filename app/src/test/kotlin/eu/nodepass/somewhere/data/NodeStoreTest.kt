// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store, and the one property it exists to hold: **what comes out is what
 * the parser would have accepted going in.**
 *
 * A store that can hand the protocol layer a node the parser would reject is
 * worse than no store, because the rejection then happens somewhere that has no
 * idea what to do about it.
 */
class NodeStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): NodeStore = NodeStore(File(folder.root, "nodes/nodes.txt"))

    private fun node(url: String): NowhereUrl = (NowhereUrl.parse(url) as DecodeResult.Ok).value

    private val frankfurt = "nowhere://a-key@fra04.example.net:443?up=tcp&down=tcp&mux=1#Frankfurt"
    private val singapore = "nowhere://b-key@sgp11.example.net:443?up=udp&down=udp#Singapore"

    @Test
    fun anAbsentFileIsAnEmptyListAndNotAFailure() {
        // First launch. Nothing to read is the normal case, not an error state.
        assertEquals(emptyList<NodeStore.Entry>(), store().load())
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val original = node(frankfurt)
        val store = store()
        store.add(original)

        val loaded = store.load().single().url
        assertEquals(original.host, loaded.host)
        assertEquals(original.port, loaded.port)
        assertEquals(original.up, loaded.up)
        assertEquals(original.down, loaded.down)
        assertEquals(original.mux, loaded.mux)
        assertEquals(original.alpn, loaded.alpn)
        assertEquals(original.displayName, loaded.displayName)
        assertEquals(original.certificateVerification, loaded.certificateVerification)
        // The key is the field a round trip is most likely to mangle, and the
        // one whose corruption shows up as an authentication failure against a
        // live Portal rather than as anything readable.
        assertEquals(original.sharedKey, loaded.sharedKey)
    }

    @Test
    fun aKeyWithCharactersThatMeanSomethingInAUrlSurvives() {
        // `+` must stay `+` and not become a space, `@` and `#` and `?` must not
        // re-split the URL on the way back in. This is where a store that wrote
        // the key raw instead of encoded quietly authenticates as something else.
        val awkward = "p+a/s?s@w#o&r=d"
        val original =
            node("nowhere://${java.net.URLEncoder.encode(awkward, "UTF-8").replace("+", "%2B")}@h.example.net:443?up=tcp&down=tcp")
        val store = store()
        store.add(original)
        assertEquals(
            original.sharedKey,
            store
                .load()
                .single()
                .url.sharedKey,
        )
    }

    @Test
    fun importingTheSameLinkTwiceDoesNotProduceTwoNodes() {
        // Tapping a dashboard's import button again is the normal case.
        val store = store()
        store.add(node(frankfurt))
        val after = store.add(node(frankfurt))
        assertEquals(1, after.size)
    }

    @Test
    fun aNodeDifferingInOneParameterIsADifferentNode() {
        // On this protocol it genuinely is: up and down select the carrier.
        val store = store()
        store.add(node(frankfurt))
        store.add(node(frankfurt.replace("up=tcp", "up=udp")))
        assertEquals(2, store.load().size)
    }

    @Test
    fun anUnparseableLineCostsThatNodeAndNoOther() {
        // One corrupt line should not take the list with it.
        val file = File(folder.root, "nodes/nodes.txt")
        file.parentFile.mkdirs()
        file.writeText("$frankfurt\nthis is not a url\n$singapore\n")
        val loaded = NodeStore(file).load()
        assertEquals(2, loaded.size)
        assertEquals(listOf("fra04.example.net", "sgp11.example.net"), loaded.map { it.url.host })
    }

    @Test
    fun replaceKeepsThePositionInTheList() {
        // The node editor edits in place. A node that jumped to the end of the
        // list every time it was saved would be its own bug report.
        val store = store()
        store.add(node(frankfurt))
        store.add(node(singapore))
        val edited = node(frankfurt.replace("up=tcp", "up=udp"))
        val after = store.replace(node(frankfurt), edited)
        assertEquals(listOf("fra04.example.net", "sgp11.example.net"), after.map { it.url.host })
        assertEquals(NextHopCarrier.Udp, after.first().url.up)
    }

    @Test
    fun removingTheLastNodeLeavesAReadableEmptyList() {
        val store = store()
        store.add(node(frankfurt))
        assertTrue(store.remove(node(frankfurt)).isEmpty())
        assertTrue("a second load must also be empty, not a parse failure", store.load().isEmpty())
    }

    @Test
    fun aFailedWriteLeavesThePreviousListIntact() {
        // The rename-over-temporary is what buys this. Simulated by making the
        // target a directory, which no write can replace.
        val target = File(folder.root, "nodes/nodes.txt")
        target.parentFile.mkdirs()
        target.writeText("$frankfurt\n")
        val store = NodeStore(target)
        assertEquals(1, store.load().size)

        val blocked = NodeStore(File(folder.root, "nodes"))
        assertFalse("writing over a directory must fail rather than throw", blocked.save(listOf(node(singapore))))
        assertEquals("the real list is untouched", 1, store.load().size)
    }
}
