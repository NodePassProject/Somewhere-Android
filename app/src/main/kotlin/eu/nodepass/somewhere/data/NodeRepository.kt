// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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
    private val dialer: NowhereDialer = NowhereDialer(),
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _nodes = MutableStateFlow(store.load())
    val nodes: StateFlow<List<NodeStore.Entry>> = _nodes.asStateFlow()

    private val _probes = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())

    /** Probe results, keyed by the node's rendered URL. */
    val probes: StateFlow<Map<String, ProbeResult>> = _probes.asStateFlow()

    fun refresh() {
        _nodes.value = store.load()
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
