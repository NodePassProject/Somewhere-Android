// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

/**
 * A rule set as a document this app can read.
 *
 * ## The syntax, and why it is somebody else's
 *
 * ```text
 * # a comment
 * DOMAIN,exact.example.com,DIRECT
 * DOMAIN-SUFFIX,example.com,TUNNEL
 * DOMAIN-KEYWORD,ads,REJECT
 * IP-CIDR,10.0.0.0/8,DIRECT
 * ```
 *
 * This is the line syntax the Clash family uses, and it is deliberate: rule
 * lists are written by people who already have them, and inventing a format
 * would mean every one of those has to be converted by hand before it can be
 * tried. Nothing here reads a Clash *configuration* — that is a YAML document
 * with a great deal else in it, and the donor's libyaml was declined for
 * exactly that reason. This reads lines.
 *
 * **Where the rules come from is D-14 and is still open.** This is the path a
 * document travels regardless of who wrote it.
 *
 * ## Strict about syntax, honest about coverage
 *
 * A line that is not a rule fails the whole document, with its number: a
 * half-applied rule set sends traffic somewhere nobody chose, and "which half"
 * is not a question anyone can answer afterwards.
 *
 * A line that *is* a rule of a kind this client does not implement — `GEOIP`,
 * `PROCESS-NAME`, and the rest of the family — is counted and named in
 * [Parsed.unsupported] rather than failing the import or being dropped in
 * silence. Real lists contain them, and a client that quietly ignored a
 * `GEOIP` rule would be routing against a set the user believes is loaded.
 */
object RuleDocument {
    /** The outcome of reading a document: what was understood, and what was not. */
    data class Parsed(
        val rules: List<Rule>,
        /** Rule kinds this client does not implement, with how many of each. */
        val unsupported: Map<String, Int>,
    )

    private val types =
        mapOf(
            "DOMAIN" to RuleType.DomainExact,
            "DOMAIN-SUFFIX" to RuleType.DomainSuffix,
            "DOMAIN-KEYWORD" to RuleType.DomainKeyword,
            "IP-CIDR" to RuleType.IpCidr,
            "IP-CIDR6" to RuleType.IpCidr,
        )

    private val actions =
        mapOf(
            "DIRECT" to RouteAction.Direct,
            "TUNNEL" to RouteAction.Tunnel,
            // The Clash family's name for the same thing. Accepted on the way
            // in and never emitted, because this client has one proxy and
            // calling it "PROXY" would imply a choice of several.
            "PROXY" to RouteAction.Tunnel,
            "REJECT" to RouteAction.Reject,
        )

    /**
     * Rule kinds that exist and are not implemented here.
     *
     * Listed rather than inferred from "anything unknown", so that a typo is
     * still a syntax error rather than a silently unsupported rule.
     */
    private val known =
        setOf(
            "GEOIP",
            "GEOSITE",
            "IP-SUFFIX",
            "IP-ASN",
            "SRC-IP-CIDR",
            "SRC-PORT",
            "DST-PORT",
            "PROCESS-NAME",
            "PROCESS-PATH",
            "RULE-SET",
            "MATCH",
            "FINAL",
        )

    /**
     * Text this must never accept, whatever else is in it.
     *
     * A subscription URL and a node URL are credentials — the token is in the
     * query and the shared key is in the userinfo. Pasting one into a rule box
     * is an ordinary mistake, and a rule set that accepted it would write a
     * credential into a file that has no business holding one and that a
     * future "export my rules" would happily hand out.
     */
    private val credentialMarkers = listOf("nowhere://", "somewhere://", "anywhere://", "token=", "://")

    fun parse(text: String): Result<Parsed> {
        if (text.length > MAX_DOCUMENT_BYTES) {
            return Result.failure(IllegalArgumentException("a rule document of ${text.length} bytes is too large"))
        }

        val rules = mutableListOf<Rule>()
        val unsupported = mutableMapOf<String, Int>()

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            credentialMarkers.firstOrNull { line.contains(it, ignoreCase = true) }?.let { marker ->
                return Result.failure(
                    IllegalArgumentException(
                        "line ${index + 1} looks like a link rather than a rule ($marker); " +
                            "a subscription or node URL is a credential and does not belong in a rule set",
                    ),
                )
            }

            val fields = line.split(',').map(String::trim)
            val keyword = fields.firstOrNull()?.uppercase().orEmpty()

            if (keyword in known) {
                unsupported[keyword] = (unsupported[keyword] ?: 0) + 1
                return@forEachIndexed
            }
            val type =
                types[keyword]
                    ?: return Result.failure(IllegalArgumentException("line ${index + 1}: '$keyword' is not a rule kind"))
            if (fields.size < 3) {
                return Result.failure(IllegalArgumentException("line ${index + 1}: expected KIND,VALUE,ACTION"))
            }
            val value = fields[1]
            if (value.isEmpty()) {
                return Result.failure(IllegalArgumentException("line ${index + 1}: the value is empty"))
            }
            val action =
                actions[fields[2].uppercase()]
                    ?: return Result.failure(IllegalArgumentException("line ${index + 1}: '${fields[2]}' is not an action"))
            rules += Rule(type, value, action)
        }

        // Built here rather than by the caller so that a document which parses
        // line by line and still cannot become a rule set — a malformed CIDR,
        // more rules than the maximum — fails the import rather than the first
        // lookup after it.
        RoutingRules.of(rules).exceptionOrNull()?.let { return Result.failure(it) }
        return Result.success(Parsed(rules, unsupported))
    }

    /**
     * Ten megabytes of text, which is far past any real list.
     *
     * The donor's own set is 38,709 rules; this is room for several times that
     * and a bound on a file that arrived from somewhere unknown.
     */
    const val MAX_DOCUMENT_BYTES = 10 * 1024 * 1024
}
