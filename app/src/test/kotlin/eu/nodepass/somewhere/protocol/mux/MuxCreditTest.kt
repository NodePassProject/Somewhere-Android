// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/** NW-P-15, at the level of one window. */
class MuxCreditTest {
    @Test
    fun aWindowStartsFullAndSpendsDown() {
        val credit = MuxCredit(1_000)
        assertEquals(1_000, credit.availableBytes)
        assertTrue(credit.acquire(400))
        assertEquals(600, credit.availableBytes)
    }

    @Test
    fun creditBeyondTheWindowIsRefused() {
        // The rule that closes the carrier: a peer returning more than it ever
        // advertised is either broken or trying to make this side over-commit.
        val credit = MuxCredit(1_000)
        credit.acquire(100)
        assertTrue(credit.release(100) is DecodeResult.Ok)
        val over = credit.release(1)
        assertTrue(over is DecodeResult.Invalid)
        assertTrue((over as DecodeResult.Invalid).reason is MuxReason.CreditExceedsWindow)
        assertEquals("a refused release changes nothing", 1_000, credit.availableBytes)
    }

    @Test
    fun acquiringWaitsForTheWindowToReopen() {
        val credit = MuxCredit(100)
        assertTrue(credit.acquire(100))
        val acquired = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val waiter =
            thread {
                started.countDown()
                acquired.set(credit.acquire(50))
            }
        started.await()
        Thread.sleep(100)
        assertFalse("it must not proceed on an empty window", acquired.get())

        credit.release(50)
        waiter.join(2_000)
        assertTrue("and must proceed once credit is returned", acquired.get())
    }

    @Test
    fun revokingWakesEveryWaiterAndRefusesAfterwards() {
        // Without this a stream blocked when the carrier dies waits for a
        // WINDOW that can never arrive — a hang rather than a failure, and the
        // one failure mode with nothing to report.
        val credit = MuxCredit(10)
        credit.acquire(10)
        val outcome = AtomicBoolean(true)
        val waiter = thread { outcome.set(credit.acquire(5)) }
        Thread.sleep(50)
        credit.revoke()
        waiter.join(2_000)
        assertFalse("a revoked window refuses rather than hangs", outcome.get())
        assertFalse(credit.acquire(1))
        assertEquals(0, credit.acquireAtMost(1))
    }

    @Test
    fun takingWhatIsThereBeatsWaitingForAllOfIt() {
        val credit = MuxCredit(100)
        credit.acquire(90)
        assertEquals("a short frame that moves beats a full one that waits", 10, credit.acquireAtMost(50))
        assertEquals(0, credit.acquireAtMost(50))
    }

    @Test
    fun aWindowNeverGoesNegativeOrPastItsCeilingUnderChurn() {
        val credit = MuxCredit(4_096)
        val random = Random(20260827)
        var outstanding = 0
        repeat(20_000) {
            if (random.nextBoolean()) {
                val want = random.nextInt(1, 512)
                val got = credit.acquireAtMost(want)
                outstanding += got
            } else if (outstanding > 0) {
                val give = random.nextInt(1, outstanding + 1)
                assertTrue("returning what was spent must always be legal", credit.release(give) is DecodeResult.Ok)
                outstanding -= give
            }
            assertTrue("the window went negative", credit.availableBytes >= 0)
            assertTrue("the window grew past its ceiling", credit.availableBytes <= 4_096)
        }
    }

    @Test
    fun manyThreadsCannotBetweenThemSpendMoreThanTheWindow() {
        val credit = MuxCredit(1_000)
        val spent = AtomicLong(0)
        val threads =
            (1..8).map {
                thread {
                    repeat(500) {
                        val got = credit.acquireAtMost(7)
                        spent.addAndGet(got.toLong())
                    }
                }
            }
        threads.forEach { it.join(5_000) }
        assertEquals("every byte is accounted for exactly once", 1_000 - credit.availableBytes, spent.get())
        assertTrue("and no more than the window was ever handed out", spent.get() <= 1_000)
    }

    @Test
    fun aReceiveWindowBatchesItsUpdates() {
        // One WINDOW frame per read would put an eight-byte header on the wire
        // for every buffer the application drains, which on a fast transfer is
        // most of the traffic.
        val window = MuxReceiveWindow(1_000)
        assertEquals("not yet worth a frame", 0, window.consume(100))
        assertEquals(0, window.consume(100))
        assertEquals("a quarter of the window is", 300, window.consume(100))
        assertEquals("and the count restarts", 0, window.consume(100))
    }

    @Test
    fun aReceiveWindowCanBeDrainedForTheLastFrame() {
        val window = MuxReceiveWindow(1_000)
        window.consume(60)
        assertEquals(60, window.drain())
        assertEquals("draining twice returns nothing the second time", 0, window.drain())
        assertEquals(0, window.pending)
    }

    @Test
    fun everythingConsumedIsEventuallyReturned() {
        // The property that matters: no byte the application read may be left
        // unacknowledged, or the peer stalls a full window short of the end.
        val window = MuxReceiveWindow(MuxHeader.DEFAULT_STREAM_CREDIT)
        val random = Random(7)
        var consumed = 0L
        var returned = 0L
        repeat(10_000) {
            val bytes = random.nextInt(1, 8_192)
            consumed += bytes
            returned += window.consume(bytes)
        }
        returned += window.drain()
        assertEquals(consumed, returned)
    }

    @Test
    fun theBoundsAreTheOnesTheSpecificationStates() {
        assertEquals(32_768, MuxHeader.MAX_STREAM_PAYLOAD)
        assertEquals(524_288, MuxHeader.DEFAULT_STREAM_CREDIT)
        assertEquals(524_288, MuxHeader.DEFAULT_CONNECTION_CREDIT)
        assertEquals(256, MuxHeader.MAX_ACTIVE_STREAMS)
        assertEquals(512, MuxHeader.OUTBOUND_QUEUE_SLOTS)
    }
}
