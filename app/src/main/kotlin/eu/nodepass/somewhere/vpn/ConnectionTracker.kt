// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Which way a connection left the device. */
enum class ConnectionRoute {
    /** Through the Portal. */
    Portal,

    /** Straight out, by a routing rule. */
    Direct,
}

/** TCP or UDP, as the device opened it. */
enum class ConnectionProtocol {
    Tcp,
    Udp,
}

/**
 * One connection the tunnel is carrying now.
 *
 * Bytes are counted per direction and never summed, for the same reason the
 * home screen never sums them: they are two measurements.
 */
data class LiveConnection(
    val id: Long,
    val protocol: ConnectionProtocol,
    /** `host:port`, already through [TargetText]. */
    val target: String,
    val route: ConnectionRoute,
    /** The app that opened it, by label, or null when the platform would not say. */
    val app: String?,
    val upstreamBytes: Long,
    val downstreamBytes: Long,
    /** When it opened, on the monotonic clock. */
    val openedAtElapsedMillis: Long,
)

/**
 * The connections a tunnel is carrying, for the screen that lists them.
 *
 * ## Counting stays where the bytes move
 *
 * The pumps already count every byte for the home screen's meter. Each tracked
 * connection gets its own pair of counters beside that, incremented at the same
 * two places; a snapshot reads them. Nothing here is on a hot path that was not
 * already counting.
 *
 * ## Closing
 *
 * The tracker does not know how to close anything — the flow handler and the
 * UDP relay own their connections. Each registers a closer as it opens one,
 * and [close] calls it. A close request for a connection that has already gone
 * finds nothing and does nothing.
 */
class ConnectionTracker(
    private val clock: () -> Long,
) {
    /** A tracked connection's live counters. Held by whoever carries it. */
    class Handle internal constructor(
        val id: Long,
        internal val protocol: ConnectionProtocol,
        internal val target: String,
        internal val route: ConnectionRoute,
        internal val openedAt: Long,
        internal val closer: () -> Unit,
    ) {
        internal val upstream = AtomicLong(0)
        internal val downstream = AtomicLong(0)

        /** Filled in once the owner is known, which may be after the connection opened. */
        @Volatile internal var app: String? = null

        fun upstream(bytes: Int) {
            upstream.addAndGet(bytes.toLong())
        }

        fun downstream(bytes: Int) {
            downstream.addAndGet(bytes.toLong())
        }

        fun owner(label: String?) {
            app = label
        }
    }

    private val nextId = AtomicLong(1)
    private val open = ConcurrentHashMap<Long, Handle>()

    fun open(
        protocol: ConnectionProtocol,
        target: String,
        route: ConnectionRoute,
        closer: () -> Unit,
    ): Handle {
        val handle = Handle(nextId.getAndIncrement(), protocol, target, route, clock(), closer)
        open[handle.id] = handle
        return handle
    }

    /** Forgets [handle]. Called by whoever carried it, however it ended. */
    fun finished(handle: Handle) {
        open.remove(handle.id)
    }

    /** Asks the carrier of connection [id] to end it. */
    fun close(id: Long) {
        open[id]?.closer?.invoke()
    }

    fun closeAll() {
        open.values.toList().forEach { it.closer() }
    }

    /** Every connection now, newest first. */
    fun snapshot(): List<LiveConnection> =
        open.values
            .map {
                LiveConnection(
                    id = it.id,
                    protocol = it.protocol,
                    target = it.target,
                    route = it.route,
                    app = it.app,
                    upstreamBytes = it.upstream.get(),
                    downstreamBytes = it.downstream.get(),
                    openedAtElapsedMillis = it.openedAt,
                )
            }.sortedByDescending { it.id }

    /** Drops everything, without asking anyone to close. For a tunnel that is gone. */
    fun clear() {
        open.clear()
    }
}
