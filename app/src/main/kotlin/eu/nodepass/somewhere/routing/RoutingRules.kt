// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

/** Where a flow goes. */
enum class RouteAction {
    /** Through the Portal. */
    Tunnel,

    /** Straight out of the device, on a protected socket. */
    Direct,

    /** Not at all, with a reason. */
    Reject,
}

/** How a rule matches. */
enum class RuleType {
    /** The whole name, exactly. */
    DomainExact,

    /** The name or any label-aligned suffix of it. */
    DomainSuffix,

    /** Any name containing this text. Consulted only when no name rule matched. */
    DomainKeyword,

    /** An address inside this CIDR block, v4 or v6. */
    IpCidr,
}

/** One rule, as written down. */
data class Rule(
    val type: RuleType,
    val value: String,
    val action: RouteAction,
)

/**
 * Every rule, arranged so that a lookup costs what a lookup should.
 *
 * Reimplemented against the donor's `DomainRouter`, which is the blueprint for
 * the shape rather than the source of the code — the same relationship this
 * repository has with the donor's lwIP layer.
 *
 * ## The three name tiers, and why they are ordered
 *
 * **Exact** beats everything: somebody who wrote a whole name meant that name.
 *
 * **Suffix** is a reverse-label trie. `www.example.com` is inserted as
 * `com`, `example`, `www`, and a lookup walks the query from the top-level
 * label inward, remembering the deepest action it passed. That makes the match
 * label-aligned by construction, which is the property a substring search does
 * not have: `example.com` must not match `notexample.com`, and a trie cannot
 * make that mistake because `notexample` is a different label from `example`.
 *
 * **Keyword** is a substring search and is consulted only when no name rule
 * matched, because it is the tier that cannot be reasoned about. Within it the
 * longer pattern wins, so a more specific rule is not shadowed by a shorter one
 * that happens to have been loaded first.
 *
 * ## Addresses
 *
 * Two binary tries, one per family, walked bit by bit — 32 steps for v4 and
 * 128 for v6, whatever the rule count. Longest prefix wins, which is what a
 * routing table means by "most specific".
 *
 * ## Bounded
 *
 * [MAX_RULES] is refused rather than truncated. A rule set that silently lost
 * its tail would send traffic somewhere the user did not ask for, and would do
 * it quietly.
 */
class RoutingRules private constructor(
    private val exact: Map<String, RouteAction>,
    private val suffixes: SuffixTable,
    private val keywords: List<Rule>,
    private val ipv4: CidrNode,
    private val ipv6: CidrNode,
    val size: Int,
) {
    /**
     * The action for [name], or null when no rule mentions it.
     *
     * Null rather than a default: what to do with a name nothing matched is the
     * caller's policy, and burying it here would make the two callers — the
     * resolver and the flow handler — able to disagree about it.
     */
    fun decide(name: String): RouteAction? {
        val normalised = asciiName(name)
        if (normalised.isEmpty()) return null
        exact[normalised]?.let { return it }
        suffixMatch(normalised)?.let { return it }
        return keywordMatch(normalised)
    }

    /** The action for an address, or null. Four or sixteen bytes. */
    fun decide(address: ByteArray): RouteAction? =
        when (address.size) {
            4 -> ipv4.lookup(address)
            16 -> ipv6.lookup(address)
            else -> null
        }

    /** The longest listed suffix of [name] that ends on a label boundary, longest first. */
    private fun suffixMatch(name: String): RouteAction? {
        var from = 0
        while (true) {
            suffixes.lookup(name, from)?.let { return it }
            val dot = name.indexOf('.', from)
            if (dot < 0) return null
            from = dot + 1
        }
    }

    private fun keywordMatch(name: String): RouteAction? =
        keywords
            .filter { name.contains(it.value) }
            .maxByOrNull { it.value.length }
            ?.action

    /**
     * Domain suffixes, packed: one byte array of every suffix in sorted order,
     * their offsets, and one action each.
     *
     * A tree of `HashMap`s, one per label, was what this was, and it held the
     * bundled mainland-China set — 109,493 suffixes — in about 31 MiB of heap
     * inside the VPN process. Packed it is under 2 MiB, and a lookup is one
     * binary search per label boundary of the name, so a five-label name costs
     * about eighty byte comparisons.
     */
    internal class SuffixTable internal constructor(
        private val bytes: ByteArray,
        private val offsets: IntArray,
        private val actions: Array<RouteAction>,
    ) {
        val size: Int get() = actions.size

        /** The action for exactly `name.substring(from)`, or null. */
        fun lookup(
            name: String,
            from: Int,
        ): RouteAction? {
            var low = 0
            var high = actions.size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val order = compare(middle, name, from)
                when {
                    order < 0 -> low = middle + 1
                    order > 0 -> high = middle - 1
                    else -> return actions[middle]
                }
            }
            return null
        }

        /**
         * Entry [index] against `name.substring(from)`, as unsigned values.
         *
         * Names are ASCII by now ([asciiName]). One the IDN conversion refused
         * is compared by its full char values, so it can never equal an entry.
         */
        private fun compare(
            index: Int,
            name: String,
            from: Int,
        ): Int {
            val start = offsets[index]
            val end = offsets[index + 1]
            val length = name.length - from
            val shared = minOf(end - start, length)
            for (i in 0 until shared) {
                val a = bytes[start + i].toInt() and 0xFF
                val b = name[from + i].code
                if (a != b) return a - b
            }
            return (end - start) - length
        }

        companion object {
            /**
             * Packs [keys] with their [actions]; on a repeated key the later
             * entry wins, as it did when each one overwrote a tree node.
             *
             * Sorted as strings, which for ASCII is the byte order [compare]
             * searches in. The bundled set is generated already in that order,
             * so the sort is one pass over input it finds sorted; nothing here
             * builds a map, or a byte array per key, or a string per entry to
             * look an action back up — which, at 109,000 suffixes, was most of
             * the load time on a phone.
             */
            fun of(
                keys: List<String>,
                actions: List<RouteAction>,
            ): SuffixTable {
                // Only ASCII keys are packed: anything else could never compare
                // equal to an ASCII name, and asciiName makes every name ASCII
                // that can be.
                val ascii = keys.indices.filter { index -> keys[index].all { it.code < 0x80 } }
                // Already in order — strictly or with repeats — is the bundled
                // case, and one linear check spares a boxed sort of 109,000.
                val sorted = (1 until ascii.size).all { keys[ascii[it - 1]] <= keys[ascii[it]] }
                val order = if (sorted) ascii else ascii.sortedWith(compareBy<Int> { keys[it] }.thenBy { it })
                // Of each run of equal keys, the last index — the later rule.
                val kept = ArrayList<Int>(order.size)
                for ((position, index) in order.withIndex()) {
                    val next = order.getOrNull(position + 1)
                    if (next == null || keys[next] != keys[index]) kept += index
                }
                val offsets = IntArray(kept.size + 1)
                kept.forEachIndexed { at, index -> offsets[at + 1] = offsets[at] + keys[index].length }
                val bytes = ByteArray(offsets[kept.size])
                kept.forEachIndexed { at, index ->
                    val key = keys[index]
                    for (i in key.indices) bytes[offsets[at] + i] = key[i].code.toByte()
                }
                return SuffixTable(bytes, offsets, Array(kept.size) { actions[kept[it]] })
            }
        }
    }

    internal class CidrNode {
        val children = arrayOfNulls<CidrNode>(2)
        var action: RouteAction? = null

        fun lookup(address: ByteArray): RouteAction? {
            var node: CidrNode? = this
            var deepest: RouteAction? = action
            for (index in 0 until address.size * 8) {
                val bit = (address[index / 8].toInt() shr (7 - index % 8)) and 1
                node = node?.children?.get(bit) ?: return deepest
                node.action?.let { deepest = it }
            }
            return deepest
        }
    }

    companion object {
        /**
         * The most rules that will be accepted.
         *
         * Chosen against the donor's own set, which is 38,709 entries, with
         * room for a set several times larger. Past it the load is refused,
         * because a rule set quietly missing its tail routes traffic somewhere
         * nobody asked for.
         */
        const val MAX_RULES = 250_000

        /**
         * A name as the wire carries it: lower case, no trailing dot, and an
         * internationalised name in its `xn--` form.
         *
         * The same conversion on both sides — the rule and the name asked about
         * — so that `中国` in a rule file and `xn--fiqs8s` in a DNS query are
         * the same suffix, and so that the packed suffix table only ever holds
         * and compares ASCII. A name the conversion refuses — a label over 63
         * characters, a prohibited code point — is kept as typed: no DNS name
         * can be spelled that way, so as a rule it matches nothing.
         */
        internal fun asciiName(name: String): String {
            val trimmed = name.trim().trim('.')
            if (trimmed.all { it.code < 0x80 }) return trimmed.lowercase()
            return runCatching { java.net.IDN.toASCII(trimmed, java.net.IDN.ALLOW_UNASSIGNED) }
                .getOrDefault(trimmed)
                .lowercase()
        }

        /** An empty set. Every lookup returns null, and every caller decides. */
        val EMPTY = of(emptyList()).getOrThrow()

        /**
         * Builds a set, or fails with a stated reason.
         *
         * Later rules win on conflict, which is the ordering the sources are
         * loaded in: a user's own rule is loaded after a bundled one and is
         * meant to override it.
         */
        fun of(rules: List<Rule>): Result<RoutingRules> {
            if (rules.size > MAX_RULES) {
                return Result.failure(
                    IllegalArgumentException("a rule set of ${rules.size} exceeds the maximum of $MAX_RULES"),
                )
            }
            val exact = HashMap<String, RouteAction>()
            val suffixKeys = ArrayList<String>()
            val suffixActions = ArrayList<RouteAction>()
            val keywords = mutableListOf<Rule>()
            val ipv4 = CidrNode()
            val ipv6 = CidrNode()

            for (rule in rules) {
                when (rule.type) {
                    RuleType.DomainExact -> exact[asciiName(rule.value)] = rule.action
                    RuleType.DomainSuffix ->
                        asciiName(rule.value)
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                suffixKeys += it
                                suffixActions += rule.action
                            }
                    RuleType.DomainKeyword -> keywords += rule.copy(value = rule.value.lowercase())
                    RuleType.IpCidr ->
                        insertCidr(ipv4, ipv6, rule)
                            ?: return Result.failure(IllegalArgumentException("not a CIDR block: ${rule.value}"))
                }
            }
            return Result.success(RoutingRules(exact, SuffixTable.of(suffixKeys, suffixActions), keywords, ipv4, ipv6, rules.size))
        }

        /**
         * Builds a set straight from a bundled document's text, or returns
         * null when the text is not in the shape this needs.
         *
         * The shape is what `tools/rules/generate-mainland-china.py` writes:
         * DOMAIN-SUFFIX lines in strictly ascending order, lower-case ASCII,
         * then IP-CIDR and IP-CIDR6 lines, every action DIRECT. Under those
         * conditions the suffix table *is* the domain column of the text, so it
         * is copied into the packed bytes in one pass, with no string, rule or
         * map per line. On an API 35 emulator that took the 117,000-rule set
         * from 1.1 s to the figure `BundledRulesOnDeviceTest` logs. Anything
         * else — a line out of order, a capital, another kind — returns null,
         * and the caller takes the ordinary path, which reads anything.
         */
        internal fun compileTrusted(text: String): RoutingRules? {
            val prefix = "DOMAIN-SUFFIX,"
            val direct = ",DIRECT"
            var bytes = ByteArray(text.length / 2)
            var offsets = IntArray(text.length / 24 + 2)
            var count = 0
            var used = 0
            val cidrs = ArrayList<Rule>()
            var previousStart = -1
            var previousEnd = -1
            var start = 0
            while (start < text.length) {
                var end = text.indexOf('\n', start)
                if (end < 0) end = text.length
                if (end > start && text[start] != '#') {
                    if (end - start <= direct.length || !text.regionMatches(end - direct.length, direct, 0, direct.length)) return null
                    val valueEnd = end - direct.length
                    if (text.startsWith(prefix, start)) {
                        if (cidrs.isNotEmpty()) return null
                        val valueStart = start + prefix.length
                        if (valueEnd <= valueStart) return null
                        for (i in valueStart until valueEnd) {
                            val c = text[i]
                            if (!(c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_')) return null
                        }
                        if (previousStart >= 0 && compareRegions(text, previousStart, previousEnd, valueStart, valueEnd) >= 0) return null
                        val length = valueEnd - valueStart
                        if (used + length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, used + length))
                        if (count + 2 > offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                        for (i in 0 until length) bytes[used + i] = text[valueStart + i].code.toByte()
                        used += length
                        count++
                        offsets[count] = used
                        previousStart = valueStart
                        previousEnd = valueEnd
                    } else if (text.startsWith("IP-CIDR,", start) || text.startsWith("IP-CIDR6,", start)) {
                        cidrs += Rule(RuleType.IpCidr, text.substring(text.indexOf(',', start) + 1, valueEnd), RouteAction.Direct)
                    } else {
                        return null
                    }
                }
                start = end + 1
            }
            val ipv4 = CidrNode()
            val ipv6 = CidrNode()
            for (rule in cidrs) insertCidr(ipv4, ipv6, rule) ?: return null
            val table = SuffixTable(bytes.copyOf(used), offsets.copyOf(count + 1), Array(count) { RouteAction.Direct })
            return RoutingRules(HashMap(), table, emptyList(), ipv4, ipv6, count + cidrs.size)
        }

        private fun compareRegions(
            text: String,
            aStart: Int,
            aEnd: Int,
            bStart: Int,
            bEnd: Int,
        ): Int {
            val shared = minOf(aEnd - aStart, bEnd - bStart)
            for (i in 0 until shared) {
                val order = text[aStart + i] - text[bStart + i]
                if (order != 0) return order
            }
            return (aEnd - aStart) - (bEnd - bStart)
        }

        private fun insertCidr(
            ipv4: CidrNode,
            ipv6: CidrNode,
            rule: Rule,
        ): Unit? {
            val slash = rule.value.indexOf('/')
            if (slash < 0) return null
            val address = parseAddress(rule.value.take(slash)) ?: return null
            val prefix = rule.value.substring(slash + 1).toIntOrNull() ?: return null
            if (prefix < 0 || prefix > address.size * 8) return null

            var node = if (address.size == 4) ipv4 else ipv6
            for (index in 0 until prefix) {
                val bit = (address[index / 8].toInt() shr (7 - index % 8)) and 1
                node = node.children[bit] ?: CidrNode().also { node.children[bit] = it }
            }
            node.action = rule.action
            return Unit
        }

        /** Dotted quad or an IPv6 literal, as bytes. Null when it is neither. */
        internal fun parseAddress(text: String): ByteArray? {
            if (text.contains(':')) return parseIpv6(text)
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((index, part) in parts.withIndex()) {
                val value = part.toIntOrNull() ?: return null
                if (value < 0 || value > 255) return null
                // "01" and "1" are the same number and different text; a
                // leading zero is how an address is smuggled past a filter.
                if (part.length > 1 && part[0] == '0') return null
                bytes[index] = value.toByte()
            }
            return bytes
        }

        private fun parseIpv6(text: String): ByteArray? {
            val doubleColon = text.indexOf("::")
            val bytes = ByteArray(16)

            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                return part.split(':').map { group ->
                    if (group.isEmpty() || group.length > 4) return null
                    group.toIntOrNull(16) ?: return null
                }
            }
            val head: List<Int>
            val tail: List<Int>
            if (doubleColon >= 0) {
                head = groups(text.take(doubleColon)) ?: return null
                tail = groups(text.substring(doubleColon + 2)) ?: return null
                if (head.size + tail.size > 8) return null
            } else {
                head = groups(text) ?: return null
                tail = emptyList()
                if (head.size != 8) return null
            }
            for ((index, group) in head.withIndex()) {
                bytes[index * 2] = (group shr 8).toByte()
                bytes[index * 2 + 1] = group.toByte()
            }
            for ((index, group) in tail.withIndex()) {
                val position = 16 - (tail.size - index) * 2
                bytes[position] = (group shr 8).toByte()
                bytes[position + 1] = group.toByte()
            }
            return bytes
        }
    }
}
