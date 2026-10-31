// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficHistoryTest {
    private fun sample(
        up: Long,
        down: Long,
    ) = TrafficSample.NONE.copy(upstreamBytesPerSecond = up, downstreamBytesPerSecond = down, measured = true)

    @Test
    fun `each direction keeps its own series`() {
        val history = TrafficHistory.EMPTY.plus(sample(1, 10)).plus(sample(2, 20))
        assertEquals(listOf(1L, 2L), history.upstream)
        assertEquals(listOf(10L, 20L), history.downstream)
    }

    @Test
    fun `a minute is kept and the oldest goes`() {
        var history = TrafficHistory.EMPTY
        repeat(75) { history = history.plus(sample(it.toLong(), 0)) }
        assertEquals(TrafficHistory.CAPACITY, history.upstream.size)
        assertEquals(15L, history.upstream.first())
        assertEquals(74L, history.upstream.last())
    }
}
