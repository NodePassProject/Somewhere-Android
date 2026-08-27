// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

/**
 * Puts fragmented UDP packets back together, within bounds. NW-P-21.
 *
 * ## Every rule here discards rather than repairs
 *
 * A fragment that disagrees with the ones already held — a different count, a
 * different total length, different bytes at the same index — means one of the
 * two is not what the sender sent. There is no way to tell which, so the packet
 * goes. That is not a lost cause: this is UDP, and a lost packet is a thing the
 * layer above already handles. A *wrong* packet is not.
 *
 * ## Bounded three ways, because one is not enough
 *
 * The specification bounds reassembly to 64 active packet slots per connection,
 * a shared byte budget, and a ten-second fragment lifetime. Each closes a
 * different hole: slots stop a peer opening endless packets, bytes stop it
 * opening a few enormous ones, and the lifetime stops a packet whose last
 * fragment never comes from holding its slot for the life of the connection.
 *
 * The clock is a parameter for the same reason [eu.nodepass.somewhere.vpn.TrafficMeter]'s
 * is: a test for a ten-second rule that waited ten seconds would measure the
 * machine.
 */
class FragmentReassembly(
    private val clock: () -> Long,
    private val maxSlots: Int = MAX_SLOTS,
    private val maxBytes: Int = MAX_BYTES,
    private val lifetimeMillis: Long = LIFETIME_MILLIS,
) {
    private class Slot(
        val count: Int,
        val totalLength: Int,
        val startedAt: Long,
    ) {
        val pieces = arrayOfNulls<ByteArray>(count)
        var heldBytes = 0

        val complete: Boolean get() = pieces.all { it != null }
    }

    private val slots = LinkedHashMap<Pair<UInt, UInt>, Slot>()
    private var heldBytes = 0

    /** What accepting a fragment produced. */
    sealed interface Outcome {
        /** Held; the packet is not complete yet. */
        data object Held : Outcome

        /** Complete. These are the original packet's bytes. */
        data class Complete(
            val flowId: UInt,
            val payload: ByteArray,
        ) : Outcome {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Complete && flowId == other.flowId && payload.contentEquals(other.payload))

            override fun hashCode(): Int = 31 * flowId.hashCode() + payload.contentHashCode()
        }

        /** The packet is gone, and why. Nothing is retried and nothing is repaired. */
        data class Discarded(
            val why: String,
        ) : Outcome
    }

    /** How many packets are part-assembled right now. */
    val activeSlots: Int get() = slots.size

    /** How many bytes those packets are holding. */
    val bytesHeld: Int get() = heldBytes

    fun accept(fragment: QuicDatagram.Fragment): Outcome {
        expire()

        val key = fragment.flowId to fragment.packetId
        val existing = slots[key]

        if (existing != null &&
            (existing.count != fragment.count || existing.totalLength != fragment.totalLength)
        ) {
            drop(key)
            return Outcome.Discarded("a fragment disagrees with the ones already held about count or total length")
        }

        if (existing == null && slots.size >= maxSlots) {
            return Outcome.Discarded("$maxSlots packets are already being reassembled")
        }
        if (heldBytes + fragment.payload.size > maxBytes) {
            return Outcome.Discarded("the reassembly budget of $maxBytes bytes is spent")
        }

        val slot =
            existing ?: Slot(fragment.count, fragment.totalLength, clock()).also { slots[key] = it }

        val already = slot.pieces[fragment.index]
        if (already != null) {
            // A duplicate is ordinary — networks do that. A duplicate that
            // differs is not, and there is no way to know which copy is real.
            if (already.contentEquals(fragment.payload)) return Outcome.Held
            drop(key)
            return Outcome.Discarded("two fragments at index ${fragment.index} carry different bytes")
        }

        slot.pieces[fragment.index] = fragment.payload
        slot.heldBytes += fragment.payload.size
        heldBytes += fragment.payload.size

        if (!slot.complete) return Outcome.Held

        val assembled = slot.pieces.filterNotNull().fold(ByteArray(0)) { acc, piece -> acc + piece }
        drop(key)
        if (assembled.size != slot.totalLength) {
            return Outcome.Discarded("the reassembled packet is ${assembled.size} bytes and claims ${slot.totalLength}")
        }
        return Outcome.Complete(fragment.flowId, assembled)
    }

    /** Drops every packet whose first fragment is older than the lifetime. */
    fun expire() {
        val deadline = clock() - lifetimeMillis
        slots.entries.removeAll { (_, slot) ->
            (slot.startedAt <= deadline).also { if (it) heldBytes -= slot.heldBytes }
        }
    }

    /** Forgets everything for a flow, which is what a CLOSE means. */
    fun forget(flowId: UInt) {
        slots.entries.removeAll { (key, slot) ->
            (key.first == flowId).also { if (it) heldBytes -= slot.heldBytes }
        }
    }

    private fun drop(key: Pair<UInt, UInt>) {
        slots.remove(key)?.let { heldBytes -= it.heldBytes }
    }

    companion object {
        /** Active packet slots per authenticated connection. */
        const val MAX_SLOTS = 64

        /**
         * The shared byte budget.
         *
         * Slots alone bound the count and not the size: 64 packets of 65,535
         * bytes is four megabytes a peer can pin by sending one fragment of
         * each. This is the other half of the bound.
         */
        const val MAX_BYTES = 4 * 1024 * 1024

        /** How long a packet may wait for the fragment that never comes. */
        const val LIFETIME_MILLIS = 10_000L
    }
}
