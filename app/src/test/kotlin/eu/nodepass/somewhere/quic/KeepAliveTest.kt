// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The keep-alive interval, which is arithmetic and therefore needs no network.
 *
 * The property that matters is not the number but the relation: whatever is
 * chosen must stay inside the timeout it is defending against, for every
 * timeout a peer could announce. That is checked over a range rather than at
 * the two or three values a hand-written case would pick.
 */
class KeepAliveTest {
    @Test
    fun anIntervalAlwaysStaysInsideTheTimeoutItDefendsAgainst() {
        // Every second from one to ten minutes. A keep-alive that equalled the
        // timeout would be a connection closed by the deadline it was meant to
        // outrun, and it would present as an intermittently dying tunnel.
        for (seconds in 1..600) {
            val idle = seconds.seconds
            val interval = KeepAlive.interval(idle) ?: continue
            assertTrue(
                "a $interval keep-alive does not stay inside a $idle timeout",
                KeepAlive.staysInside(interval, idle),
            )
        }
    }

    @Test
    fun itIsMoreFrugalThanUpstreamWhereverTheTimeoutAllows() {
        // Upstream's client sends every fifteen seconds regardless. On a phone
        // that is a radio wake every fifteen seconds for the life of the
        // tunnel, and the radio is the expensive part by a wide margin.
        val interval = KeepAlive.interval(UPSTREAM_DEFAULT_IDLE)
        assertEquals(KeepAlive.CEILING, interval)
        assertTrue(
            "the interval must beat upstream's fixed ${KeepAlive.UPSTREAM_INTERVAL}",
            interval!! > KeepAlive.UPSTREAM_INTERVAL,
        )
    }

    @Test
    fun aTimeoutTooShortToDefendCheaplyIsNotDefendedAtAll() {
        // Below the floor the connection is allowed to close and be rebuilt.
        // One handshake is cheaper than a permanently busy radio, and a peer
        // announcing a very short timeout would otherwise produce a storm.
        assertNull(KeepAlive.interval(4.seconds))
        assertNull(KeepAlive.interval(1.milliseconds))
    }

    @Test
    fun noTimeoutMeansNoKeepAlive() {
        // Nothing will close the connection for being quiet, so nothing needs
        // to be said. Sending anyway would be a radio wake with no purpose.
        assertNull(KeepAlive.interval(Duration.ZERO))
    }

    @Test
    fun aVeryLongTimeoutIsCappedRatherThanTrusted() {
        // QUIC forgets connections long after a middlebox forgets the UDP
        // mapping underneath one. A keep-alive derived only from the peer's
        // generosity would be sent into a path that no longer exists.
        assertEquals(KeepAlive.CEILING, KeepAlive.interval(60.minutes))
        assertTrue(KeepAlive.CEILING <= 30.seconds)
    }

    @Test
    fun theFloorAndTheCeilingDoNotCross() {
        assertTrue("the bounds are the wrong way round", KeepAlive.FLOOR < KeepAlive.CEILING)
    }

    private companion object {
        /** `NOW_UDP_IDLE_TIMEOUT`'s default, which is what a Portal announces. */
        val UPSTREAM_DEFAULT_IDLE = 2.minutes
    }
}
