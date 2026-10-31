// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import java.io.ByteArrayOutputStream

/**
 * DNS over TCP's framing (RFC 1035 §4.2.2): each message preceded by its length
 * as two bytes.
 *
 * A device falls back to TCP when a UDP answer comes back truncated, and some
 * resolvers in the platform go straight to it. Those connections are addressed
 * to the resolver this tunnel announced, which exists nowhere but inside the
 * TUN — so they are answered here, by the same interceptor UDP queries reach,
 * rather than being sent to a Portal that has no such address.
 *
 * Bytes arrive in whatever pieces TCP delivers them; a query split across two
 * reads is ordinary. This keeps what it has not yet framed and hands back only
 * whole messages.
 */
class DnsOverTcp {
    private val pending = ByteArrayOutputStream()

    /** Adds [bytes] and returns every message now complete, in order. */
    fun feed(bytes: ByteArray): List<ByteArray> {
        pending.write(bytes)
        val buffer = pending.toByteArray()
        val messages = mutableListOf<ByteArray>()
        var at = 0
        while (buffer.size - at >= LENGTH_PREFIX) {
            val length = ((buffer[at].toInt() and 0xFF) shl 8) or (buffer[at + 1].toInt() and 0xFF)
            if (buffer.size - at - LENGTH_PREFIX < length) break
            messages += buffer.copyOfRange(at + LENGTH_PREFIX, at + LENGTH_PREFIX + length)
            at += LENGTH_PREFIX + length
        }
        pending.reset()
        pending.write(buffer, at, buffer.size - at)
        return messages
    }

    companion object {
        const val LENGTH_PREFIX = 2

        /** [message] with its length in front, ready to write to the stream. */
        fun frame(message: ByteArray): ByteArray {
            val out = ByteArray(LENGTH_PREFIX + message.size)
            out[0] = ((message.size shr 8) and 0xFF).toByte()
            out[1] = (message.size and 0xFF).toByte()
            message.copyInto(out, LENGTH_PREFIX)
            return out
        }
    }
}
