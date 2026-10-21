// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol

/**
 * Constants the protocol fixes, rather than ones a deployment chooses.
 *
 * `docs/protocol.md` § Transport requirements: "Both transports use TLS 1.3 and
 * only ALPN `nw3`. Peers reject missing or other ALPN values. There is no
 * negotiation or fallback."
 *
 * v1 let a node name its own ALPN (`alpn=`, default `now/1`), and this client
 * read it from the URL so as to embed no value of its own. v3 took the choice
 * away: the ALPN is the protocol version now, and a Portal accepts exactly one.
 * It is transcribed here for the same reason the derivation labels are — it is
 * specification, not configuration.
 */
object Nw3 {
    const val ALPN: String = "nw3"

    /** The version the ALPN names, for diagnostics. */
    const val VERSION: Int = 3
}
