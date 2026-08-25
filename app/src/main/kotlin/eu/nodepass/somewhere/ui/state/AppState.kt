// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import androidx.compose.runtime.Immutable
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionUsage

/*
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

/**
 * What is currently known about one node.
 *
 * Two different kinds of "not working" are kept apart on purpose, because the
 * user's next move differs:
 *
 * - [Unreachable] — this device could not open a connection. The node may be
 *   fine and the network may not be.
 * - [RemovedByProvider] — the node is gone from the subscription feed. NW-D-04:
 *   the dashboard removes nodes when a subscription lapses, so the honest
 *   message is expiry or quota, never "network error" and never an empty list.
 *
 * Collapsing them into one "offline" would tell someone whose subscription
 * expired to check their wifi.
 */
sealed interface NodeStatus {
    /** Never probed. Not the same as failing a probe. */
    data object Unknown : NodeStatus

    data object Probing : NodeStatus

    /**
     * A TLS connection completed and the Portal accepted this node's ALPN.
     *
     * Narrower than "works": the shared key is carried in the frame that opens
     * a flow, so it is not exercised here. A node green in this list can still
     * fail to authenticate — see `ProbeResult.Reachable`.
     */
    data class Reachable(
        val handshakeMillis: Int,
    ) : NodeStatus

    /**
     * Carries the reason, not a rendered sentence.
     *
     * Rendering here would freeze the message in whatever locale was current
     * when the probe ran, which is the wrong one after a language change and
     * the wrong one for a reason that has an argument in it.
     */
    data class Unreachable(
        val reason: DecodeReason,
    ) : NodeStatus

    data object RemovedByProvider : NodeStatus
}

@Immutable
data class NodeEntry(
    val url: NowhereUrl,
    val status: NodeStatus = NodeStatus.Unknown,
) {
    val displayName: String get() = url.displayName ?: "${url.host}:${url.port}"

    val latencyMillis: Int? get() = (status as? NodeStatus.Reachable)?.handshakeMillis
}

@Immutable
data class SessionSnapshot(
    /**
     * The single source of truth for whether the tunnel is up.
     *
     * Single deliberately. The home screen once read this for its header and
     * status dot while its button read the tunnel state directly, and the two
     * disagreed on screen: "Not connected" above a button offering to
     * disconnect. One fact needs one field, or a screen can contradict itself
     * without any code being wrong on its own.
     */
    val connected: Boolean,
    /** Between the tap and the TUN. Not [connected], and not disconnected either. */
    val connecting: Boolean,
    /**
     * Whether the figures below were measured.
     *
     * Separate from [connected] because the two really are different, and
     * conflating them produced a screen that contradicted itself: the button
     * read "Disconnect" while the header read "Not connected" and every figure
     * read zero. A tunnel can be up with nothing counting its bytes yet, and
     * "0 B/s" in that state claims a measurement of zero rather than admitting
     * there is none — which is what `docs/design-system.md` rule 4 forbids.
     * When false, the screen shows an em dash in place of every figure.
     */
    val measured: Boolean,
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
                connecting = false,
                measured = false,
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
