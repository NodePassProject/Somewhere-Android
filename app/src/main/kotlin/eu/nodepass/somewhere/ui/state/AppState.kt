// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import androidx.compose.runtime.Immutable
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionUsage

/**
 * What the screens render.
 *
 * The types the protocol layer already defines are used directly — [NowhereUrl],
 * [SubscriptionUsage], [SetupResult] — rather than re-declared as view models.
 * A parallel set of UI-shaped copies is where "the screen says `tcp` but the
 * session negotiated `udp`" comes from: two representations of one fact drift,
 * and the drift is invisible until someone reads both.
 *
 * Nothing here produces state. The service that will is L1 work and needs a TUN
 * device; until then [SampleState] supplies a snapshot so the layout can be
 * checked against the design at the size it actually ships at.
 */
enum class NodeHealth {
    /** Reachable, and measured. */
    Active,

    /** Known, not currently measured. */
    Idle,

    /**
     * Gone from the subscription feed.
     *
     * NW-D-04: the dashboard removes nodes when a subscription lapses, so the
     * truthful message is expiry or quota — never "network error", and never an
     * empty list with no explanation.
     */
    Unavailable,
}

@Immutable
data class NodeEntry(
    val url: NowhereUrl,
    val health: NodeHealth,
    val latencyMillis: Int?,
) {
    val displayName: String get() = url.displayName ?: "${url.host}:${url.port}"
}

/**
 * The live session.
 *
 * The two directions are separate fields with no combined total, because the
 * protocol gives them no common denominator: `up` and `down` can be on different
 * carriers, and a single "throughput" number would be an average of two things
 * that are not the same thing.
 */
@Immutable
data class SessionSnapshot(
    val connected: Boolean,
    val upstreamBytesPerSecond: Long,
    val downstreamBytesPerSecond: Long,
    /** Each direction against its own recent peak. Never against the other's. */
    val upstreamOfPeak: Float,
    val downstreamOfPeak: Float,
    val activeFlows: Int,
    val sessionBytes: Long,
    val handshakeMillis: Int,
    val connectedSeconds: Long,
) {
    companion object {
        val DISCONNECTED =
            SessionSnapshot(
                connected = false,
                upstreamBytesPerSecond = 0,
                downstreamBytesPerSecond = 0,
                upstreamOfPeak = 0f,
                downstreamOfPeak = 0f,
                activeFlows = 0,
                sessionBytes = 0,
                handshakeMillis = 0,
                connectedSeconds = 0,
            )
    }
}

/**
 * One line of the connection log.
 *
 * [result] is the protocol's own enum, so a screen cannot invent an eighth
 * outcome or collapse two into one.
 */
@Immutable
data class ConnectionLogEntry(
    val result: SetupResult,
    val timestamp: String,
    /** The target, for the rejections that name one. Never a full URL. */
    val target: String? = null,
    val flowId: Int? = null,
    val carrier: String? = null,
)

@Immutable
data class SubscriptionState(
    val title: String,
    val usage: SubscriptionUsage,
    val refreshedMinutesAgo: Int,
)

@Immutable
data class RuleSet(
    val action: RuleAction,
    val name: String,
    val entryCount: Int?,
)

enum class RuleAction {
    Direct,
    Tunnel,
    GeoIp,
}

enum class RoutingMode {
    Rules,
    Everything,
}

@Immutable
data class AppEntry(
    val label: String,
    val packageName: String,
    val excluded: Boolean,
)
