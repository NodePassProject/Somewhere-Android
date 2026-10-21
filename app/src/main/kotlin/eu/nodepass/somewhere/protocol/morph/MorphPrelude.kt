// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import java.security.SecureRandom

/**
 * How the 64 random bytes a Morph TCP connection opens with are distributed,
 * per `docs/protocol.md` § Morph, TCP encapsulation.
 *
 * The server consumes the prelude without interpreting it, so the policy is a
 * client-side choice that never has to agree with anything. Upstream's default
 * is [Fade] (`NOW_MORPH_TCP_PRELUDE=fade`), and that is what this client sends;
 * the other two are here because they are part of the specification and the
 * vectors for them are how [Fade]'s boundaries are known to be right.
 */
enum class MorphPrelude {
    /**
     * Printable ASCII, then 7-bit, then full 8-bit bytes.
     *
     * Two boundaries per connection, sampled uniformly: A in `[8,12]` and B in
     * `[16,32]`, both inclusive. Bytes `[0,A)` are printable (`0x20..0x7E`),
     * `[A,B)` have their high bit cleared, `[B,64)` are untouched.
     */
    Fade,

    /** All 64 bytes uniformly random. */
    Full8,

    /** All 64 bytes with the high bit cleared. */
    Low7,
    ;

    /**
     * Generates one prelude.
     *
     * @param fill fills its argument with secure random bytes. Injected so that
     *   the byte-for-byte procedure — including how many extra draws rejection
     *   sampling takes — can be checked against a scripted source.
     */
    fun generate(fill: (ByteArray) -> Unit = ::secureFill): ByteArray {
        val prelude = ByteArray(LENGTH).also(fill)
        when (this) {
            Fade -> {
                val boundaries = ByteArray(2).also(fill)
                val a = FADE_A_MIN + sampleBelow(boundaries[0], FADE_A_SPAN, fill)
                val b = FADE_B_MIN + sampleBelow(boundaries[1], FADE_B_SPAN, fill)
                for (index in 0 until a) {
                    prelude[index] = (PRINTABLE_FIRST + sampleBelow(prelude[index], PRINTABLE_SPAN, fill)).toByte()
                }
                for (index in a until b) prelude[index] = (prelude[index].toInt() and LOW7_MASK).toByte()
            }
            Full8 -> Unit
            Low7 -> for (index in prelude.indices) prelude[index] = (prelude[index].toInt() and LOW7_MASK).toByte()
        }
        return prelude
    }

    companion object {
        const val LENGTH: Int = 64

        private const val FADE_A_MIN = 8
        private const val FADE_A_SPAN = 5 // 8..12
        private const val FADE_B_MIN = 16
        private const val FADE_B_SPAN = 17 // 16..32
        private const val PRINTABLE_FIRST = 0x20
        private const val PRINTABLE_SPAN = 95 // 0x20..0x7E
        private const val LOW7_MASK = 0x7F

        private val random = SecureRandom()

        private fun secureFill(bytes: ByteArray) = random.nextBytes(bytes)

        /**
         * A uniform value in `[0, upper)` from one random byte, redrawing that
         * byte while it falls in the biased tail.
         *
         * `byte % upper` alone would favour small values whenever 256 is not a
         * multiple of `upper`. The redraw consumes exactly one byte per
         * rejection, in order, which is what upstream does — so a scripted
         * source yields the same prelude on both sides.
         */
        private fun sampleBelow(
            byte: Byte,
            upper: Int,
            fill: (ByteArray) -> Unit,
        ): Int {
            val limit = 256 - 256 % upper
            var value = byte.toInt() and 0xFF
            val one = ByteArray(1)
            while (value >= limit) {
                fill(one)
                value = one[0].toInt() and 0xFF
            }
            return value % upper
        }
    }
}
