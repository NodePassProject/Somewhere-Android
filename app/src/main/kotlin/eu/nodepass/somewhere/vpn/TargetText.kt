// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.protocol.target.Target

/**
 * Destinations and names as the screens and logs may show them.
 *
 * One place, because every surface that lists traffic — the Portal's answers,
 * the live connections, the DNS queries — is copied into issues, and a value
 * that arrived from a peer must reach none of them carrying anything a host
 * name cannot. `ConnectionLogTest` fuzzes credential-shaped values through it.
 */
object TargetText {
    /**
     * A target as `host:port`, and nothing else.
     *
     * A domain target already is one. An address target is rendered from its
     * octets rather than from anything a peer supplied, so a name that arrived
     * carrying punctuation cannot reach a log line at all.
     */
    fun describe(target: Target): String =
        when (target) {
            is Target.Domain -> "${sanitise(target.host)}:${target.port}"
            is Target.Ip ->
                if (target.octets.size == 4) {
                    target.octets.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":${target.port}"
                } else {
                    target.octets.joinToString(":") { "%02x".format(it) } + " port ${target.port}"
                }
        }

    /**
     * Strips what a log line must not carry.
     *
     * A hostname cannot legally contain any of this, so removing it costs a
     * correct name nothing — and a value that arrived from a peer carrying a
     * newline, a userinfo separator or a percent-encoded byte is precisely the
     * thing that must not reach a line someone copies.
     */
    fun sanitise(
        value: String,
        /**
         * Whether `_` survives. A host name cannot contain one; a DNS query
         * name can (`_dns-sd._udp`, every SRV record), and the query log
         * would otherwise show names nobody asked for.
         */
        underscore: Boolean = false,
    ): String =
        value
            .asSequence()
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || (underscore && it == '_') }
            .take(MAX_HOST_LENGTH)
            .joinToString("")

    private const val MAX_HOST_LENGTH = 253
}
