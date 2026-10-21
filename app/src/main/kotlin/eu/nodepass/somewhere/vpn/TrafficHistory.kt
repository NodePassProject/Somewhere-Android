// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

/**
 * The recent rates, one series per direction.
 *
 * Two series and never one: the home screen's whole argument is that upstream
 * and downstream are separate measurements, and a combined curve would be the
 * average this project already refused to show as a number. Each is drawn
 * against its own peak.
 */
data class TrafficHistory(
    val upstream: List<Long>,
    val downstream: List<Long>,
) {
    /** This history with [sample]'s rates appended, the oldest dropped past [CAPACITY]. */
    fun plus(sample: TrafficSample): TrafficHistory =
        TrafficHistory(
            upstream = (upstream + sample.upstreamBytesPerSecond).takeLast(CAPACITY),
            downstream = (downstream + sample.downstreamBytesPerSecond).takeLast(CAPACITY),
        )

    companion object {
        /** A minute, at the meter's one reading a second. */
        const val CAPACITY = 60

        val EMPTY = TrafficHistory(emptyList(), emptyList())
    }
}
