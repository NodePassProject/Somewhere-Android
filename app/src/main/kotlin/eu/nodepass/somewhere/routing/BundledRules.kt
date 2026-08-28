// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import java.io.IOException

/**
 * Rule sets that ship inside the APK.
 *
 * ## What ships, and what deliberately does not (D-14)
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
    /** Where the assets live, and the order they are consulted in. */
    private val ASSETS = listOf("rules/local-network.list")

    /** Every line a provenance header must carry, in the order they appear. */
    val REQUIRED_HEADERS = listOf("name", "source", "revision", "licence", "date")

    /** One bundled set: what it is, where it came from, and what it holds. */
    data class Set(
        val asset: String,
        val provenance: Map<String, String>,
        val loaded: RuleStore.Loaded,
    ) {
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
     * Loads every bundled set, in order.
     *
     * A set whose header is incomplete or whose body does not parse is skipped
     * rather than failing the app: it is a defect in this repository, caught by
     * a gate long before a device sees it, and refusing to start over one would
     * make a packaging mistake into an outage.
     */
    fun load(open: (String) -> String): List<Set> =
        ASSETS.mapNotNull { asset ->
            val text =
                try {
                    open(asset)
                } catch (_: IOException) {
                    return@mapNotNull null
                }
            val provenance = provenanceOf(text)
            if (REQUIRED_HEADERS.any { it !in provenance }) return@mapNotNull null
            val parsed = RuleDocument.parse(text).getOrNull() ?: return@mapNotNull null
            val rules = RoutingRules.of(parsed.rules).getOrNull() ?: return@mapNotNull null
            Set(asset, provenance, RuleStore.Loaded(rules, parsed.rules.size, parsed.unsupported))
        }
}
