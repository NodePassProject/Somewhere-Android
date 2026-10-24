// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Authenticated Prelude, per Nowhere `docs/prelude.md` (v3.1.0).
 *
 * A Nowhere-specific extension, not part of the Core specification: it carries
 * an Identity, a Counter and a 14-byte Tag in the low seven bits of the Morph
 * TCP prelude's last 32 bytes, so that a Portal can refuse a replayed
 * bootstrap before it sends any TLS. The first 32 bytes and every high bit keep
 * whatever the prelude policy generated.
 *
 * ```text
 * input   = N(12) || P(32) || H(4) || I(8) || C(6)      P = prelude[0..32]
 *                                                       H = high bits of prelude[32..64]
 * Tag     = HMAC-SHA256(auth_key, input)[0..14]
 * Mask    = HMAC-SHA256(mask_key, N || P || H)[0..28]
 * Payload = (I || C || Tag) XOR Mask, written 7 bits per tail byte, MSB first
 * ```
 *
 * **It needs nothing from the Portal and costs no round trip.** A Portal that
 * predates it consumes the 64 bytes without reading them, and a 3.1 Portal
 * that finds a Tag it cannot match takes the ordinary path — so sending it is
 * safe against every v3 Portal, which is why it is always sent.
 */
object AuthenticatedPrelude {
    /** Counters run `1..2^48-1` and never wrap. */
    const val MAX_COUNTER: Long = (1L shl 48) - 1

    private const val PREFIX = 32
    private const val HEADER = 48 // nonce 12 + prefix 32 + high bits 4
    private const val PAYLOAD = 28 // identity 8 + counter 6 + tag 14
    private const val TAG = 14

    /** What a prelude carries once encoded. */
    data class Fields(
        val identity: Long,
        val counter: Long,
    )

    /**
     * Writes [fields] into [prelude]'s tail in place.
     *
     * [prelude] must already hold the policy's bytes: the prefix and every high
     * bit are inputs, and only the tail's low seven bits change.
     */
    fun encode(
        prelude: ByteArray,
        nonce: ByteArray,
        keys: MorphKeys,
        fields: Fields,
    ) {
        require(prelude.size == MorphPrelude.LENGTH && nonce.size == ChaCha20.NONCE_BYTES)
        val header = header(prelude, nonce)
        val metadata = metadata(fields)
        val tag = MorphKeys.hmac(keys.preludeAuthKey(), header + metadata)
        val mask = MorphKeys.hmac(keys.preludeMaskKey(), header)
        val payload = ByteArray(PAYLOAD) { index -> if (index < metadata.size) metadata[index] else tag[index - metadata.size] }
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index].toInt()).toByte()

        var accumulator = 0
        var bits = 0
        var out = PREFIX
        for (byte in payload) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 7) {
                bits -= 7
                prelude[out] = ((prelude[out].toInt() and 0x80) or ((accumulator shr bits) and 0x7F)).toByte()
                out++
            }
            accumulator = accumulator and ((1 shl bits) - 1)
        }
    }

    /**
     * The fields [prelude] carries, or null when its Tag does not match.
     *
     * The Portal's half. This client never receives a prelude; it is here so
     * that the fixtures can be checked in both directions.
     */
    fun decode(
        prelude: ByteArray,
        nonce: ByteArray,
        keys: MorphKeys,
    ): Fields? {
        val header = header(prelude, nonce)
        val mask = MorphKeys.hmac(keys.preludeMaskKey(), header)
        val payload = ByteArray(PAYLOAD)
        var accumulator = 0
        var bits = 0
        var out = 0
        for (index in PREFIX until MorphPrelude.LENGTH) {
            accumulator = (accumulator shl 7) or (prelude[index].toInt() and 0x7F)
            bits += 7
            if (bits >= 8) {
                bits -= 8
                payload[out++] = (accumulator shr bits).toByte()
                accumulator = accumulator and ((1 shl bits) - 1)
            }
        }
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index].toInt()).toByte()
        val metadata = payload.copyOf(PAYLOAD - TAG)
        val expected = MorphKeys.hmac(keys.preludeAuthKey(), header + metadata).copyOf(TAG)
        if (!java.security.MessageDigest.isEqual(expected, payload.copyOfRange(PAYLOAD - TAG, PAYLOAD))) return null
        val identity = metadata.copyOf(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
        val counter = metadata.copyOfRange(8, 14).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
        return Fields(identity, counter)
    }

    /** `N || P || H`: the nonce, the prefix, and the tail's 32 high bits packed MSB first. */
    private fun header(
        prelude: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val header = ByteArray(HEADER)
        nonce.copyInto(header, 0)
        prelude.copyInto(header, nonce.size, 0, PREFIX)
        for (index in 0 until MorphPrelude.LENGTH - PREFIX) {
            val high = (prelude[PREFIX + index].toInt() shr 7) and 1
            val at = 44 + index / 8
            header[at] = (header[at].toInt() or (high shl (7 - index % 8))).toByte()
        }
        return header
    }

    /** `I(8) || C(6)`, big-endian. */
    private fun metadata(fields: Fields): ByteArray {
        val bytes = ByteArray(PAYLOAD - TAG)
        for (index in 0 until 8) bytes[index] = (fields.identity ushr (56 - 8 * index)).toByte()
        for (index in 0 until 6) bytes[8 + index] = (fields.counter ushr (40 - 8 * index)).toByte()
        return bytes
    }

    /**
     * One Identity and its Counter.
     *
     * The specification gives each Vector instance one Identity, generated on
     * its first Morph TCP connection and never persisted. A Vector talks to one
     * Portal; this client talks to many, and one Identity shared across them
     * would let two Portals that compared notes recognise the same device. So
     * an Identity here belongs to a shared key — a Portal — for the life of
     * the process, which is the same thing from any one Portal's side.
     */
    class Allocator internal constructor(
        private val random: () -> Long = { secure.nextLong() },
    ) {
        private val identity: Long by lazy(random)
        private val counter = AtomicLong(0)

        /**
         * The next unused Counter, allocated immediately before encoding.
         *
         * A value is spent whether or not its connection succeeds, and
         * concurrent callers never share one.
         */
        fun allocate(): Fields {
            while (true) {
                val previous = counter.get()
                if (previous >= MAX_COUNTER) throw IOException("Morph prelude counter exhausted")
                if (counter.compareAndSet(previous, previous + 1)) return Fields(identity, previous + 1)
            }
        }

        internal companion object {
            private val secure = SecureRandom()

            /** For the exhaustion fixture: an allocator whose last allocation was [previous]. */
            fun startingAfter(
                identity: Long,
                previous: Long,
            ): Allocator = Allocator { identity }.also { it.counter.set(previous) }
        }
    }

    private val allocators = ConcurrentHashMap<String, Allocator>()

    /** The allocator for the Portal these keys belong to. See [Allocator]. */
    fun allocatorFor(keys: MorphKeys): Allocator =
        // Keyed on a hash of the auth key rather than on the key: the map
        // outlives every connection, and need not hold key material to do it.
        allocators.getOrPut(MorphKeys.hmac(keys.preludeAuthKey(), ALLOCATOR_LABEL).toHex()) { Allocator() }

    private val ALLOCATOR_LABEL = "somewhere allocator".encodeToByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
