// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import java.io.Closeable

/**
 * One logical flow: a single connection to one target, from the caller's side.
 *
 * A flow is not a carrier. On a TLS connection the two are one-to-one; on a
 * QUIC connection many flows share one. Callers hold flows and never carriers,
 * so the same code works across both.
 */
interface Flow : Closeable {
    /**
     * A number for logs. Over QUIC it is the flow id the control stream derives,
     * which is the number a Portal's own log would show; over TLS the protocol
     * has no flow id at all, and this is a session-local sequence.
     */
    val id: UInt

    val target: Target

    val kind: FlowKind

    /** The Portal's answer, once it has arrived. Null until the setup byte is read. */
    val setupResult: SetupResult?

    val isOpen: Boolean

    fun write(bytes: ByteArray)

    fun flush()

    /** @return bytes read, or -1 at clean end of stream. */
    fun read(
        into: ByteArray,
        offset: Int = 0,
        length: Int = into.size - offset,
    ): Int
}
