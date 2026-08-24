// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import java.security.SecureRandom

/**
 * The 16 random bytes identifying one client session. NW-P-02.
 *
 * One per session, shared by every physical carrier under it — which is what
 * lets a Portal pair the two halves of a split flow that arrived on different
 * connections.
 *
 * Generated from [SecureRandom]: a predictable session id would let someone
 * else's traffic be paired with yours.
 */
@JvmInline
value class SessionId private constructor(
    private val bytes: ByteArray,
) {
    fun toByteArray(): ByteArray = bytes.copyOf()

    /** First four bytes, for logs. Never the whole value. */
    fun shortForm(): String = bytes.take(4).joinToString("") { "%02x".format(it) }

    override fun toString(): String = "SessionId(${shortForm()}…)"

    companion object {
        const val LENGTH: Int = 16

        private val random = SecureRandom()

        fun random(): SessionId = SessionId(ByteArray(LENGTH).also(random::nextBytes))

        /** For tests and for reconstructing a session id received from a peer. */
        fun of(bytes: ByteArray): SessionId {
            require(bytes.size == LENGTH) { "session id must be $LENGTH bytes, got ${bytes.size}" }
            return SessionId(bytes.copyOf())
        }
    }
}
