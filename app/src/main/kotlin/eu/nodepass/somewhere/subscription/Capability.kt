// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

/**
 * A protocol capability this client advertises when fetching a subscription.
 *
 * The set is deliberately about what the client can *speak*, not about what it
 * happens to have enabled. A dashboard uses it to decide what it is safe to send
 * back — for example whether it may emit v3 share links rather than the
 * `up=…&down=…&mux=…` form a Nowhere 2.x dashboard produces.
 *
 * Tokens are lower-case and stable: they are part of a wire contract with
 * dashboards, so renaming one is a breaking change even though it is an enum
 * here.
 */
enum class Capability(
    val token: String,
) {
    /**
     * Nowhere v3: ALPN `nw3`, `net=tcp|udp`, Morph. A client advertising it
     * cannot speak anything older, so a dashboard that can still emit 2.x
     * links knows which to send.
     *
     * `mux` was advertised here until v3 removed the TLS Mux carrier; a
     * dashboard reading an old token list should treat its absence as "no".
     */
    NW3("nw3"),

    /** The QUIC carrier, `net=udp`. */
    QUIC("quic"),
    ;

    companion object {
        /**
         * Encodes a set for the `caps` parameter.
         *
         * Sorted rather than in declaration or iteration order, so that the same
         * set always produces the same string — otherwise the request URL varies
         * between runs and becomes useless as a cache key or in a test assertion.
         */
        fun encode(capabilities: Set<Capability>): String = capabilities.map { it.token }.sorted().joinToString(",")
    }
}
