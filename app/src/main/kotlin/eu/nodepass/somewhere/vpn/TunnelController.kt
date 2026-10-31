// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the tunnel is doing, for the one screen that shows it.
 *
 * Four states rather than a boolean, because the interesting ones are the two
 * in between. "Connecting" is where a slow Portal lives, and a failure that
 * collapses into "not connected" is the failure mode this project has spent
 * two days removing from everywhere else: the home screen would say the same
 * thing for a Portal that refused the key, a Portal that is down, and a user
 * who never pressed anything.
 */
sealed interface TunnelState {
    data object Disconnected : TunnelState

    data class Connecting(
        val node: String,
    ) : TunnelState

    data class Connected(
        val node: String,
        /**
         * When the TUN came up, on the monotonic clock.
         *
         * Monotonic rather than wall-clock because this is a duration: a
         * device that adjusts its clock, or crosses a daylight-saving
         * boundary, must not make a session appear to have lasted an hour
         * longer or to have started in the future.
         */
        val sinceElapsedRealtime: Long,
        /**
         * The node's URL, which is its identity elsewhere — for looking up what
         * is known about it. Held in memory only, like the node list it came
         * from.
         */
        val nodeUrl: String = "",
        /**
         * A QUIC node that did not answer over QUIC and is carried over TLS on
         * the same port for this session. Shown, because the link says QUIC
         * and the user should not have to read a log to learn otherwise.
         */
        val fellBackToTls: Boolean = false,
    ) : TunnelState {
        // The URL carries the shared key, and a data class prints every field.
        override fun toString(): String = "Connected(node=$node, sinceElapsedRealtime=$sinceElapsedRealtime)"
    }

    /**
     * The tunnel stopped or never started.
     *
     * [reason] is a string resource id so that the message is the device's
     * language rather than a developer's. The same mistake — an English
     * developer string on a Chinese device — was shipped once already on the
     * node card and caught by looking at the screen, not by a test.
     */
    data class Failed(
        val reason: Int,
    ) : TunnelState
}

/**
 * The tunnel's state, published for the UI.
 *
 * A process-wide object rather than something injected because the tunnel *is*
 * process-wide: there is one VpnService, the system owns its lifecycle, and it
 * outlives every activity. Anything scoped narrower would be lying about that.
 */
object TunnelController {
    /**
     * What the Portal answered, flow by flow (NW-A-06).
     *
     * On the controller because the service that writes it is created and
     * destroyed with each tunnel while the screen that reads it outlives both,
     * and a log that vanished when the tunnel stopped would lose exactly the
     * entries a user goes looking for.
     */
    val connectionLog: ConnectionLog = ConnectionLog()

    /** The names the device asked for. On the controller for the reason the connection log is. */
    val dnsLog: DnsLog = DnsLog()

    /**
     * The connections the tunnel is carrying.
     *
     * Process-wide like the tunnel: the flow handler registers and finishes
     * connections in it, and the screen asks it to close one. A tunnel that
     * goes away clears it.
     */
    val tracker: ConnectionTracker = ConnectionTracker { android.os.SystemClock.elapsedRealtime() }

    private val mutableConnections = MutableStateFlow<List<LiveConnection>>(emptyList())

    /** [tracker]'s contents, republished once a second with the traffic reading. */
    val connections: StateFlow<List<LiveConnection>> = mutableConnections.asStateFlow()

    private val mutableHistory = MutableStateFlow(TrafficHistory.EMPTY)

    /**
     * The last minute of rates, a reading a second, each direction its own
     * series. What the home screen draws its two curves from.
     */
    val trafficHistory: StateFlow<TrafficHistory> = mutableHistory.asStateFlow()

    private val mutable = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    private val mutableTraffic = MutableStateFlow(TrafficSample.NONE)

    val state: StateFlow<TunnelState> = mutable.asStateFlow()

    /**
     * What the tunnel has carried, published on the same terms as [state].
     *
     * Separate from [state] because the two change at different rates — the
     * state changes a handful of times per session and this changes every
     * second — and because a screen must be able to say "connected, nothing
     * measured yet" without either half being wrong.
     */
    val traffic: StateFlow<TrafficSample> = mutableTraffic.asStateFlow()

    val isEngaged: Boolean
        get() = state.value is TunnelState.Connected || state.value is TunnelState.Connecting

    internal fun report(next: TunnelState) {
        mutable.value = next
        // A new session's names are its own. The DNS log is what the device
        // looked up, and carried over it would be the last session's browsing
        // on a screen that says "while the tunnel is up".
        if (next is TunnelState.Connecting) dnsLog.clear()
        // Anything that is not Connected has carried nothing *yet*, and last
        // session's figures are not this session's. Cleared here rather than at
        // each call site, and cleared for Connecting as well as for the two
        // ends: a reconnect passes through Connecting, and a rate left over
        // from the previous attempt would be attributed to this one.
        if (next !is TunnelState.Connected) {
            mutableTraffic.value = TrafficSample.NONE
            mutableHistory.value = TrafficHistory.EMPTY
            tracker.clear()
            mutableConnections.value = emptyList()
        }
    }

    internal fun reportTraffic(sample: TrafficSample) {
        // The sampler is stopped cooperatively, so its last reading can land
        // after the tunnel has gone; republishing then would bring back the
        // list and history the stop just cleared.
        if (state.value !is TunnelState.Connected) return
        mutableTraffic.value = sample
        if (sample.measured) mutableHistory.value = mutableHistory.value.plus(sample)
        mutableConnections.value = tracker.snapshot()
    }

    /** Ends one connection the tunnel is carrying. The app on the device sees a reset. */
    fun closeConnection(id: Long) = tracker.close(id)

    /** Ends every connection the tunnel is carrying, and leaves the tunnel up. */
    fun closeAllConnections() = tracker.closeAll()
}
