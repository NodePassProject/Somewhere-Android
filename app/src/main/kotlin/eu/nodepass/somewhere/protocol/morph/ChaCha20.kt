// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import java.io.IOException

/** The keystream of one key and nonce ran out; continuing would reuse it. */
class KeystreamExhaustedException : IOException("Morph ChaCha20 keystream exhausted")

/**
 * IETF ChaCha20 (RFC 8439 § 2.4) as a keystream: a 256-bit key, a 96-bit nonce,
 * and a 32-bit block counter starting at zero.
 *
 * ## Why this is written out rather than asked of a provider
 *
 * Morph needs the raw stream cipher — no Poly1305, no padding — continued
 * across arbitrarily many calls. The JDK has it as `ChaCha20`; Android's
 * platform provider does not promise it at every API level from 26; Conscrypt
 * has a version whose continuation across partial blocks is an implementation
 * detail rather than a contract. A mask that is wrong on one provider is a
 * connection that silently never completes a handshake, on some phones. Sixty
 * lines checked against the RFC's own vectors are the cheaper risk.
 *
 * ## Exhaustion
 *
 * The counter is 32 bits, so one key and nonce give `2^38` bytes. Upstream
 * stops at `2^38 - 64`, one block short, and this does the same: the stream
 * fails rather than wrapping the counter into keystream it has already used.
 *
 * Not thread-safe. One instance is one direction of one connection.
 */
class ChaCha20(
    key: ByteArray,
    nonce: ByteArray,
) {
    private val state = IntArray(STATE_WORDS)
    private val working = IntArray(STATE_WORDS)
    private val block = ByteArray(BLOCK_BYTES)
    private var blockOffset = BLOCK_BYTES
    private var produced = 0L

    init {
        require(key.size == KEY_BYTES) { "ChaCha20 keys are $KEY_BYTES bytes, got ${key.size}" }
        require(nonce.size == NONCE_BYTES) { "ChaCha20 nonces are $NONCE_BYTES bytes, got ${nonce.size}" }
        state[0] = SIGMA_0
        state[1] = SIGMA_1
        state[2] = SIGMA_2
        state[3] = SIGMA_3
        for (word in 0 until 8) state[4 + word] = littleEndian(key, word * 4)
        state[12] = 0
        for (word in 0 until 3) state[13 + word] = littleEndian(nonce, word * 4)
    }

    /** XORs the next [length] keystream bytes into [bytes] in place. */
    fun apply(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        if (length == 0) return
        if (STREAM_LIMIT - produced < length) throw KeystreamExhaustedException()
        for (index in offset until offset + length) {
            if (blockOffset == BLOCK_BYTES) nextBlock()
            bytes[index] = (bytes[index].toInt() xor block[blockOffset++].toInt()).toByte()
        }
        produced += length
    }

    private fun nextBlock() {
        state.copyInto(working)
        repeat(DOUBLE_ROUNDS) {
            quarterRound(0, 4, 8, 12)
            quarterRound(1, 5, 9, 13)
            quarterRound(2, 6, 10, 14)
            quarterRound(3, 7, 11, 15)
            quarterRound(0, 5, 10, 15)
            quarterRound(1, 6, 11, 12)
            quarterRound(2, 7, 8, 13)
            quarterRound(3, 4, 9, 14)
        }
        for (word in 0 until STATE_WORDS) {
            val value = working[word] + state[word]
            block[word * 4] = value.toByte()
            block[word * 4 + 1] = (value ushr 8).toByte()
            block[word * 4 + 2] = (value ushr 16).toByte()
            block[word * 4 + 3] = (value ushr 24).toByte()
        }
        state[12]++
        blockOffset = 0
    }

    private fun quarterRound(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) {
        val x = working
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    companion object {
        const val KEY_BYTES: Int = 32
        const val NONCE_BYTES: Int = 12
        const val BLOCK_BYTES: Int = 64

        /** `2^38 - 64`: every block a 32-bit counter can address, less one. */
        const val STREAM_LIMIT: Long = (1L shl 38) - BLOCK_BYTES

        private const val STATE_WORDS = 16
        private const val DOUBLE_ROUNDS = 10

        // "expand 32-byte k", as four little-endian words.
        private const val SIGMA_0 = 0x61707865
        private const val SIGMA_1 = 0x3320646e
        private const val SIGMA_2 = 0x79622d32
        private const val SIGMA_3 = 0x6b206574

        private fun littleEndian(
            bytes: ByteArray,
            at: Int,
        ): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}
