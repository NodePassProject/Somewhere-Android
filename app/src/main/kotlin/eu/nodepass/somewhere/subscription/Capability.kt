// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

/**
 * A protocol capability this client advertises when fetching a subscription.
 *
 * The set is deliberately about what the client can *speak*, not about what it
 * happens to have enabled. A dashboard uses it to decide what it is safe to send
 * back — for example whether it may emit `mux=1` nodes at all.
 *
 * Tokens are lower-case and stable: they are part of a wire contract with
 * dashboards, so renaming one is a breaking change even though it is an enum
 * here.
 */
enum class Capability(
    val token: String,
) {
    /** TLS Mux carrier, `mux=1`. Nowhere 1.8 and later. */
    MUX("mux"),

    /** QUIC transport for either direction. */
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
