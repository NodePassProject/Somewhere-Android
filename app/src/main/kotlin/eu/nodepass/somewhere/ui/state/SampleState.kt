// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.subscription.SubscriptionUsage

/**
 * The snapshot the design previews render.
 *
 * **The screens no longer default to this.** They read the real node list, and
 * a fresh install shows empty states because a fresh install has no nodes —
 * which is the honest thing for them to show. This object survives as the
 * fixture behind `@Preview`, so the populated design stays inspectable without
 * a device and without inventing state at runtime.
 *
 * Nothing here reaches the network.
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
     * `udp` direction needed QUIC, which had not shipped, so a connected node in
     * that state was a screen the app could not actually reach. Rendering it
     * would have made the home screen a drawing rather than a reading.
     *
     * **QUIC has shipped since**, so a split node is now reachable and this
     * could honestly be drawn as the canvas drew it. It is left as `tcp/tcp`
     * for a different reason: split flows themselves — OPEN and ATTACH on two
     * carriers — are not implemented, so `up=tcp&down=udp` is still a state the
     * app cannot reach. Revisit when they land.
     */
    val frankfurt: NodeEntry =
        NodeEntry(
            url = node("nowhere://$FAKE_KEY@fra04.example.net:443?up=tcp&down=tcp&mux=1#Frankfurt%20%C2%B7%20Portal%2004"),
            status = NodeStatus.Reachable(handshakeMillis = 38),
        )

    /**
     * A pasted default: upstream defaults both directions to `udp`, so this is
     * what an unadorned `nowhere://` URL is.
     *
     * **It used to be the node that could not be carried.** Since L3 it can, so
     * it is an ordinary node here — and what NW-P-25's two buttons now apply to
     * is [taipei], which asks for something the QUIC carrier does not do.
     */
    val singapore: NodeEntry =
        NodeEntry(
            url = node("nowhere://$FAKE_KEY@sgp11.example.net:443?up=udp&down=udp#Singapore%20%C2%B7%20Portal%2011"),
            status = NodeStatus.Reachable(handshakeMillis = 61),
        )

    /**
     * A QUIC node that also asks for certificate pinning, which the QUIC
     * carrier does not implement.
     *
     * NW-P-25 is about this shape: the client says what it cannot do and offers
     * two ways forward, rather than rewriting the user's pasted configuration
     * on their behalf. Carrying it without the pin would be a security
     * downgrade the user configured against and could not observe.
     */
    val taipei: NodeEntry =
        NodeEntry(
            url =
                node(
                    "nowhere://$FAKE_KEY@tpe07.example.net:443?up=udp&down=udp" +
                        "&pin=6a5c1f0b9e2d4a8c3b7f1e0d9c8b7a6958473625140f3e2d1c0b9a8877665544" +
                        "#Taipei%20%C2%B7%20Portal%2007",
                ),
            status = NodeStatus.Unknown,
        )

    /** Dropped from the feed. NW-D-04: name the reason, never an empty list. */
    val tokyo: NodeEntry =
        NodeEntry(
            url = node("nowhere://$FAKE_KEY@tyo02.example.net:443?up=tcp&down=tcp#Tokyo%20%C2%B7%20Portal%2002"),
            status = NodeStatus.RemovedByProvider,
        )

    val nodes: List<NodeEntry> = listOf(frankfurt, singapore, tokyo)

    val session: SessionSnapshot =
        SessionSnapshot(
            connected = true,
            connecting = false,
            measured = true,
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
}
