// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

/**
 * One flow-control window, in bytes. NW-P-15.
 *
 * A Mux carrier has one of these per stream and one for the connection, and
 * payload has to pass both before it may be queued. That ordering is the whole
 * mechanism: a stream cannot reserve more than the peer said it would read, and
 * the streams together cannot reserve more than the carrier said it would read.
 *
 * ## Blocking is the point
 *
 * [acquire] waits rather than failing. Payload that arrives with no credit is
 * not an error — it is a peer that has not caught up, and the correct response
 * is to slow down. On this client the caller is a per-connection pump, so
 * waiting here becomes back-pressure on the device's own TCP window, which is
 * where it belongs. Failing instead would turn a slow reader into lost bytes.
 *
 * The alternative, an unbounded queue in front of a slow peer, is the shape
 * this project has already been bitten by once at the lwIP layer: a write that
 * is accepted and then dropped looks like a working tunnel that is quietly
 * losing most of a transfer.
 *
 * ## Why returning credit can close the carrier
 *
 * [release] is called for a peer's WINDOW frame, and a peer that returns more
 * credit than it ever advertised is either broken or trying to make this side
 * commit memory it does not have. The specification says that closes the
 * carrier, so the return type says so too.
 */
class MuxCredit(
    /** The window this side was told about, and the ceiling [release] enforces. */
    val window: Int,
) {
    private var available: Long = window.toLong()
    private var revoked = false
    private val lock = Object()

    val availableBytes: Long get() = synchronized(lock) { available }

    /**
     * Takes [bytes] from the window, waiting for the peer to return some if
     * there are not enough.
     *
     * @return false when the carrier closed while waiting. The caller's flow is
     *   over at that point; there is nothing left to send it on.
     */
    fun acquire(bytes: Int): Boolean {
        if (bytes <= 0) return !revoked
        synchronized(lock) {
            while (available < bytes) {
                if (revoked) return false
                lock.wait()
            }
            available -= bytes
            return true
        }
    }

    /**
     * Takes what is available up to [bytes], without waiting.
     *
     * For a caller that would rather send a short frame now than a full one
     * later — which is every caller with a stream to keep moving.
     *
     * @return how much was taken; zero means none was available.
     */
    fun acquireAtMost(bytes: Int): Int {
        if (bytes <= 0) return 0
        synchronized(lock) {
            if (revoked) return 0
            val taken = minOf(available, bytes.toLong()).toInt()
            available -= taken
            return taken
        }
    }

    /**
     * Puts credit back, as a peer's WINDOW frame does.
     *
     * @return the new total, or [MuxReason.CreditExceedsWindow] when the peer
     *   returned more than it ever advertised — which closes the carrier.
     */
    fun release(bytes: Int): DecodeResult<Long> {
        synchronized(lock) {
            val total = available + bytes
            if (total > window) return invalid(MuxReason.CreditExceedsWindow(total, window))
            available = total
            lock.notifyAll()
            return total.ok()
        }
    }

    /**
     * The carrier is gone: wake everything waiting and refuse everything after.
     *
     * Without this, a stream blocked on [acquire] when the carrier dies waits
     * for a WINDOW frame that can never arrive — a hang rather than a failure,
     * and the one failure mode with nothing to report.
     */
    fun revoke() {
        synchronized(lock) {
            revoked = true
            lock.notifyAll()
        }
    }

    val isRevoked: Boolean get() = synchronized(lock) { revoked }
}

/**
 * The credit this side has advertised and not yet been read out of.
 *
 * The mirror of [MuxCredit], for the receiving direction. The peer may send up
 * to [MuxCredit.window] before it must stop; every byte the application
 * actually consumes is credit that can be handed back with a WINDOW frame.
 *
 * WINDOW frames are batched rather than sent per read. One frame per `read()`
 * would put an eight-byte header on the wire for every buffer the application
 * drains, which on a fast transfer is most of the traffic; waiting until a
 * quarter of the window has been consumed sends four frames per window instead
 * of thousands, and still returns credit long before the peer can exhaust it.
 */
class MuxReceiveWindow(
    private val window: Int,
) {
    private var consumed = 0L
    private val lock = Any()

    /**
     * When an update becomes due.
     *
     * A quarter of the window, but never more than one frame can carry — the
     * `value` field is a u16 and the window is 512 KiB, so a naive quarter is
     * twice what fits. Capping here keeps [consume] returning an amount a
     * single frame can express, and the caller splits anything larger that
     * [drain] hands back.
     */
    private val threshold: Int = minOf(window / UPDATE_DIVISOR, MuxHeader.MAX_WINDOW_CREDIT)

    /** How much has been consumed and not yet returned. */
    val pending: Long get() = synchronized(lock) { consumed }

    /**
     * Records [bytes] as read by the application.
     *
     * @return the credit to return now, or zero if it is not yet worth a frame.
     */
    fun consume(bytes: Int): Int =
        synchronized(lock) {
            if (bytes <= 0) return 0
            consumed += bytes
            if (consumed < threshold) return 0
            val due = consumed.toInt()
            consumed = 0
            due
        }

    /** Everything outstanding, for the last frame before a stream closes. */
    fun drain(): Int =
        synchronized(lock) {
            val due = consumed.toInt()
            consumed = 0
            due
        }

    private companion object {
        /**
         * Return credit once a quarter of the window has been consumed.
         *
         * Four frames per window. Smaller would be chattier for no benefit;
         * larger risks the peer reaching the window before a frame is due,
         * which stalls the stream for a full round trip.
         */
        const val UPDATE_DIVISOR = 4
    }
}
