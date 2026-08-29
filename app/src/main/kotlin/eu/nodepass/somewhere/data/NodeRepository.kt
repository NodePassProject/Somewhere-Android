// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.Subscription
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import eu.nodepass.somewhere.subscription.SubscriptionReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
     * Where the last node a tunnel came up on is remembered.
     *
     * A file rather than memory because the reader is a quick-settings tile,
     * which runs in a process that may have been started for the tile alone and
     * has nothing else in it.
     *
     * It holds a node URL, which carries a shared key — the same material
     * `nodes.txt` beside it already holds, in the same directory, with the same
     * permissions. It is not a second exposure, and it is deliberately not a
     * second *copy*: what comes back is matched against the live list, so a
     * node the user deleted is gone from here the moment it is gone from there.
     */
    private val lastConnectedFile: File? = null,
) {
    private val _nodes = MutableStateFlow(store.load())
    val nodes: StateFlow<List<NodeStore.Entry>> = _nodes.asStateFlow()

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

    /**
     * The node a tunnel last came up on, if it is still in the list.
     *
     * Null when there is none, when the file cannot be read, or when the node
     * has since been deleted — and the last of the three is why this checks
     * rather than simply returning what it stored. A tile that connected to a
     * node the user removed would be the app disagreeing with its own list.
     */
    fun lastConnected(): String? {
        val file = lastConnectedFile ?: return null
        val remembered =
            try {
                if (!file.exists()) return null
                file.readText().trim()
            } catch (_: java.io.IOException) {
                return null
            }
        if (remembered.isEmpty()) return null
        return _nodes.value.firstOrNull { it.line == remembered }?.line
    }

    /** Remembers [line] as the node a tunnel came up on. Best effort. */
    fun recordConnected(line: String) {
        val file = lastConnectedFile ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(line)
        } catch (_: java.io.IOException) {
            // A tile that offers the app instead of a node is a smaller failure
            // than a tunnel that refuses to come up over a preference.
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
    }

    fun replace(
        old: NowhereUrl,
        new: NowhereUrl,
    ) {
        _probes.update { it - old.toUrl() }
        _nodes.value = store.replace(old, new)
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
}
