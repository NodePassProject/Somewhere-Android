// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionUsage

/**
 * The snapshot the screens render until a session can supply a real one.
 *
 * This is **placeholder state, not a demo mode**: nothing here reaches the
 * network, and the whole object disappears the moment the VPN service exists.
 * It is in the source rather than in a preview annotation so the screens can be
 * checked on a device at the size they ship at, which is where spacing and
 * translated line-wrapping actually fail.
 *
 * Two rules it keeps, because a placeholder that breaks them teaches the wrong
 * shape:
 *
 * - **The nodes are real [NowhereUrl] values, parsed by the real parser.** A
 *   hand-built struct would let a screen display a combination the parser would
 *   reject.
 * - **Nothing here is a reachable address or a usable key.** This file is in a
 *   public repository; `example.net` and an obviously-fake key are the point.
 */
object SampleState {
    private fun node(url: String): NowhereUrl =
        when (val parsed = NowhereUrl.parse(url)) {
            is DecodeResult.Ok -> parsed.value
            is DecodeResult.Invalid ->
                error("the sample node does not parse: ${parsed.reason.detail}")
        }

    private const val FAKE_KEY = "not-a-real-key"

    /**
     * The connected node is `tcp/tcp`.
     *
     * The design canvas draws this screen with a split configuration —
     * `up=tcp&down=udp` — because that is the protocol's distinguishing property
     * and the reason the two directions are coloured separately at all. But a
     * `udp` direction needs QUIC, which has not shipped, so a connected node in
     * that state is a screen the app cannot actually reach. Rendering it would
     * make the home screen a drawing rather than a reading. The split case is
     * still shown where it is honest: on the node list, as the node that says it
     * needs QUIC.
     */
    val frankfurt: NodeEntry =
        NodeEntry(
            url = node("nowhere://$FAKE_KEY@fra04.example.net:443?up=tcp&down=tcp&mux=1#Frankfurt%20%C2%B7%20Portal%2004"),
            health = NodeHealth.Active,
            latencyMillis = 38,
        )

    /** Upstream defaults both directions to `udp`, so this is what a pasted
     *  default looks like: it needs QUIC, and NW-P-25 says to say so. */
    val singapore: NodeEntry =
        NodeEntry(
            url = node("nowhere://$FAKE_KEY@sgp11.example.net:443?up=udp&down=udp#Singapore%20%C2%B7%20Portal%2011"),
            health = NodeHealth.Idle,
            latencyMillis = null,
        )

    /** Dropped from the feed. NW-D-04: name the reason, never an empty list. */
    val tokyo: NodeEntry =
        NodeEntry(
            url = node("nowhere://$FAKE_KEY@tyo02.example.net:443?up=tcp&down=tcp#Tokyo%20%C2%B7%20Portal%2002"),
            health = NodeHealth.Unavailable,
            latencyMillis = null,
        )

    val nodes: List<NodeEntry> = listOf(frankfurt, singapore, tokyo)

    val session: SessionSnapshot =
        SessionSnapshot(
            connected = true,
            upstreamBytesPerSecond = 1_929_379,
            downstreamBytesPerSecond = 13_212_057,
            upstreamOfPeak = 0.62f,
            downstreamOfPeak = 0.88f,
            activeFlows = 14,
            sessionBytes = 4_509_715_660,
            handshakeMillis = 38,
            connectedSeconds = 8077,
        )

    val subscription: SubscriptionState =
        SubscriptionState(
            title = "Aurora Networks",
            usage =
                SubscriptionUsage(
                    // NW-D-02: there is no upload field at all. Upstream does not
                    // meter it, and a field that is always zero invites a screen
                    // to render "0 B uploaded" as if it were a measurement.
                    downloadBytes = 88_465_162_240,
                    totalBytes = 214_748_364_800,
                    expiresAtEpochSeconds = 1_796_083_200,
                ),
            refreshedMinutesAgo = 4,
        )

    /**
     * The connection log.
     *
     * Deliberately carries rejections of three different severities: NW-P-06
     * requires the seven outcomes to stay distinguishable, and a log sample that
     * only shows successes cannot demonstrate that they do.
     */
    val connectionLog: List<ConnectionLogEntry> =
        listOf(
            ConnectionLogEntry(
                result = SetupResult.DialFailed,
                timestamp = "14:22:07.184",
                target = "api.example.com:443",
                flowId = 8421,
                carrier = "MUX",
            ),
            ConnectionLogEntry(SetupResult.FlowLimit, "14:21:58.902", flowId = 8419),
            ConnectionLogEntry(SetupResult.SessionReplaced, "14:19:12.006"),
            ConnectionLogEntry(SetupResult.Ready, "14:19:11.883", carrier = "TLS/TCP, MUX"),
        )

    val ruleSets: List<RuleSet> =
        listOf(
            RuleSet(RuleAction.Direct, "private_ranges", 18),
            RuleSet(RuleAction.Direct, "domestic_domains", 4206),
            RuleSet(RuleAction.Tunnel, "everything_else", null),
            RuleSet(RuleAction.GeoIp, "bypass_by_country", null),
        )

    val apps: List<AppEntry> =
        listOf(
            AppEntry("Banking", "com.example.banking", excluded = true),
            AppEntry("Camera", "com.example.camera", excluded = true),
            AppEntry("Maps", "com.example.maps", excluded = false),
            AppEntry("Messages", "com.example.messages", excluded = false),
        )
}
