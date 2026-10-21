// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.nodes.Attempt
import eu.nodepass.somewhere.nodes.NodeChoice
import eu.nodepass.somewhere.nodes.NodeHealth
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The choice as the repository keeps it: on disk, through edits and deletions.
 *
 * The resolution rules are [NodeChoiceTest]'s; what is tested here is that the
 * stored choice follows a node when its URL changes and goes when it does.
 */
class NodeChoiceRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val health = NodeHealth { 1_000L }

    private fun repository(): NodeRepository =
        NodeRepository(
            store = NodeStore(File(folder.root, "nodes/nodes.txt")),
            subscriptions = SubscriptionStore(File(folder.root, "sub/sub.txt")),
            fetcher = SubscriptionFetcher(clientVersion = "0.1.0"),
            io = Dispatchers.Unconfined,
            scope = CoroutineScope(Dispatchers.Unconfined),
            choiceFile = File(folder.root, "nodes/choice.txt"),
            health = health,
        )

    private fun url(text: String): NowhereUrl = (NowhereUrl.parse(text) as DecodeResult.Ok).value

    private val frankfurt = url("nowhere://0123456789abcdef0123456789abcdef@fra.example.net:443#Frankfurt")
    private val singapore = url("nowhere://0123456789abcdef0123456789abcdef@sgp.example.net:443#Singapore")

    @Test
    fun `with nothing chosen the first usable node is the target`() {
        val repository = repository()
        repository.add(frankfurt)
        repository.add(singapore)
        assertEquals(NodeChoice.Automatic, repository.choice.value)
        assertEquals(frankfurt, repository.target()?.url)
    }

    @Test
    fun `a choice is the target and survives a restart`() {
        repository().apply {
            add(frankfurt)
            add(singapore)
            choose(NodeChoice.Chosen(singapore.toUrl()))
            assertEquals(singapore, target()?.url)
        }
        val reopened = repository()
        assertEquals(NodeChoice.Chosen(singapore.toUrl()), reopened.choice.value)
        assertEquals(singapore, reopened.target()?.url)
    }

    @Test
    fun `editing the chosen node keeps it chosen`() {
        val repository = repository()
        repository.add(frankfurt)
        repository.add(singapore)
        repository.choose(NodeChoice.Chosen(singapore.toUrl()))

        val renamed = singapore.copy(displayName = "Singapore 2")
        repository.replace(singapore, renamed)

        assertEquals(NodeChoice.Chosen(renamed.toUrl()), repository.choice.value)
        assertEquals(renamed, repository.target()?.url)
    }

    @Test
    fun `deleting the chosen node returns the choice to automatic`() {
        val repository = repository()
        repository.add(frankfurt)
        repository.add(singapore)
        repository.choose(NodeChoice.Chosen(singapore.toUrl()))

        repository.remove(singapore)

        assertEquals(NodeChoice.Automatic, repository.choice.value)
        // And it stays gone: importing the same link later is a new decision.
        repository.add(singapore)
        assertEquals(frankfurt, repository.target()?.url)
    }

    @Test
    fun `automatic never picks a node the subscription withdrew`() {
        val store = NodeStore(File(folder.root, "nodes/nodes.txt"))
        store.saveEntries(
            listOf(
                NodeStore.Entry(frankfurt, frankfurt.toUrl(), NodeStore.Origin.RemovedFromFeed),
                NodeStore.Entry(singapore, singapore.toUrl(), NodeStore.Origin.Subscription),
            ),
        )
        val repository = repository()
        assertEquals(singapore, repository.target()?.url)
        assertEquals(singapore, repository.automaticTarget()?.url)

        // Nor one that was chosen before it was withdrawn: the choice reads
        // as automatic until the node comes back.
        repository.choose(NodeChoice.Chosen(frankfurt.toUrl()))
        assertEquals(singapore, repository.target()?.url)
        assertEquals(NodeChoice.Automatic, repository.effectiveChoice())
    }

    @Test
    fun `with nothing usable there is still a target, so the tunnel can say why`() {
        // Every node refused: Automatic has no answer, but Home must not claim
        // there are no nodes, and Connect must reach the node to report the
        // refusal rather than "no node was given".
        val repository = repository()
        repository.add(frankfurt)
        health.record(frankfurt.toUrl(), Attempt.Refused)
        assertEquals(frankfurt, repository.target()?.url)
    }

    @Test
    fun `every node withdrawn still leaves a target`() {
        NodeStore(File(folder.root, "nodes/nodes.txt")).saveEntries(
            listOf(NodeStore.Entry(frankfurt, frankfurt.toUrl(), NodeStore.Origin.RemovedFromFeed)),
        )
        assertEquals(frankfurt, repository().target()?.url)
    }

    @Test
    fun `health changes are announced`() {
        val repository = repository()
        val before = repository.healthChanges.value
        health.record(frankfurt.toUrl(), Attempt.Unreachable)
        assertEquals(before + 1, repository.healthChanges.value)
    }

    @Test
    fun `feed nodes left by a forgotten subscription become the user's on load`() {
        // The single-subscription "forget" kept nodes with their feed markers.
        // With no subscription of that id, nothing would ever refresh, withdraw
        // or release them.
        NodeStore(File(folder.root, "nodes/nodes.txt")).saveEntries(
            listOf(
                NodeStore.Entry(frankfurt, frankfurt.toUrl(), NodeStore.Origin.Subscription),
                NodeStore.Entry(singapore, singapore.toUrl(), NodeStore.Origin.RemovedFromFeed),
            ),
        )
        val repository = repository()
        assertEquals(
            listOf(NodeStore.Origin.Manual, NodeStore.Origin.RemovedFromFeed),
            repository.nodes.value.map { it.origin },
        )
    }

    @Test
    fun `a subscription's nodes are not orphans while it exists`() {
        NodeStore(File(folder.root, "nodes/nodes.txt")).saveEntries(
            listOf(NodeStore.Entry(frankfurt, frankfurt.toUrl(), NodeStore.Origin.Subscription)),
        )
        // A pre-upgrade subscription file: no id, so the empty one.
        File(folder.root, "sub/sub.txt").apply {
            parentFile?.mkdirs()
            writeText("url=https://dash.example/sub?token=x\n")
        }
        assertEquals(
            NodeStore.Origin.Subscription,
            repository()
                .nodes.value
                .single()
                .origin,
        )
    }

    @Test
    fun `automatic follows health`() {
        val repository = repository()
        repository.add(frankfurt)
        repository.add(singapore)
        health.record(frankfurt.toUrl(), Attempt.Unreachable)
        assertEquals(singapore, repository.target()?.url)
    }

    @Test
    fun `a chosen node that is not listed reads as automatic`() {
        val repository = repository()
        repository.add(frankfurt)
        repository.choose(NodeChoice.Chosen(singapore.toUrl()))
        assertEquals(NodeChoice.Automatic, repository.effectiveChoice())
        assertEquals(frankfurt, repository.target()?.url)
    }

    @Test
    fun `no nodes, no target`() {
        assertNull(repository().target())
    }
}
