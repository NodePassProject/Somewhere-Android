// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.auth

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

/**
 * The pre-shared key a session authenticates with. 1–255 bytes, never transmitted.
 *
 * Wrapped rather than passed as a raw [ByteArray] so that the length rule is
 * enforced once, at the boundary, and so that [toString] cannot leak it into a
 * log or a crash report.
 */
@JvmInline
value class SharedKey private constructor(
    private val bytes: ByteArray,
) {
    /** A defensive copy: the key must not be mutable through a caller's reference. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    val size: Int get() = bytes.size

    /** Never renders the key. It is the one value in this project that must not reach a log. */
    override fun toString(): String = "SharedKey(${bytes.size} bytes)"

    companion object {
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 255

        fun of(bytes: ByteArray): DecodeResult<SharedKey> =
            when {
                bytes.isEmpty() -> invalid(AuthReason.EmptySharedKey)
                bytes.size > MAX_LENGTH -> invalid(AuthReason.SharedKeyTooLong(bytes.size))
                else -> SharedKey(bytes.copyOf()).ok()
            }

        fun of(text: String): DecodeResult<SharedKey> = of(text.encodeToByteArray())

        /**
         * Extracts the key from a URL userinfo component.
         *
         * The userinfo is **strictly** percent-decoded: `+` stays a literal `+`
         * rather than becoming a space. This is why the platform URL decoders are
         * not used — they implement form encoding, where `+` means space, and a
         * key containing `+` would silently authenticate as something else.
         *
         * A password component makes the URL invalid rather than being ignored:
         * `key:password@host` most likely means someone put a key where a
         * username goes, and guessing which half is the key would be worse than
         * refusing.
         */
        fun fromUserInfo(userInfo: String?): DecodeResult<SharedKey> {
            if (userInfo.isNullOrEmpty()) return invalid(AuthReason.MissingUserInfo)
            if (userInfo.contains(':')) return invalid(AuthReason.PasswordComponentPresent)
            return when (val decoded = strictPercentDecode(userInfo)) {
                null -> invalid(AuthReason.MalformedPercentEncoding)
                else -> of(decoded)
            }
        }

        /**
         * Percent-decodes to bytes, or returns null if the input is malformed.
         *
         * Returns bytes rather than a String because the key is not required to
         * be valid UTF-8 — decoding through a String would replace invalid
         * sequences and change the key.
         */
        internal fun strictPercentDecode(input: String): ByteArray? {
            val out = ArrayList<Byte>(input.length)
            var index = 0
            while (index < input.length) {
                val character = input[index]
                if (character != '%') {
                    if (character.code > 0x7F) return null // non-ASCII is not valid in userinfo
                    out.add(character.code.toByte())
                    index++
                    continue
                }
                if (index + 2 >= input.length) return null // "%", "%1" at the end
                val high = hexDigit(input[index + 1])
                val low = hexDigit(input[index + 2])
                if (high < 0 || low < 0) return null // "%GG"
                out.add(((high shl 4) or low).toByte())
                index += 3
            }
            return out.toByteArray()
        }

        private fun hexDigit(character: Char): Int =
            when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                else -> -1
            }
    }
}
