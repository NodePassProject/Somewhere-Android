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
    ) : TunnelState

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
    private val mutable = MutableStateFlow<TunnelState>(TunnelState.Disconnected)

    val state: StateFlow<TunnelState> = mutable.asStateFlow()

    val isEngaged: Boolean
        get() = state.value is TunnelState.Connected || state.value is TunnelState.Connecting

    internal fun report(next: TunnelState) {
        mutable.value = next
    }
}
