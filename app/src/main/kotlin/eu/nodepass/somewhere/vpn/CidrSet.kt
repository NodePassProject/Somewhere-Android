// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import java.math.BigInteger

/**
 * Address ranges, and what is left of one after others are taken out of it.
 *
 * Needed for one thing: keeping ranges out of the tunnel on Android below 13,
 * where `VpnService.Builder.excludeRoute` does not exist. There the only way
 * to leave a range out is to route everything else, so the routes are the
 * complement — computed here, as the smallest list of prefixes that covers the
 * universe minus the exclusions, and checked by tests that need no device.
 */
object CidrSet {
    /** A prefix as a number range, family by byte length. */
    data class Range(
        val first: BigInteger,
        val last: BigInteger,
        val bytes: Int,
    )

    /** `address/prefix`, or null if either part is not one. Host bits are cleared. */
    fun parse(text: String): TunConfiguration.Cidr? {
        val slash = text.trim().indexOf('/')
        val address = if (slash < 0) text.trim() else text.trim().take(slash)
        val bytes = TunConfiguration.parse(address) ?: return null
        val max = bytes.size * 8
        val prefix = if (slash < 0) max else text.trim().substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..max) return null
        val range = range(bytes, prefix)
        return TunConfiguration.Cidr(format(range.first, bytes.size), prefix)
    }

    fun range(cidr: TunConfiguration.Cidr): Range? {
        val bytes = TunConfiguration.parse(cidr.address) ?: return null
        return range(bytes, cidr.prefix)
    }

    private fun range(
        address: ByteArray,
        prefix: Int,
    ): Range {
        val bits = address.size * 8
        val value = BigInteger(1, address)
        val hostMask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE)
        val first = value.andNot(hostMask)
        return Range(first, first.or(hostMask), address.size)
    }

    /**
     * [universe] with every one of [excluded] taken out, as prefixes.
     *
     * Exclusions of the other family are ignored. The result is minimal: no
     * two prefixes in it could be merged into one.
     */
    fun subtract(
        universe: TunConfiguration.Cidr,
        excluded: List<TunConfiguration.Cidr>,
    ): List<TunConfiguration.Cidr> {
        val whole = range(universe) ?: return emptyList()
        val cuts =
            excluded
                .mapNotNull(::range)
                .filter { it.bytes == whole.bytes && it.last >= whole.first && it.first <= whole.last }
                .sortedBy { it.first }
        val pieces = mutableListOf<Range>()
        var cursor = whole.first
        for (cut in cuts) {
            if (cut.first > cursor) pieces += Range(cursor, cut.first.subtract(BigInteger.ONE), whole.bytes)
            if (cut.last >= cursor) cursor = cut.last.add(BigInteger.ONE)
        }
        if (cursor <= whole.last) pieces += Range(cursor, whole.last, whole.bytes)
        return pieces.flatMap { prefixes(it) }
    }

    /** The fewest prefixes that exactly cover [range]. */
    private fun prefixes(range: Range): List<TunConfiguration.Cidr> {
        val bits = range.bytes * 8
        val out = mutableListOf<TunConfiguration.Cidr>()
        var start = range.first
        while (start <= range.last) {
            // The largest block aligned at start that does not overrun the end.
            var size = if (start.signum() == 0) bits else start.lowestSetBit.coerceAtMost(bits)
            while (size > 0 && start.add(BigInteger.ONE.shiftLeft(size)).subtract(BigInteger.ONE) > range.last) size--
            out += TunConfiguration.Cidr(format(start, range.bytes), bits - size)
            start = start.add(BigInteger.ONE.shiftLeft(size))
        }
        return out
    }

    private fun format(
        value: BigInteger,
        bytes: Int,
    ): String {
        val raw = value.toByteArray()
        val fixed = ByteArray(bytes)
        val copy = minOf(raw.size, bytes)
        System.arraycopy(raw, raw.size - copy, fixed, bytes - copy, copy)
        return if (bytes == 4) {
            fixed.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            (0 until 8).joinToString(":") { group ->
                Integer.toHexString(((fixed[group * 2].toInt() and 0xFF) shl 8) or (fixed[group * 2 + 1].toInt() and 0xFF))
            }
        }
    }
}
