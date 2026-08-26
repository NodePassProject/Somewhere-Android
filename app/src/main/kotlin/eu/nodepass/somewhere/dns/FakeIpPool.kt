// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

/**
 * Synthetic addresses that stand in for names, so that a name can survive as
 * far as the Portal.
 *
 * A Nowhere target is either an address or a name, and a name is the useful
 * one: the Portal resolves it in its own network, which is the whole reason
 * remote resolution exists. But nothing between an app and this client carries
 * names — an app resolves, then connects to an address, and by the time lwIP
 * hands over a connection the name is gone.
 *
 * So the name is kept here and an address is invented to carry it. A DNS query
 * for `example.com` is answered with an address out of a range that routes
 * nowhere real; when a flow later arrives at that address, the name comes back
 * out and the flow opens as [eu.nodepass.somewhere.protocol.target.Target.Domain].
 * The address never leaves the device.
 *
 * ## The rule that shapes this class
 *
 * **An address in use is never handed to a different name.** The pool is
 * bounded, so it must evict; evicting a mapping that a live flow is still
 * relying on would silently redirect that flow to somebody else's host —
 * traffic for one site arriving at another, with no error anywhere. Entries are
 * therefore retained for as long as a flow holds them ([retain]/[release]) and
 * eviction walks past them to the oldest mapping nobody is using.
 *
 * When every entry is retained, allocation fails rather than evicting one
 * anyway. A failed allocation is a query that gets relayed upstream instead —
 * a worse route, and correct.
 *
 * A count rather than a flag, because one address commonly carries several
 * flows at once: a page opens six connections to the same host, and the first
 * one to close must not unpin the other five.
 *
 * ## Ranges
 *
 * IPv4 is `198.18.0.0/15`, reserved by RFC 2544 for benchmarking and routed by
 * nobody. IPv6 is `fc00::` plus the offset, from the RFC 4193 unique-local
 * range. Both match the donor client, which matters where the two run on one
 * device: a range collision would make each look like a defect in the other.
 */
class FakeIpPool(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity in 1..MAX_CAPACITY) { "capacity must be 1..$MAX_CAPACITY, got $capacity" }
    }

    private class Entry(
        val offset: Int,
        val name: String,
    ) {
        /** How many live flows are relying on this mapping. */
        var holders: Int = 0

        /** Ordering for eviction; a counter rather than a clock, so tests need no fake time. */
        var usedAt: Long = 0
    }

    private val byName = HashMap<String, Entry>()
    private val byOffset = HashMap<Int, Entry>()
    private var nextOffset = 1
    private var tick = 0L
    private val lock = Any()

    val size: Int get() = synchronized(lock) { byName.size }

    /** Entries a flow is currently relying on. Never evicted. */
    val retainedCount: Int get() = synchronized(lock) { byName.values.count { it.holders > 0 } }

    /**
     * The offset standing for [name], allocating one if this is the first time.
     *
     * @return null when the pool is full and every entry is retained. The
     *   caller's honest response is to stop pretending it has an address —
     *   relay the query upstream rather than answering with someone else's.
     */
    fun allocate(name: String): Int? =
        synchronized(lock) {
            byName[name]?.let { existing ->
                existing.usedAt = ++tick
                return existing.offset
            }

            val offset =
                if (nextOffset <= capacity) {
                    nextOffset++
                } else {
                    evictOldestIdle() ?: return null
                }

            val entry = Entry(offset, name)
            entry.usedAt = ++tick
            byName[name] = entry
            byOffset[offset] = entry
            offset
        }

    /** The name behind an address, without claiming it. Null when the address is not ours. */
    fun nameFor(octets: ByteArray): String? =
        synchronized(lock) {
            val entry = byOffset[offsetOf(octets) ?: return null] ?: return null
            entry.usedAt = ++tick
            entry.name
        }

    /**
     * The name behind an address, held against eviction until [release].
     *
     * This is what a flow calls. Between [retain] and [release] the mapping
     * cannot be reassigned, which is the guarantee that keeps a long-lived
     * connection pointed at the host it was opened to.
     */
    fun retain(octets: ByteArray): String? =
        synchronized(lock) {
            val entry = byOffset[offsetOf(octets) ?: return null] ?: return null
            entry.holders++
            entry.usedAt = ++tick
            entry.name
        }

    /**
     * Gives back one hold.
     *
     * Releasing an address that was never retained, or releasing twice, is
     * ignored rather than treated as an error: teardown paths race, and a pool
     * that threw there would turn a harmless double close into a crash. The
     * count is clamped at zero for the same reason — an under-count would pin
     * an entry forever, which is the failure that does not announce itself.
     */
    fun release(octets: ByteArray) {
        synchronized(lock) {
            val entry = byOffset[offsetOf(octets) ?: return] ?: return
            if (entry.holders > 0) entry.holders--
        }
    }

    fun clear() {
        synchronized(lock) {
            byName.clear()
            byOffset.clear()
            nextOffset = 1
            tick = 0
        }
    }

    /** @return the freed offset, or null when every entry is held by a live flow. */
    private fun evictOldestIdle(): Int? {
        val victim = byName.values.filter { it.holders == 0 }.minByOrNull { it.usedAt } ?: return null
        byName.remove(victim.name)
        byOffset.remove(victim.offset)
        return victim.offset
    }

    /** Which entry an address refers to, or null when the address is outside our ranges. */
    private fun offsetOf(octets: ByteArray): Int? =
        when (octets.size) {
            IPV4_LENGTH -> ipv4Offset(octets)
            IPV6_LENGTH -> ipv6Offset(octets)
            else -> null
        }

    private fun ipv4Offset(octets: ByteArray): Int? {
        val value =
            ((octets[0].toLong() and 0xFF) shl 24) or
                ((octets[1].toLong() and 0xFF) shl 16) or
                ((octets[2].toLong() and 0xFF) shl 8) or
                (octets[3].toLong() and 0xFF)
        val offset = value - IPV4_BASE
        return if (offset in 1..capacity) offset.toInt() else null
    }

    private fun ipv6Offset(octets: ByteArray): Int? {
        if (octets[0] != IPV6_PREFIX_FIRST || octets[1] != 0.toByte()) return null
        for (index in 2 until 12) if (octets[index] != 0.toByte()) return null
        val offset =
            ((octets[12].toLong() and 0xFF) shl 24) or
                ((octets[13].toLong() and 0xFF) shl 16) or
                ((octets[14].toLong() and 0xFF) shl 8) or
                (octets[15].toLong() and 0xFF)
        return if (offset in 1..capacity) offset.toInt() else null
    }

    companion object {
        /** `198.18.0.0`, the first address of the RFC 2544 range. */
        const val IPV4_BASE: Long = 0xC612_0000L

        /** `198.18.0.0/15` holds this many addresses; offset 0 is the network address. */
        const val MAX_CAPACITY: Int = (1 shl 17) - 1

        /**
         * Mappings kept at once.
         *
         * Large enough that a day of ordinary browsing never reaches it, small
         * enough that the bookkeeping stays trivial. The range would hold eight
         * times as many; the limit is about bounding memory, not about running
         * out of addresses.
         */
        const val DEFAULT_CAPACITY: Int = 16_384

        const val IPV4_LENGTH: Int = 4
        const val IPV6_LENGTH: Int = 16

        private const val IPV6_PREFIX_FIRST: Byte = 0xFC.toByte()

        fun ipv4(offset: Int): ByteArray {
            val value = IPV4_BASE + offset
            return byteArrayOf(
                ((value shr 24) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )
        }

        fun ipv6(offset: Int): ByteArray =
            ByteArray(IPV6_LENGTH).also {
                it[0] = IPV6_PREFIX_FIRST
                it[12] = ((offset shr 24) and 0xFF).toByte()
                it[13] = ((offset shr 16) and 0xFF).toByte()
                it[14] = ((offset shr 8) and 0xFF).toByte()
                it[15] = (offset and 0xFF).toByte()
            }

        /**
         * Whether an address is one this pool could have minted.
         *
         * A range test rather than a lookup, for the caller that has to decide
         * cheaply, on the lwIP thread, whether a destination is worth asking
         * about at all.
         */
        fun isFake(octets: ByteArray): Boolean =
            when (octets.size) {
                IPV4_LENGTH -> (octets[0].toInt() and 0xFF) == 198 && (octets[1].toInt() and 0xFF) in 18..19
                IPV6_LENGTH ->
                    octets[0] == IPV6_PREFIX_FIRST &&
                        octets[1] == 0.toByte() &&
                        (2 until 12).all { octets[it] == 0.toByte() }

                else -> false
            }
    }
}
