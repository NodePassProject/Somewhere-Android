// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A fake clock throughout. A rate test against the wall clock measures the
 * machine it runs on, and fails on a loaded CI runner for reasons that have
 * nothing to do with the code.
 */
class TrafficMeterTest {
    private var now = 0L
    private val meter = TrafficMeter { now }

    @Test
    fun `the first sample has no interval and therefore no rate`() {
        meter.recordDownstream(1_000)
        val first = meter.sample()
        assertFalse("nothing has been measured yet", first.measured)
        assertEquals("but the bytes are counted", 1_000, first.downstreamBytes)
        assertEquals(0, first.downstreamBytesPerSecond)
    }

    @Test
    fun `a rate is bytes over the interval that produced them`() {
        meter.sample()
        now += 1_000
        meter.recordDownstream(2_048)
        val sample = meter.sample()
        assertTrue(sample.measured)
        assertEquals(2_048, sample.downstreamBytesPerSecond)

        now += 500
        meter.recordDownstream(2_048)
        assertEquals("half the interval, twice the rate", 4_096, meter.sample().downstreamBytesPerSecond)
    }

    @Test
    fun `the two directions are counted apart`() {
        meter.sample()
        now += 1_000
        meter.recordUpstream(100)
        meter.recordDownstream(900)
        val sample = meter.sample()
        assertEquals(100, sample.upstreamBytes)
        assertEquals(900, sample.downstreamBytes)
        assertEquals(100, sample.upstreamBytesPerSecond)
        assertEquals(900, sample.downstreamBytesPerSecond)
        assertEquals(1_000, sample.totalBytes)
    }

    @Test
    fun `totals only ever grow`() {
        val random = Random(20260826)
        var lastUp = 0L
        var lastDown = 0L
        repeat(2_000) {
            meter.recordUpstream(random.nextInt(0, 4_096))
            meter.recordDownstream(random.nextInt(0, 4_096))
            now += random.nextLong(0, 2_000)
            val sample = meter.sample()
            assertTrue("upstream went backwards", sample.upstreamBytes >= lastUp)
            assertTrue("downstream went backwards", sample.downstreamBytes >= lastDown)
            lastUp = sample.upstreamBytes
            lastDown = sample.downstreamBytes
        }
    }

    @Test
    fun `bytes from many flows land in one total`() {
        meter.sample()
        now += 1_000
        // Four concurrent flows' worth, interleaved the way real pumps arrive.
        repeat(4) { meter.recordDownstream(250) }
        repeat(4) { meter.recordUpstream(50) }
        val sample = meter.sample()
        assertEquals(1_000, sample.downstreamBytes)
        assertEquals(200, sample.upstreamBytes)
    }

    @Test
    fun `a zero-length write is not a measurement`() {
        meter.recordUpstream(0)
        meter.recordDownstream(-1)
        assertEquals(0, meter.upstreamBytes)
        assertEquals(0, meter.downstreamBytes)
    }

    @Test
    fun `two samples inside the clock's resolution do not invent a rate`() {
        meter.sample()
        now += 1_000
        meter.recordDownstream(1_000)
        assertEquals(1_000, meter.sample().downstreamBytesPerSecond)

        // Same millisecond. Dividing the delta by no time at all would report a
        // number with no upper bound, from a real measurement.
        meter.recordDownstream(5_000)
        val degenerate = meter.sample()
        assertFalse(degenerate.measured)
        assertEquals(0, degenerate.downstreamBytesPerSecond)
        assertEquals("and the bytes are not lost", 6_000, degenerate.downstreamBytes)
    }

    @Test
    fun `each direction is drawn against its own peak`() {
        meter.sample()
        now += 1_000
        meter.recordDownstream(10_000)
        meter.recordUpstream(100)
        val busy = meter.sample()
        assertEquals("both are at their own peak", 1f, busy.downstreamOfPeak, 0.001f)
        assertEquals(1f, busy.upstreamOfPeak, 0.001f)

        now += 1_000
        meter.recordDownstream(5_000)
        meter.recordUpstream(100)
        val quieter = meter.sample()
        assertEquals("downstream halved against its own peak", 0.5f, quieter.downstreamOfPeak, 0.001f)
        assertEquals(
            "upstream is unchanged, and is not scaled by the other direction",
            1f,
            quieter.upstreamOfPeak,
            0.001f,
        )
    }

    @Test
    fun `a peak is remembered after the traffic that set it stops`() {
        meter.sample()
        now += 1_000
        meter.recordDownstream(8_000)
        meter.sample()
        now += 1_000
        val idle = meter.sample()
        assertEquals(0, idle.downstreamBytesPerSecond)
        assertEquals(0f, idle.downstreamOfPeak, 0.001f)
        assertTrue("idle is still a measurement", idle.measured)
    }

    @Test
    fun `the flow count comes from the caller, not from the meter`() {
        meter.sample()
        now += 1_000
        assertEquals(7, meter.sample(activeFlows = 7).activeFlows)
    }

    @Test
    fun `the empty reading claims nothing`() {
        assertFalse(TrafficSample.NONE.measured)
        assertEquals(0, TrafficSample.NONE.totalBytes)
    }
}
