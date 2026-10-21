// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.protocol.DecodeReason

/**
 * A flow the Portal named a reason for refusing, whichever carrier carried it.
 *
 * Every carrier has its own failure vocabulary, and it should: a dedicated lane
 * can be used twice, a QUIC connection can be lost under its streams, and
 * neither of those means anything to the other. But **`SetupResult` is not the carrier's
 * word, it is the protocol's** — the same byte values with the same meanings
 * arrive over a TLS connection and over a QUIC stream. A caller
 * asking "what did the Portal say" is asking a question about the protocol, and
 * it should not have to know which carrier answered it in order to ask.
 *
 * This existed as two unrelated types until the oracle differential ran the
 * same case over two carriers: `dial_failed` on one reached the caller as a
 * named `DIAL_FAILED` and on the other as an unclassifiable failure carrying
 * the same words in a string. Nothing on the wire differed. What differed was
 * that one shape was recognised and the other was not — and the rejections the
 * app renders as distinct explanations are matched on exactly this, so the
 * second carrier would have quietly degraded all of them to a generic failure.
 *
 * Silence stays outside this interface. A Portal that answers nothing has not
 * named a `SetupResult`, and giving that case a result would mean inventing
 * one.
 */
interface FlowRejected : DecodeReason {
    /** What the Portal answered the flow's SYN with. */
    val result: SetupResult
}
