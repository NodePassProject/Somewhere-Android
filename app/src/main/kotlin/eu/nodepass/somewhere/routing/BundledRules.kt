// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import java.io.IOException

/**
 * Rule sets that ship inside the APK.
 *
 * ## Two tiers (D-14, D-18)
 *
 * **Structural** — always on — and **policy**, which the user switches and
 * which only Rules mode consults. The policy tier was deferred in D-14 and
 * taken on 2026-10-24 by the requester for mainland China (D-18); what follows
 * is why the structural tier is still kept apart from it.
 *
 * ## The structural tier
 *
 * One set: the network-structural one. Loopback, the private ranges, CGNAT,
 * link-local, multicast, and the names that only ever mean this network. It is
 * what stops a tunnel from swallowing the router's admin page and the printer,
 * and it names no country and no service.
 *
 * That last part is the decision rather than an accident. A bundled set naming
 * either would be a political claim shipped under this project's name, in a
 * public history that cannot really be deleted, by a client whose stated
 * posture is mechanisms without embedded policy. The requester chose to ship
 * the smallest defensible set and keep the way open for more, so what is built
 * here is the room: an ordered list rather than one set, a provenance header
 * every asset must carry, and a precedence rule decided while it is still cheap
 * to change.
 *
 * ## Precedence, decided now
 *
 * **An imported set is consulted before any bundled one.** A user who imported
 * rules has said something specific; a bundled set is what this client thought
 * before being told. Deciding this with one bundled set and no policy tier is
 * cheap; discovering the question later, with two bundled sets and a user's
 * import in play, is not.
 */
object BundledRules {
    /** Which tier a set belongs to. See the class comment. */
    enum class Tier {
        /** Network arithmetic. Always consulted; also what "keep local networks out" applies in every mode. */
        Structural,

        /** Regions or services. Consulted in Rules mode only, and only when the user has it on. */
        Policy,
    }

    /** The mainland-China set, which is the one policy set there is. */
    const val MAINLAND_CHINA = "rules/policy/mainland-china.list"

    /** Where the assets live, and the order they are consulted in. */
    private val ASSETS =
        listOf(
            "rules/local-network.list" to Tier.Structural,
            MAINLAND_CHINA to Tier.Policy,
        )

    /** Every line a provenance header must carry, in the order they appear. */
    val REQUIRED_HEADERS = listOf("name", "source", "revision", "licence", "date")

    /**
     * One bundled set: what it is, where it came from, and what it holds.
     *
     * [loaded] is parsed the first time it is asked for, not when the set is
     * listed: the policy set is consulted only in Rules mode with its switch
     * on, and a device in the default mode should not pay to parse it.
     */
    class Set(
        val asset: String,
        val provenance: Map<String, String>,
        val tier: Tier,
        parse: () -> RuleStore.Loaded,
    ) {
        /** The rules. Parsed on first use, off the main thread — see the callers. */
        val loaded: RuleStore.Loaded by lazy(parse)

        val name: String get() = provenance["name"] ?: asset

        /** `NodePassProject/Somewhere-Android @ this repository`, for a screen. */
        val origin: String
            get() = listOfNotNull(provenance["source"], provenance["revision"]).joinToString(" @ ")
    }

    /** Reads an asset's `# key: value` header. Stops at the first blank line. */
    fun provenanceOf(text: String): Map<String, String> {
        val header = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) break
            if (!trimmed.startsWith("#")) break
            val body = trimmed.removePrefix("#").trim()
            val separator = body.indexOf(':')
            if (separator <= 0) continue
            val key = body.substring(0, separator).trim()
            if (key in REQUIRED_HEADERS && key !in header) {
                header[key] = body.substring(separator + 1).trim()
            }
        }
        return header
    }

    /**
     * Lists every bundled set, in order; each one's rules are parsed when
     * first used.
     *
     * A set whose header is incomplete is skipped, and one whose body does not
     * build loads as empty, rather than failing the app: either is a defect in
     * this repository, caught by a gate long before a device sees it, and
     * refusing to start over one would make a packaging mistake into an outage.
     */
    fun load(open: (String) -> String): List<Set> =
        ASSETS.mapNotNull { (asset, tier) ->
            val text =
                try {
                    open(asset)
                } catch (_: IOException) {
                    return@mapNotNull null
                }
            val provenance = provenanceOf(text)
            if (REQUIRED_HEADERS.any { it !in provenance }) return@mapNotNull null
            // Read again when parsed rather than held: 3.9 MB of text kept
            // alive in a process that may never consult it.
            Set(asset, provenance, tier) {
                val body = runCatching { open(asset) }.getOrDefault("")
                // The generated shape compiles in one pass; anything else goes
                // the ordinary way. Both are held to the same answers by
                // PolicyRulesTest.
                RoutingRules.compileTrusted(body)?.let { return@Set RuleStore.Loaded(it, it.size, emptyMap()) }
                val rules = RuleDocument.parseTrusted(body)
                val built = RoutingRules.of(rules).getOrDefault(RoutingRules.EMPTY)
                RuleStore.Loaded(built, if (built === RoutingRules.EMPTY) 0 else rules.size, emptyMap())
            }
        }

    @Volatile private var shared: List<Set>? = null

    /**
     * [load], once per process.
     *
     * The assets cannot change while the process lives, and the policy set is
     * 3.9 MB of text that takes the better part of a second to parse on a
     * desktop JVM. Parsing it on every connect, and again every time the
     * routing screen opened, was a cost paid for nothing.
     */
    fun shared(open: (String) -> String): List<Set> = shared ?: synchronized(this) { shared ?: load(open).also { shared = it } }
}
