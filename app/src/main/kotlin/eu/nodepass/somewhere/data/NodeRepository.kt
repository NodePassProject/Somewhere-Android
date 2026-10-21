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
    // Feed nodes whose subscription no longer exists are the user's. The
    // single-subscription "forget" kept its nodes with their feed markers, and
    // after the upgrade those point at a subscription id nobody has: never
    // refreshed, never withdrawn, never released. Adopted once, at load.
    private val _nodes = MutableStateFlow(store.adoptOrphans(subscriptions.loadAll().map { it.id }.toSet()))
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

    private val _subscriptionList = MutableStateFlow(subscriptions.loadAll())

    /** Every subscription, in the order they were added. */
    val subscriptionList: StateFlow<List<SubscriptionStore.Record>> = _subscriptionList.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the subscriptions being fetched right now. */
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private val _refreshFailures = MutableStateFlow<Map<String, DecodeReason>>(emptyMap())

    /** Each subscription's last reason for failing, absent if its last fetch worked. */
    val refreshFailures: StateFlow<Map<String, DecodeReason>> = _refreshFailures.asStateFlow()

    /**
     * Serialises every change to the node file.
     *
     * Two subscriptions refreshing at once each read the list, reconcile their
     * own feed into it, and write it back; without this, the second write
     * would drop whatever the first had added. Held only around the file work,
     * never across a fetch.
     */
    private val storeLock = Any()

    private val _probes = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())

    /** Probe results, keyed by the node's rendered URL. */
    val probes: StateFlow<Map<String, ProbeResult>> = _probes.asStateFlow()

    /** The nodes that may be used: listed, and not withdrawn by their subscription. */
    private fun selectable(): List<String> =
        _nodes.value
            .filter { it.origin != NodeStore.Origin.RemovedFromFeed }
            .map { it.url.toUrl() }

    /** The choice as a screen should show it: a chosen node that cannot be used reads as automatic. */
    fun effectiveChoice(): NodeChoice = NodeChoice.effective(_choice.value, selectable())

    /** Ticks when node health changes; see [NodeHealth.changes]. */
    val healthChanges: StateFlow<Long> get() = health.changes

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
     * The node a connection should use now, or null only when there are none.
     *
     * Never a node the subscription has withdrawn while another exists:
     * NW-D-04 says those are expired or out of quota, and dialling one only
     * produces a failure that points at the network.
     *
     * When nothing is usable — every node refused or withdrawn — this still
     * returns one rather than null. Null made Home say "no node yet" to a user
     * with a full list, and Connect report "no node was given"; connecting to
     * the first node instead lets the tunnel report what is actually wrong,
     * which is the refusal or the expiry.
     */
    fun target(): NodeStore.Entry? =
        resolve(_choice.value)
            ?: _nodes.value.firstOrNull { it.origin != NodeStore.Origin.RemovedFromFeed }
            ?: _nodes.value.firstOrNull()

    /**
     * What automatic selection would pick now, whatever is chosen.
     *
     * For the card that offers it: "automatic" alone is a promise with nothing
     * behind it, and the user should see where it would send traffic first.
     */
    fun automaticTarget(): NodeStore.Entry? = resolve(NodeChoice.Automatic)

    private fun resolve(choice: NodeChoice): NodeStore.Entry? {
        val line = NodeChoice.resolve(choice, selectable(), health, ::latencyOf) ?: return null
        return _nodes.value.firstOrNull { it.url.toUrl() == line }
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
        _subscriptionList.value = subscriptions.loadAll()
    }

    /** Runs [block] holding the lock every write to the node files takes. */
    fun <T> exclusive(block: () -> T): T = synchronized(storeLock) { block() }

    /** Everything re-read from disk, after a restore replaced it underneath. */
    fun reload() {
        synchronized(storeLock) {
            _nodes.value = store.load()
            _subscriptionList.value = subscriptions.loadAll()
            _choice.value = readChoice()
        }
        _probes.value = emptyMap()
        _refreshFailures.value = emptyMap()
    }

    /** Fire-and-forget [addSubscription], for a caller that is about to go away. */
    fun subscribe(url: String) {
        scope.launch { addSubscription(url) }
    }

    /** Fire-and-forget [refreshAll]. */
    fun refreshInBackground() {
        scope.launch { refreshAll() }
    }

    /** Fire-and-forget [refreshSubscription] for one subscription. */
    fun refreshInBackground(id: String) {
        scope.launch { refreshSubscription(id) }
    }

    /** Fire-and-forget [probe], for the same reason. */
    fun probeInBackground(node: NowhereUrl) {
        scope.launch { probe(node) }
    }

    /** Fire-and-forget [probeAll]. */
    fun probeAllInBackground() {
        scope.launch { probeAll() }
    }

    /** Probes the nodes nothing has measured yet, through the same throttle. */
    fun probeUnprobedInBackground() {
        scope.launch { probeAll(onlyUnprobed = true) }
    }

    /**
     * One throttle for every probe this repository starts.
     *
     * Shared rather than per call: a second "Test all" tapped while the first
     * is running, or the list's first-open probe overlapping either, would
     * otherwise each bring their own four, and the measurement would be of the
     * queue again.
     */
    private val probePermits = Semaphore(PROBE_PARALLELISM)

    /**
     * Probes every node the subscription has not withdrawn, a few at a time.
     *
     * A few rather than all at once, because the figure being measured is a
     * handshake time: forty TLS handshakes started together queue behind each
     * other on the device's own link, and the list would come back ranked by
     * who started first. [PROBE_PARALLELISM] keeps the measurement about the
     * Portals.
     */
    suspend fun probeAll(onlyUnprobed: Boolean = false) {
        val targets =
            synchronized(probeClaims) {
                _nodes.value
                    .filter { it.origin != NodeStore.Origin.RemovedFromFeed }
                    .map { it.url }
                    .filter { !onlyUnprobed || _probes.value[it.toUrl()] == null }
                    // A node already queued or being dialled is not dialled
                    // twice; the run that claimed it will report it.
                    .filter { probeClaims.add(it.toUrl()) }
            }
        // Every one is marked first, so the whole list shows that a re-test is
        // under way rather than only the nodes whose turn has come.
        _probes.update { current -> current + targets.associate { it.toUrl() to ProbeResult.Probing } }
        coroutineScope {
            targets.forEach { node ->
                launch {
                    try {
                        probePermits.withPermit { probe(node) }
                    } finally {
                        synchronized(probeClaims) { probeClaims.remove(node.toUrl()) }
                    }
                }
            }
        }
    }

    /** Nodes some [probeAll] has queued and not yet finished. */
    private val probeClaims = HashSet<String>()

    /**
     * Adds a subscription and fetches it once.
     *
     * The URL is written before the fetch is attempted, so a subscription that
     * is correct but temporarily unreachable is still there when the network
     * comes back. A failed first fetch leaves the URL in place and the reason
     * in [refreshFailures] rather than discarding what the user typed. A URL
     * that is already a subscription is refreshed, not added twice.
     */
    suspend fun addSubscription(url: String): DecodeResult<Subscription> {
        val record =
            synchronized(storeLock) {
                subscriptions.add(url).also { _subscriptionList.value = subscriptions.loadAll() }
            }
        return refreshSubscription(record.id)
    }

    /** Refreshes every subscription, one after another. */
    suspend fun refreshAll() {
        _subscriptionList.value.map { it.id }.forEach { refreshSubscription(it) }
    }

    /**
     * Re-fetches one feed and reconciles it against what is stored.
     *
     * The reconciliation, not the fetch, is where NW-D-04 lives: nodes the feed
     * has stopped listing are kept and marked rather than deleted, manual nodes
     * are never touched by it, and neither are another subscription's.
     */
    suspend fun refreshSubscription(id: String): DecodeResult<Subscription> {
        val record =
            _subscriptionList.value.firstOrNull { it.id == id }
                ?: return DecodeResult.Invalid(SubscriptionReason.Transport("no subscription is configured"))

        _refreshing.update { it + id }
        try {
            val result = withContext(io) { fetcher.fetch(record.url) }
            when (result) {
                is DecodeResult.Ok -> {
                    val subscription = result.value
                    synchronized(storeLock) {
                        // Re-read rather than using the copy taken before the
                        // fetch: the subscription may have been removed while
                        // it was in flight. Then the feed is dropped unread —
                        // reconciling it first would add nodes the user never
                        // saw, which removing the subscription then made theirs.
                        val current = subscriptions.loadAll().firstOrNull { it.id == id }
                        if (current != null) {
                            _nodes.value = store.reconcileWithFeed(subscription.nodes, id)
                            subscriptions.save(
                                current.copy(
                                    title = subscription.title ?: current.title,
                                    usage = subscription.usage ?: current.usage,
                                    fetchedAtEpochMillis = clock(),
                                ),
                            )
                        }
                        _subscriptionList.value = subscriptions.loadAll()
                    }
                    _refreshFailures.update { it - id }
                }

                is DecodeResult.Invalid -> {
                    // NW-D-04: an empty feed is almost never a broken
                    // subscription, it is an exhausted one — so the nodes are
                    // still reconciled against it and every one of them is
                    // marked as removed, which is what actually happened.
                    if (result.reason == SubscriptionReason.NoNodes) {
                        synchronized(storeLock) {
                            if (subscriptions.loadAll().any { it.id == id }) {
                                _nodes.value = store.reconcileWithFeed(emptyList(), id)
                            }
                        }
                    }
                    if (_subscriptionList.value.any { it.id == id }) _refreshFailures.update { it + (id to result.reason) }
                }
            }
            return result
        } finally {
            _refreshing.update { it - id }
        }
    }

    /**
     * Forgets a subscription and the credential in it.
     *
     * The nodes it listed stay, as the user's own; the ones it had already
     * withdrawn go. See [NodeStore.releaseSource].
     */
    fun removeSubscription(id: String) {
        synchronized(storeLock) {
            subscriptions.remove(id)
            _subscriptionList.value = subscriptions.loadAll()
            _nodes.value = store.releaseSource(id)
        }
        _refreshFailures.update { it - id }
    }

    fun add(node: NowhereUrl) {
        synchronized(storeLock) { _nodes.value = store.add(node) }
    }

    /** Adds every node in [nodes] that is not already stored, in one write. */
    fun addAll(nodes: List<NowhereUrl>) {
        synchronized(storeLock) { _nodes.value = store.addAll(nodes) }
    }

    fun remove(node: NowhereUrl) {
        _probes.update { it - node.toUrl() }
        synchronized(storeLock) { _nodes.value = store.remove(node) }
        // A deliberate deletion, unlike a refresh that dropped the node: the
        // choice goes with it, so it cannot come back with a later import.
        if (_choice.value == NodeChoice.Chosen(node.toUrl())) choose(NodeChoice.Automatic)
    }

    fun replace(
        old: NowhereUrl,
        new: NowhereUrl,
    ) {
        _probes.update { it - old.toUrl() }
        synchronized(storeLock) { _nodes.value = store.replace(old, new) }
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
