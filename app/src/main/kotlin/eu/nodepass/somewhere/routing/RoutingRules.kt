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
    private val suffixes: SuffixNode,
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
        val normalised = name.lowercase().trim('.')
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

    private fun suffixMatch(name: String): RouteAction? {
        var node: SuffixNode? = suffixes
        var deepest: RouteAction? = null
        for (label in name.split('.').asReversed()) {
            node = node?.children?.get(label) ?: return deepest
            node.action?.let { deepest = it }
        }
        return deepest
    }

    private fun keywordMatch(name: String): RouteAction? =
        keywords
            .filter { name.contains(it.value) }
            .maxByOrNull { it.value.length }
            ?.action

    internal class SuffixNode {
        val children = HashMap<String, SuffixNode>()
        var action: RouteAction? = null
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
            val suffixes = SuffixNode()
            val keywords = mutableListOf<Rule>()
            val ipv4 = CidrNode()
            val ipv6 = CidrNode()

            for (rule in rules) {
                when (rule.type) {
                    RuleType.DomainExact -> exact[rule.value.lowercase().trim('.')] = rule.action
                    RuleType.DomainSuffix -> insertSuffix(suffixes, rule)
                    RuleType.DomainKeyword -> keywords += rule.copy(value = rule.value.lowercase())
                    RuleType.IpCidr ->
                        insertCidr(ipv4, ipv6, rule)
                            ?: return Result.failure(IllegalArgumentException("not a CIDR block: ${rule.value}"))
                }
            }
            return Result.success(RoutingRules(exact, suffixes, keywords, ipv4, ipv6, rules.size))
        }

        private fun insertSuffix(
            root: SuffixNode,
            rule: Rule,
        ) {
            var node = root
            val labels =
                rule.value
                    .lowercase()
                    .trim('.')
                    .split('.')
                    .asReversed()
            for (label in labels) {
                node = node.children.getOrPut(label) { SuffixNode() }
            }
            node.action = rule.action
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
