// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * How much has gone each way, and how fast it is going now.
 *
 * ## Two directions, never one number
 *
 * Every other client shows a single throughput figure, because for every other
 * protocol there is one path. Nowhere can put the two directions on different
 * carriers, so one number here would be the average of two unrelated
 * measurements. The counters are separate all the way through, and nothing in
 * this class can add them together except where it says so.
 *
 * ## A rate is a difference, so the first sample has none
 *
 * Throughput is bytes over an interval, and before there are two observations
 * there is no interval. [sample] therefore reports [TrafficSample.measured] as
 * false the first time, and the screen shows an em dash rather than `0 B/s`.
 *
 * That distinction is not pedantry: it is `docs/design-system.md` rule 4, and
 * this project has already shipped its opposite. A screen that reads zero when
 * nothing has been measured claims a measurement, and the claim is
 * indistinguishable from a tunnel that is up and carrying nothing — which is a
 * completely different situation with a completely different next step.
 *
 * ## The clock is a parameter
 *
 * Because a test for a rate needs to control the interval, and because a rate
 * derived from wall-clock time is wrong twice a year and whenever a user
 * adjusts the clock. Callers pass a monotonic source; tests pass a counter.
 */
class TrafficMeter(
    private val clock: () -> Long,
) {
    private val upstream = AtomicLong(0)
    private val downstream = AtomicLong(0)
    private val direct = AtomicLong(0)

    private var lastSampleAt: Long? = null
    private var lastUpstream = 0L
    private var lastDownstream = 0L
    private var upstreamPeak = 0L
    private var downstreamPeak = 0L
    private val lock = Any()

    /** Bytes handed to the Portal. Called once the write has actually happened. */
    fun recordUpstream(bytes: Int) {
        if (bytes > 0) upstream.addAndGet(bytes.toLong())
    }

    /** Bytes taken from the Portal. */
    fun recordDownstream(bytes: Int) {
        if (bytes > 0) downstream.addAndGet(bytes.toLong())
    }

    /**
     * Bytes that left the device without touching the Portal.
     *
     * Counted apart from both directions rather than added to either. A routing
     * rule can send a large share of a device's traffic straight out, and a
     * throughput figure that included it would describe the tunnel's load as
     * something it is not — the same defect as one number for two directions,
     * which this class refuses on the line above.
     *
     * Undirected on purpose: the interesting question about direct traffic is
     * how much of it there is, and splitting it would imply a screen that shows
     * two more figures nobody has designed.
     */
    fun recordDirect(bytes: Int) {
        if (bytes > 0) direct.addAndGet(bytes.toLong())
    }

    /** Bytes that bypassed the tunnel entirely, in both directions together. */
    val directBytes: Long get() = direct.get()

    val upstreamBytes: Long get() = upstream.get()

    val downstreamBytes: Long get() = downstream.get()

    /**
     * Totals now, and the rate over the interval since the previous call.
     *
     * Sampling is what advances the interval, so this is not a pure read: two
     * callers sampling the same meter would each see half the traffic. One
     * caller, on a timer, is the intended shape.
     *
     * @param activeFlows read from the session rather than counted here. One
     *   fact, one source: a meter that kept its own flow count would eventually
     *   disagree with the allocator that hands out the ids.
     */
    fun sample(activeFlows: Int = 0): TrafficSample {
        val now = clock()
        val up = upstream.get()
        val down = downstream.get()

        return synchronized(lock) {
            val previous = lastSampleAt
            val elapsed = if (previous == null) 0L else now - previous
            lastSampleAt = now

            // A non-positive interval means the caller sampled twice within the
            // clock's resolution. Reporting the whole delta over "no time" would
            // produce an enormous figure from a real measurement, so the
            // previous rate stands and the bytes stay counted for next time.
            if (previous == null || elapsed <= 0) {
                lastUpstream = up
                lastDownstream = down
                return@synchronized TrafficSample(
                    upstreamBytes = up,
                    downstreamBytes = down,
                    upstreamBytesPerSecond = 0,
                    downstreamBytesPerSecond = 0,
                    upstreamOfPeak = 0f,
                    downstreamOfPeak = 0f,
                    activeFlows = activeFlows,
                    measured = false,
                )
            }

            val upRate = (up - lastUpstream) * MILLIS_PER_SECOND / elapsed
            val downRate = (down - lastDownstream) * MILLIS_PER_SECOND / elapsed
            lastUpstream = up
            lastDownstream = down
            upstreamPeak = maxOf(upstreamPeak, upRate)
            downstreamPeak = maxOf(downstreamPeak, downRate)

            TrafficSample(
                upstreamBytes = up,
                downstreamBytes = down,
                upstreamBytesPerSecond = upRate,
                downstreamBytesPerSecond = downRate,
                // Each direction against its own peak, never against the
                // other's: a meter scaled to the larger direction would draw an
                // upload of a few kilobytes as empty next to a download, which
                // is the one comparison the home screen must not invite.
                upstreamOfPeak = fraction(upRate, upstreamPeak),
                downstreamOfPeak = fraction(downRate, downstreamPeak),
                activeFlows = activeFlows,
                measured = true,
            )
        }
    }

    private fun fraction(
        rate: Long,
        peak: Long,
    ): Float = if (peak <= 0) 0f else (rate.toDouble() / peak.toDouble()).toFloat()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * One reading.
 *
 * [measured] is carried alongside the figures rather than inferred from them,
 * because "nothing has been measured" and "zero was measured" are different
 * facts and zero cannot tell them apart.
 */
data class TrafficSample(
    val upstreamBytes: Long,
    val downstreamBytes: Long,
    val upstreamBytesPerSecond: Long,
    val downstreamBytesPerSecond: Long,
    val upstreamOfPeak: Float,
    val downstreamOfPeak: Float,
    val activeFlows: Int,
    val measured: Boolean,
) {
    /** Both directions together, for the one place that wants a session total. */
    val totalBytes: Long get() = upstreamBytes + downstreamBytes

    companion object {
        val NONE =
            TrafficSample(
                upstreamBytes = 0,
                downstreamBytes = 0,
                upstreamBytesPerSecond = 0,
                downstreamBytesPerSecond = 0,
                upstreamOfPeak = 0f,
                downstreamOfPeak = 0f,
                activeFlows = 0,
                measured = false,
            )
    }
}
