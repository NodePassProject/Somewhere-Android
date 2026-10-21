// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prelude policies, `docs/protocol.md` § Morph, TCP encapsulation.
 *
 * A Portal never interprets the prelude, so nothing on the other end can catch
 * a wrong distribution; these tests are the only check there is.
 */
class MorphPreludeTest {
    /** A deterministic source: a counter, so every draw is visible. */
    private fun counter(start: Int = 0): (ByteArray) -> Unit {
        var next = start
        return { bytes -> for (index in bytes.indices) bytes[index] = (next++).toByte() }
    }

    @Test
    fun everyPolicyIsSixtyFourBytes() {
        MorphPrelude.entries.forEach { assertEquals(it.name, 64, it.generate().size) }
    }

    @Test
    fun low7ClearsEveryHighBit() {
        repeat(50) {
            assertTrue(MorphPrelude.Low7.generate().all { it.toInt() and 0x80 == 0 })
        }
    }

    @Test
    fun full8LeavesTheSourceAlone() {
        val prelude = MorphPrelude.Full8.generate(counter())
        assertEquals((0 until 64).map { it.toByte() }, prelude.toList())
    }

    @Test
    fun fadeHasThreeBandsWithBoundariesInRange() {
        repeat(500) {
            val prelude = MorphPrelude.Fade.generate()
            // Bytes 0..7 are always printable, 32..63 always untouched; the
            // bands between move with A and B. What must never happen is a
            // non-printable byte before 8 or a high bit before 16.
            for (index in 0 until 8) {
                assertTrue("byte $index printable", prelude[index].toInt() and 0xFF in 0x20..0x7E)
            }
            for (index in 0 until 16) {
                assertTrue("byte $index has no high bit", prelude[index].toInt() and 0x80 == 0)
            }
        }
    }

    @Test
    fun fadeSamplesTheBoundariesAsUpstreamDoes() {
        // Counter source: prelude = 0..63, boundaries = 64, 65. Upstream:
        // A = 8 + 64 % 5 = 12, B = 16 + 65 % 17 = 30. The first 12 bytes are
        // 0x20 + (byte % 95), with no rejections (every byte < 190), bytes
        // 12..29 are masked to seven bits, the rest are untouched.
        val prelude = MorphPrelude.Fade.generate(counter())
        for (index in 0 until 12) assertEquals("printable $index", (0x20 + index % 95).toByte(), prelude[index])
        for (index in 12 until 30) assertEquals("low7 $index", (index and 0x7F).toByte(), prelude[index])
        for (index in 30 until 64) assertEquals("full8 $index", index.toByte(), prelude[index])
    }

    @Test
    fun rejectionSamplingRedrawsTheBiasedTail() {
        // A boundary byte of 255 is in the tail for 5 (limit 255) and is
        // redrawn from the next byte rather than reduced.
        var draw = 0
        val source: (ByteArray) -> Unit = { bytes ->
            when (draw++) {
                0 -> bytes.fill(0x41) // prelude: 'A', printable either way
                1 -> {
                    bytes[0] = 0xFF.toByte()
                    bytes[1] = 0
                } // A rejected, B = 16
                else -> bytes[0] = 3 // the redraw: A = 8 + 3 = 11
            }
        }
        val prelude = MorphPrelude.Fade.generate(source)
        assertEquals(3, draw)
        // 'A' is 0x41 = 65; 0x20 + 65 % 95 = 0x61 for the printable band.
        for (index in 0 until 11) assertEquals((0x20 + 0x41).toByte(), prelude[index])
        assertEquals(0x41.toByte(), prelude[11])
    }
}
