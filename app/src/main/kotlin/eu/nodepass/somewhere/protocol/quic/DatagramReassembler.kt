// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

/**
 * Puts fragmented UDP packets back together. NW-P-21.
 *
 * Keyed by `(flow_id, packet_id)`. Bounded on both axes because it is fed
 * directly from the network: a peer that opens reassembly slots and never
 * completes them would otherwise grow this without limit. Upstream bounds it to
 * 64 concurrent slots with a 10-second lifetime, and those are the defaults here.
 *
 * Time is supplied by the caller rather than read from a clock, so expiry is
 * testable without waiting and without a fake clock abstraction.
 *
 * **Payload must not be accepted before the control flow reports READY**
 * (NW-P-20). That is enforced here rather than left to a caller, because the
 * consequence of forgetting is data being delivered out of order relative to the
 * setup handshake.
 */
class DatagramReassembler(
    private val maxSlots: Int = DEFAULT_MAX_SLOTS,
    private val slotLifetimeMillis: Long = DEFAULT_SLOT_LIFETIME_MILLIS,
) {
    companion object {
        const val DEFAULT_MAX_SLOTS: Int = 64
        const val DEFAULT_SLOT_LIFETIME_MILLIS: Long = 10_000
    }

    private data class Key(
        val flowId: UInt,
        val packetId: UInt,
    )

    private class Slot(
        val count: Int,
        val totalLength: Int,
        val startedAtMillis: Long,
    ) {
        val fragments: MutableMap<Int, ByteArray> = HashMap()

        val isComplete: Boolean get() = fragments.size == count

        fun assemble(): ByteArray {
            val out = ByteArray(fragments.values.sumOf { it.size })
            var offset = 0
            for (index in 0 until count) {
                val part = fragments.getValue(index)
                part.copyInto(out, offset)
                offset += part.size
            }
            return out
        }
    }

    private val slots = LinkedHashMap<Key, Slot>()
    private var ready = false

    /** Marks the control flow as having reported READY. Payload is refused before this. */
    fun markReady() {
        ready = true
    }

    val slotCount: Int get() = slots.size

    /** Outcome of offering one frame. */
    sealed interface Accepted {
        /** A complete UDP payload, either unfragmented or fully reassembled. */
        data class Payload(
            val flowId: UInt,
            val bytes: ByteArray,
        ) : Accepted {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Payload && flowId == other.flowId && bytes.contentEquals(other.bytes))

            override fun hashCode(): Int = 31 * flowId.hashCode() + bytes.contentHashCode()
        }

        /** The fragment was stored; more are needed. */
        data object Pending : Accepted

        /** The far end closed this flow. */
        data class Closed(
            val flowId: UInt,
        ) : Accepted
    }

    /**
     * Offers one decoded frame.
     *
     * @param nowMillis a monotonic timestamp, used only for slot expiry.
     */
    fun offer(
        frame: DatagramFrame,
        nowMillis: Long,
    ): DecodeResult<Accepted> {
        expire(nowMillis)

        return when (frame) {
            is DatagramFrame.Close -> {
                slots.keys.filter { it.flowId == frame.flowId }.forEach(slots::remove)
                Accepted.Closed(frame.flowId).ok()
            }
            is DatagramFrame.Data ->
                if (!ready) {
                    invalid(DatagramReason.NotReady)
                } else {
                    Accepted.Payload(frame.flowId, frame.payload).ok()
                }
            is DatagramFrame.Fragment ->
                if (!ready) invalid(DatagramReason.NotReady) else acceptFragment(frame, nowMillis)
        }
    }

    private fun acceptFragment(
        fragment: DatagramFrame.Fragment,
        nowMillis: Long,
    ): DecodeResult<Accepted> {
        val key = Key(fragment.flowId, fragment.packetId)
        val slot = slots[key]

        if (slot == null) {
            evictOldestIfFull()
            val fresh = Slot(fragment.count, fragment.totalLength, nowMillis)
            fresh.fragments[fragment.index] = fragment.payload
            slots[key] = fresh
            return completeIfPossible(key, fresh, fragment.flowId)
        }

        // Metadata that disagrees with the slot means two senders are using one
        // packet id, or one sender re-planned without a new id. Either way the
        // packet cannot be trusted, so the whole slot goes.
        if (slot.count != fragment.count || slot.totalLength != fragment.totalLength) {
            slots.remove(key)
            return invalid(DatagramReason.MetadataConflict)
        }

        val existing = slot.fragments[fragment.index]
        if (existing != null) {
            // A duplicate carrying identical bytes is ordinary network
            // behaviour and is ignored; one carrying different bytes means the
            // packet is unreconstructable, so it is discarded entirely.
            return if (existing.contentEquals(fragment.payload)) {
                if (slot.isComplete) completeIfPossible(key, slot, fragment.flowId) else Accepted.Pending.ok()
            } else {
                slots.remove(key)
                invalid(DatagramReason.DuplicateFragmentDiffers)
            }
        }

        slot.fragments[fragment.index] = fragment.payload
        return completeIfPossible(key, slot, fragment.flowId)
    }

    private fun completeIfPossible(
        key: Key,
        slot: Slot,
        flowId: UInt,
    ): DecodeResult<Accepted> {
        if (!slot.isComplete) return Accepted.Pending.ok()
        slots.remove(key)
        val assembled = slot.assemble()
        return if (assembled.size != slot.totalLength) {
            invalid(DatagramReason.ReassembledLengthMismatch(assembled.size, slot.totalLength))
        } else {
            Accepted.Payload(flowId, assembled).ok()
        }
    }

    private fun expire(nowMillis: Long) {
        slots.entries.removeAll { nowMillis - it.value.startedAtMillis >= slotLifetimeMillis }
    }

    private fun evictOldestIfFull() {
        while (slots.size >= maxSlots) {
            val oldest = slots.keys.firstOrNull() ?: return
            slots.remove(oldest)
        }
    }
}
