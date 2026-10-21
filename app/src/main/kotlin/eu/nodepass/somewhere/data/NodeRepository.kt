// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.nodes.NodeChoice
import eu.nodepass.somewhere.nodes.NodeChoice.Companion.toStored
import eu.nodepass.somewhere.nodes.NodeHealth
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.quic.QuicProbe
import eu.nodepass.somewhere.subscription.Subscription
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import eu.nodepass.somewhere.subscription.SubscriptionReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a probe found out about one node.
 *
 * **[Reachable] is deliberately narrow.** It means a TLS connection completed
 * and the Portal agreed to speak this node's ALPN. It does **not** mean the
 * shared key is right: authentication is carried in the frame that opens a
 * flow, and a Portal that rejects it answers with silence rather than a close
 * (learned against a live Portal, recorded in `internal/NOTES.md`). So a green
 * node here is a node whose address, port, TLS and ALPN are correct — which is
 * most of what goes wrong — and the key is proven the first time traffic moves.
 */
sealed interface ProbeResult {
    data class Reachable(
        val handshakeMillis: Int,
    ) : ProbeResult

    data class Unreachable(
        val reason: DecodeReason,
    ) : ProbeResult

    data object Probing : ProbeResult
}

/**
 * The node list as observable state, over [NodeStore].
 *
 * The store is the truth on disk; this is the truth in memory, and every
 * mutation goes through the store and re-reads it rather than updating a cached
 * copy in parallel. That costs a file read per edit — on a list of a few dozen
 * nodes, nothing — and buys the property that the screen can never show a node
 * the store would not return on next launch.
 */
class NodeRepository(
    private val store: NodeStore,
    private val subscriptions: SubscriptionStore,
    private val fetcher: SubscriptionFetcher,
    private val dialer: NowhereDialer = NowhereDialer(),
    /**
     * How a QUIC node is probed. Null when it answered; otherwise why not.
     *
     * Injected because the real one needs the native QUIC library, which a JVM
     * test does not load.
     */
    private val quicProbe: (NowhereUrl) -> DecodeReason? = QuicProbe::probe,
    private val io: CoroutineDispatcher,
    /**
     * Where work that outlives a screen runs.
     *
     * A subscription fetch must not be launched from `rememberCoroutineScope()`:
     * that scope belongs to the composition, and the import screen closes the
     * instant the user taps Subscribe. The first version did exactly that, and
     * the fetch was cancelled before it reached the network — the screen closed,
     * the node list stayed empty, and nothing anywhere reported a failure,
     * because nothing had failed. It had been cancelled.
     */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Where the user's [NodeChoice] is kept.
     *
     * A file rather than memory because a quick-settings tile reads it, from a
     * process that may have been started for the tile alone. It holds a node
     * URL, shared key included — the same material `nodes.txt` beside it
     * already holds, with the same permissions, so not a second exposure.
     */
    private val choiceFile: File? = null,
    /** How nodes have behaved, which is what an automatic choice is made from. */
    private val health: NodeHealth = NodeHealth(),
) {
    private val _nodes = MutableStateFlow(store.load())
    val nodes: StateFlow<List<NodeStore.Entry>> = _nodes.asStateFlow()

    private val _choice = MutableStateFlow(readChoice())

    /**
     * What the user asked for, exactly as stored.
     *
     * Not corrected for a node that has since gone — [effectiveChoice] is that.
     * Kept raw so that a node which comes back on the next subscription refresh
     * is chosen again, rather than having been quietly forgotten while it was
     * away.
     */
    val choice: StateFlow<NodeChoice> = _choice.asStateFlow()

    private val _subscription = MutableStateFlow(subscriptions.load())
    val subscription: StateFlow<SubscriptionStore.Record?> = _subscription.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastRefreshFailure = MutableStateFlow<DecodeReason?>(null)

    /** The last refresh's reason for failing, or null if the last one worked. */
    val lastRefreshFailure: StateFlow<DecodeReason?> = _lastRefreshFailure.asStateFlow()

    private val _probes = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())

    /** Probe results, keyed by the node's rendered URL. */
    val probes: StateFlow<Map<String, ProbeResult>> = _probes.asStateFlow()

    /** The choice as a screen should show it: a chosen node that is gone reads as automatic. */
    fun effectiveChoice(): NodeChoice = NodeChoice.effective(_choice.value, _nodes.value.map { it.url.toUrl() })

    /** Records what the user picked. Best effort: losing it costs a tap, not a connection. */
    fun choose(choice: NodeChoice) {
        _choice.value = choice
        val file = choiceFile ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(choice.toStored())
        } catch (_: java.io.IOException) {
            // Held in memory for this run regardless. Losing it at the next
            // launch costs a tap; refusing the choice would cost the connection.
        }
    }

    /**
     * The node a connection should use now, or null when there is none.
     *
     * Automatic selection never picks a node the subscription has withdrawn:
     * NW-D-04 says those are expired or out of quota, and dialling one would
     * only produce a failure that points at the network. A person who chooses
     * one explicitly still gets it — it is their call, and the card says what
     * the subscription said about it.
     */
    fun target(): NodeStore.Entry? = resolve(_choice.value)

    /**
     * What automatic selection would pick now, whatever is chosen.
     *
     * For the card that offers it: "automatic" alone is a promise with nothing
     * behind it, and the user should see where it would send traffic first.
     */
    fun automaticTarget(): NodeStore.Entry? = resolve(NodeChoice.Automatic)

    private fun resolve(choice: NodeChoice): NodeStore.Entry? {
        val listed = _nodes.value
        val line =
            NodeChoice.resolve(
                choice = choice,
                listed = listed.map { it.url.toUrl() },
                automaticCandidates =
                    listed
                        .filter { it.origin != NodeStore.Origin.RemovedFromFeed }
                        .map { it.url.toUrl() },
                health = health,
                latencyMillis = ::latencyOf,
            ) ?: return null
        return listed.firstOrNull { it.url.toUrl() == line }
    }

    /** The last probe's handshake time for [node], if it answered. */
    fun latencyOf(node: String): Int? = (_probes.value[node] as? ProbeResult.Reachable)?.handshakeMillis

    private fun readChoice(): NodeChoice {
        val file = choiceFile ?: return NodeChoice.Automatic
        return try {
            NodeChoice.fromStored(if (file.exists()) file.readText() else null)
        } catch (_: java.io.IOException) {
            NodeChoice.Automatic
        }
    }

    fun refresh() {
        _nodes.value = store.load()
        _subscription.value = subscriptions.load()
    }

    /** Fire-and-forget [setSubscription], for a caller that is about to go away. */
    fun subscribe(url: String) {
        scope.launch { setSubscription(url) }
    }

    /** Fire-and-forget [refreshSubscription]. */
    fun refreshInBackground() {
        scope.launch { refreshSubscription() }
    }

    /** Fire-and-forget [probe], for the same reason. */
    fun probeInBackground(node: NowhereUrl) {
        scope.launch { probe(node) }
    }

    /** Fire-and-forget [probeAll]. */
    fun probeAllInBackground() {
        scope.launch { probeAll() }
    }

    /**
     * Probes every node the subscription has not withdrawn, a few at a time.
     *
     * A few rather than all at once, because the figure being measured is a
     * handshake time: forty TLS handshakes started together queue behind each
     * other on the device's own link, and the list would come back ranked by
     * who started first. [PROBE_PARALLELISM] keeps the measurement about the
     * Portals.
     */
    suspend fun probeAll() {
        val targets =
            _nodes.value
                .filter { it.origin != NodeStore.Origin.RemovedFromFeed }
                .map { it.url }
        // Every one is marked first, so the whole list shows that a re-test is
        // under way rather than only the nodes whose turn has come.
        _probes.update { current -> current + targets.associate { it.toUrl() to ProbeResult.Probing } }
        val permits = Semaphore(PROBE_PARALLELISM)
        coroutineScope {
            targets.forEach { node -> launch { permits.withPermit { probe(node) } } }
        }
    }

    /**
     * Points the app at a subscription and fetches it once.
     *
     * The URL is written before the fetch is attempted, so a subscription that
     * is correct but temporarily unreachable is still the subscription when the
     * network comes back. A failed first fetch leaves the URL in place and the
     * reason in [lastRefreshFailure] rather than discarding what the user typed.
     */
    suspend fun setSubscription(url: String): DecodeResult<Subscription> {
        subscriptions.save(SubscriptionStore.Record(url, title = null, usage = null, fetchedAtEpochMillis = null))
        _subscription.value = subscriptions.load()
        return refreshSubscription()
    }

    /**
     * Re-fetches the feed and reconciles it against what is stored.
     *
     * The reconciliation, not the fetch, is where NW-D-04 lives: nodes the feed
     * has stopped listing are kept and marked rather than deleted, and manual
     * nodes are never touched by it.
     */
    suspend fun refreshSubscription(): DecodeResult<Subscription> {
        val record =
            _subscription.value
                ?: return DecodeResult.Invalid(SubscriptionReason.Transport("no subscription is configured"))

        _refreshing.value = true
        try {
            val result = withContext(io) { fetcher.fetch(record.url) }
            when (result) {
                is DecodeResult.Ok -> {
                    val subscription = result.value
                    _nodes.value = store.reconcileWithFeed(subscription.nodes)
                    subscriptions.save(
                        record.copy(
                            title = subscription.title ?: record.title,
                            usage = subscription.usage ?: record.usage,
                            fetchedAtEpochMillis = clock(),
                        ),
                    )
                    _subscription.value = subscriptions.load()
                    _lastRefreshFailure.value = null
                }

                is DecodeResult.Invalid -> {
                    // NW-D-04: an empty feed is almost never a broken
                    // subscription, it is an exhausted one — so the nodes are
                    // still reconciled against it and every one of them is
                    // marked as removed, which is what actually happened.
                    if (result.reason == SubscriptionReason.NoNodes) {
                        _nodes.value = store.reconcileWithFeed(emptyList())
                    }
                    _lastRefreshFailure.value = result.reason
                }
            }
            return result
        } finally {
            _refreshing.value = false
        }
    }

    /** Forgets the subscription and the credential in it. Nodes are left alone. */
    fun forgetSubscription() {
        subscriptions.clear()
        _subscription.value = null
        _lastRefreshFailure.value = null
    }

    fun add(node: NowhereUrl) {
        _nodes.value = store.add(node)
    }

    fun remove(node: NowhereUrl) {
        _probes.update { it - node.toUrl() }
        _nodes.value = store.remove(node)
        // A deliberate deletion, unlike a refresh that dropped the node: the
        // choice goes with it, so it cannot come back with a later import.
        if (_choice.value == NodeChoice.Chosen(node.toUrl())) choose(NodeChoice.Automatic)
    }

    fun replace(
        old: NowhereUrl,
        new: NowhereUrl,
    ) {
        _probes.update { it - old.toUrl() }
        _nodes.value = store.replace(old, new)
        // Editing a node changes its URL, which is its identity. Without this
        // the edited node would stop being the chosen one the moment it was
        // saved, and the next connection would go somewhere automatic.
        if (_choice.value == NodeChoice.Chosen(old.toUrl())) choose(NodeChoice.Chosen(new.toUrl()))
    }

    /**
     * Dials [node], measures the handshake, and closes.
     *
     * The measurement is wall time around connect plus handshake, which is what
     * the user is waiting through — not a round-trip time. It is reported in
     * milliseconds because that is the resolution at which the number means
     * anything; a Portal 200 ms away and one 205 ms away are the same Portal.
     */
    suspend fun probe(node: NowhereUrl): ProbeResult {
        val key = node.toUrl()
        _probes.update { it + (key to ProbeResult.Probing) }

        val result =
            withContext(io) {
                val started = clock()
                if (node.usesQuic) {
                    // Over the carrier the node actually uses. See QuicProbe.
                    val refused = quicProbe(node)
                    return@withContext if (refused == null) {
                        ProbeResult.Reachable((clock() - started).toInt())
                    } else {
                        ProbeResult.Unreachable(refused)
                    }
                }
                when (val dialed = dialer.connect(node)) {
                    is DecodeResult.Ok -> {
                        val elapsed = (clock() - started).toInt()
                        // Closed immediately: a probe that left a connection
                        // open would count against the Portal's flow limit for
                        // as long as the app stayed on the screen.
                        runCatching { dialed.value.close() }
                        ProbeResult.Reachable(elapsed)
                    }

                    is DecodeResult.Invalid -> ProbeResult.Unreachable(dialed.reason)
                }
            }

        _probes.update { it + (key to result) }
        return result
    }

    companion object {
        /** How many probes run at once. See [probeAll]. */
        const val PROBE_PARALLELISM: Int = 4
    }
}
