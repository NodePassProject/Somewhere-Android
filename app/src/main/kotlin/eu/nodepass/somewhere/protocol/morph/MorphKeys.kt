// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.morph

import eu.nodepass.somewhere.protocol.auth.SharedKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The four directional Morph keys, per `docs/protocol.md` § Morph, Key derivation.
 *
 * ```
 * root    = HMAC-SHA256("nowhere/nw3/morph", shared_key)
 * tcp_c2s = HMAC-SHA256(root, "tcp c2s" || 0x01)
 * tcp_s2c = HMAC-SHA256(root, "tcp s2c" || 0x01)
 * udp_c2s = HMAC-SHA256(root, "udp c2s" || 0x01)
 * udp_s2c = HMAC-SHA256(root, "udp s2c" || 0x01)
 * ```
 *
 * **The root label is the HMAC key itself, not hashed first.** Authentication
 * hashes its label into a salt and Morph does not; the specification calls the
 * difference out, and transcribing one derivation from the other is the
 * obvious mistake to make here.
 *
 * Same shared-key bytes as authentication, which is why a node has one key and
 * not two. The labels are published protocol constants, not deployment
 * parameters: the only secret is the key.
 *
 * This client is always the transport client, so it sends with `*_c2s` and
 * receives with `*_s2c`. The accessors are named for the direction rather than
 * the role so that a test can stand in for the server.
 */
class MorphKeys private constructor(
    private val tcpC2s: ByteArray,
    private val tcpS2c: ByteArray,
    private val udpC2s: ByteArray,
    private val udpS2c: ByteArray,
) {
    fun tcpClientToServer(): ByteArray = tcpC2s.copyOf()

    fun tcpServerToClient(): ByteArray = tcpS2c.copyOf()

    fun udpClientToServer(): ByteArray = udpC2s.copyOf()

    fun udpServerToClient(): ByteArray = udpS2c.copyOf()

    /** Key material never reaches a log. */
    override fun toString(): String = "MorphKeys(REDACTED)"

    companion object {
        const val KEY_LENGTH: Int = 32

        private const val ROOT_LABEL = "nowhere/nw3/morph"
        private const val HMAC_SHA256 = "HmacSHA256"

        fun derive(sharedKey: SharedKey): MorphKeys = derive(sharedKey.toByteArray())

        /** Over raw bytes, for upstream's fixtures, which derive from arbitrary text. */
        internal fun derive(sharedKey: ByteArray): MorphKeys {
            val root = hmac(ROOT_LABEL.encodeToByteArray(), sharedKey)
            return MorphKeys(
                tcpC2s = expand(root, "tcp c2s"),
                tcpS2c = expand(root, "tcp s2c"),
                udpC2s = expand(root, "udp c2s"),
                udpS2c = expand(root, "udp s2c"),
            )
        }

        private fun expand(
            root: ByteArray,
            info: String,
        ): ByteArray = hmac(root, info.encodeToByteArray() + 0x01)

        private fun hmac(
            key: ByteArray,
            message: ByteArray,
        ): ByteArray =
            Mac.getInstance(HMAC_SHA256).run {
                init(SecretKeySpec(key, HMAC_SHA256))
                doFinal(message)
            }
    }
}
