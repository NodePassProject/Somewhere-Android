// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often an idle QUIC connection says something, so that it stays open.
 *
 * ## Why this is derived rather than fixed
 *
 * The reference client sends a keep-alive every fifteen seconds
 * (`src/vector/session/quic.rs`, `Duration::from_secs(15)`), against an idle
 * timeout that defaults to two minutes. On a server that costs nothing. On a
 * phone it is a radio wake every fifteen seconds for the life of the tunnel,
 * and the radio is the expensive part of a mobile connection by a wide margin —
 * an idle modem that is woken often never reaches its low-power state at all.
 *
 * So the interval is read off the timeout that is actually in force. The peer
 * announces `max_idle_timeout` in its transport parameters and the effective
 * timeout is the smaller of the two ends' values; a keep-alive at half of that
 * leaves a full interval of margin, which is one whole lost packet's worth.
 *
 * ## The two bounds, and why each exists
 *
 * A **floor** because a peer announcing a very short timeout would otherwise
 * produce a keep-alive storm; below the floor the connection is simply allowed
 * to close and be rebuilt, which costs one handshake rather than a permanently
 * busy radio.
 *
 * A **ceiling** because middleboxes forget UDP mappings long before QUIC
 * forgets connections. Thirty seconds is the interval below which most NAT
 * bindings survive, and a keep-alive that outlived the binding would be sent
 * into a path that no longer exists.
 *
 * Nothing here is negotiated or sent: a keep-alive interval is a local decision
 * about when to send PING, and the specification says nothing about it.
 */
internal object KeepAlive {
    /** The reference client's fixed interval, kept as the thing to beat. */
    val UPSTREAM_INTERVAL: Duration = 15.seconds

    /** Below this a connection is left to close and be rebuilt. */
    val FLOOR: Duration = 5.seconds

    /** Above this a NAT binding is more likely to have been forgotten. */
    val CEILING: Duration = 30.seconds

    /**
     * The interval for a connection whose effective idle timeout is
     * [idleTimeout], or null when no keep-alive should be sent at all.
     *
     * @param idleTimeout the smaller of the two ends' `max_idle_timeout`, which
     *   is what actually governs. Zero means neither end set one, and then
     *   nothing will close the connection for being quiet.
     */
    fun interval(idleTimeout: Duration): Duration? {
        if (idleTimeout <= Duration.ZERO) return null
        val half = idleTimeout / 2
        if (half < FLOOR) return null
        return if (half > CEILING) CEILING else half
    }

    /**
     * Whether [interval] is safe for [idleTimeout].
     *
     * Stated separately from [interval] because it is the property that matters
     * and it should be checkable against any value, not only the one this
     * object computes.
     */
    fun staysInside(
        interval: Duration,
        idleTimeout: Duration,
    ): Boolean = interval > Duration.ZERO && interval < idleTimeout
}
