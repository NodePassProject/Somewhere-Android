// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.morph.AuthenticatedPrelude.Fields
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Authenticated Prelude against upstream's fixed vectors.
 *
 * Transcribed from Nowhere v3.1.0 `src/tests/transport/morph/prelude.rs`:
 * `independent_fixed_tag_and_encoded_tail_match` and
 * `extreme_field_encodings_match_independent_vectors`. The encoder here was
 * written from `docs/prelude.md`; these say it agrees with the Portal's.
 */
class AuthenticatedPreludeTest {
    private val keys = MorphKeys.derive("0123456789abcdef0123456789abcdef".encodeToByteArray())

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Prefix `00..1f`, tail high bits `f00faa55`, low bits zero. */
    private fun fixturePrelude(): ByteArray {
        val prelude = ByteArray(64)
        for (index in 0 until 32) prelude[index] = index.toByte()
        val high = 0xf00faa55L
        for (index in 0 until 32) prelude[32 + index] = (((high shr (31 - index)) and 1) shl 7).toByte()
        return prelude
    }

    @Test
    fun theFixedVectorEncodesToUpstreamsTail() {
        val prelude = fixturePrelude()
        val nonce = hex("000102030405060708090a0b")
        val fields = Fields(identity = 0x0123456789abcdefL, counter = 1)

        AuthenticatedPrelude.encode(prelude, nonce, keys, fields)

        assertArrayEquals(
            hex("898f9c840e570537225e187fb7e4c990df489547d61b9e515bad2a8f4dd84d86"),
            prelude.copyOfRange(32, 64),
        )
        assertEquals(fields, AuthenticatedPrelude.decode(prelude, nonce, keys))
    }

    @Test
    fun theExtremeFieldVectorsMatch() {
        data class Case(
            val prefix: ByteArray,
            val nonce: ByteArray,
            val high: ByteArray,
            val identity: Long,
            val counter: Long,
            val tail: String,
        )
        val cases =
            listOf(
                Case(
                    ByteArray(32) { -1 },
                    ByteArray(12) { -1 },
                    ByteArray(4) { -1 },
                    -1L,
                    AuthenticatedPrelude.MAX_COUNTER,
                    "b2eac9c5c3fcb0e5a3f3a7c8febed9bca7aec3a1c4b0a3a5cf879c8edf8ffce8",
                ),
                Case(
                    ByteArray(32),
                    ByteArray(12),
                    ByteArray(4),
                    0,
                    0,
                    "245b016f7d7e2b4c125f3e33445f0d2a103f225860664d0c7465093120367212",
                ),
                Case(
                    ByteArray(32) { (31 - it).toByte() },
                    ByteArray(12) { (11 - it).toByte() },
                    hex("80000001"),
                    -0x7fffffffffffffffL, // 0x8000_0000_0000_0001
                    0x800000000001L,
                    "b57508160f6c2d312c50315572780903206a301662675c472f676d701b032ab8",
                ),
            )
        for (case in cases) {
            val prelude = ByteArray(64)
            case.prefix.copyInto(prelude)
            for (index in 0 until 32) {
                val bit = (case.high[index / 8].toInt() shr (7 - index % 8)) and 1
                prelude[32 + index] = (bit shl 7).toByte()
            }
            val fields = Fields(case.identity, case.counter)
            AuthenticatedPrelude.encode(prelude, case.nonce, keys, fields)
            assertArrayEquals("tail for identity ${case.identity}", hex(case.tail), prelude.copyOfRange(32, 64))
            assertEquals(fields, AuthenticatedPrelude.decode(prelude, case.nonce, keys))
        }
    }

    @Test
    fun theSharedFixtureAgrees() {
        // The same four cases `verify-vectors.py` recomputes, read from the
        // fixture file both suites share, so the two cannot drift apart.
        val family = VectorFixture.family("morphPrelude")
        val fixtureKeys = MorphKeys.derive(family.str("sharedKeyUtf8").encodeToByteArray())
        for (case in VectorFixture.cases("morphPrelude")) {
            val nonce = case.str("nonceHex").hexToByteArrayCompat()
            val high = case.str("highBitsHex").hexToByteArrayCompat()
            val prelude = ByteArray(64)
            case.str("prefixHex").hexToByteArrayCompat().copyInto(prelude)
            for (index in 0 until 32) {
                prelude[32 + index] = ((((high[index / 8].toInt() shr (7 - index % 8)) and 1)) shl 7).toByte()
            }
            val fields = Fields(case.str("identityHex").toULong(16).toLong(), case.str("counterHex").toLong(16))
            AuthenticatedPrelude.encode(prelude, nonce, fixtureKeys, fields)
            assertEquals(case.str("name"), case.str("tailHex"), prelude.copyOfRange(32, 64).toHex())
            assertEquals(case.str("name"), fields, AuthenticatedPrelude.decode(prelude, nonce, fixtureKeys))
        }
    }

    @Test
    fun encodingKeepsThePolicysPrefixAndEveryHighBit() {
        // A receiver may judge the prelude's distribution, so the policy's
        // look must survive: only the tail's low seven bits are ours.
        repeat(64) {
            val generated = MorphPrelude.Fade.generate()
            val encoded = generated.copyOf()
            AuthenticatedPrelude.encode(encoded, ByteArray(12) { it.toByte() }, keys, Fields(42, it + 1L))
            assertArrayEquals(generated.copyOf(32), encoded.copyOf(32))
            for (index in 32 until 64) {
                assertEquals(generated[index].toInt() and 0x80, encoded[index].toInt() and 0x80)
            }
        }
    }

    @Test
    fun anyChangedBitFailsTheTag() {
        val prelude = fixturePrelude()
        val nonce = hex("000102030405060708090a0b")
        AuthenticatedPrelude.encode(prelude, nonce, keys, Fields(7, 9))
        for (bit in 0 until 64 * 8) {
            val flipped = prelude.copyOf()
            flipped[bit / 8] = (flipped[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            assertNull("bit $bit", AuthenticatedPrelude.decode(flipped, nonce, keys))
        }
        val otherNonce = nonce.copyOf().also { it[0] = 1 }
        assertNull(AuthenticatedPrelude.decode(prelude, otherNonce, keys))
    }

    @Test
    fun countersStartAtOneAndConcurrentCallersNeverShareOne() {
        val allocator = AuthenticatedPrelude.Allocator { 0x1234L }
        assertEquals(Fields(0x1234L, 1), allocator.allocate())

        val seen = Collections.synchronizedSet(HashSet<Long>())
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { pool.execute { repeat(1_000) { seen += allocator.allocate().counter } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(8_000, seen.size)
        assertEquals((2L..8_001L).toSet(), seen)
    }

    @Test
    fun exhaustionIsPermanentAndNeverWraps() {
        val allocator = AuthenticatedPrelude.Allocator.startingAfter(identity = 5, previous = AuthenticatedPrelude.MAX_COUNTER - 1)
        assertEquals(AuthenticatedPrelude.MAX_COUNTER, allocator.allocate().counter)
        repeat(3) { assertTrue(runCatching { allocator.allocate() }.exceptionOrNull() is IOException) }
    }

    @Test
    fun eachPortalsKeyHasItsOwnIdentity() {
        val one = AuthenticatedPrelude.allocatorFor(keys)
        val again = AuthenticatedPrelude.allocatorFor(MorphKeys.derive("0123456789abcdef0123456789abcdef".encodeToByteArray()))
        val other = AuthenticatedPrelude.allocatorFor(MorphKeys.derive("ffffffffffffffffffffffffffffffff".encodeToByteArray()))
        assertTrue("one key, one allocator", one === again)
        assertNotEquals(one.allocate().identity, other.allocate().identity)
    }
}
