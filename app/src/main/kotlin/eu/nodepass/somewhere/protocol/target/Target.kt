// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.target

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok

sealed interface TargetReason : DecodeReason {
    data object PortZero : TargetReason {
        override val detail: String = "port zero is invalid for every target form"
    }

    data class UnknownAddressType(
        val atyp: Int,
    ) : TargetReason {
        override val detail: String = "unknown ATYP 0x%02x".format(atyp)
    }

    data object Truncated : TargetReason {
        override val detail: String = "target is truncated"
    }

    data class DomainLength(
        val length: Int,
    ) : TargetReason {
        override val detail: String = "domain is $length bytes; 1-253 are allowed"
    }

    data object DomainNotAscii : TargetReason {
        override val detail: String = "domain is not in ASCII/IDNA wire form"
    }

    data class DomainLabelLength(
        val length: Int,
    ) : TargetReason {
        override val detail: String = "DNS label is $length bytes; 1-63 are allowed"
    }

    data object DomainLabelHyphen : TargetReason {
        override val detail: String = "DNS label begins or ends with a hyphen"
    }

    data object DomainLabelCharacter : TargetReason {
        override val detail: String = "DNS label contains a byte outside letters, digits and hyphen"
    }
}

/**
 * Where a flow is headed. NW-P-05, `docs/protocol.md` section 5.
 *
 * ```
 * IPv4   0x01 || 4 bytes  || port(u16)   =  7 bytes
 * domain 0x03 || len(u8)  || len bytes || port(u16)
 * IPv6   0x04 || 16 bytes || port(u16)   = 19 bytes
 * ```
 *
 * ATYP values match SOCKS5, which means `0x02` does not exist and must be
 * rejected as unknown rather than treated as a gap.
 *
 * A domain target is an **unresolved** hostname: no port, no IPv6 brackets, no
 * trailing NUL. Resolution happens at the far end, which is the point — sending
 * a name rather than an address is what lets the Portal resolve it in its own
 * network.
 */
sealed interface Target {
    val port: Int

    data class Ip(
        val octets: ByteArray,
        override val port: Int,
    ) : Target {
        val isIpv6: Boolean get() = octets.size == IPV6_ADDRESS_LENGTH

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Ip && port == other.port && octets.contentEquals(other.octets))

        override fun hashCode(): Int = 31 * octets.contentHashCode() + port

        override fun toString(): String = "Ip(${octets.size * 8}-bit, port=$port)"
    }

    data class Domain(
        val host: String,
        override val port: Int,
    ) : Target

    fun encode(): ByteArray =
        when (this) {
            is Ip -> byteArrayOf(if (isIpv6) ATYP_IPV6 else ATYP_IPV4) + octets + portBytes(port)
            is Domain -> {
                val bytes = host.encodeToByteArray()
                byteArrayOf(ATYP_DOMAIN, bytes.size.toByte()) + bytes + portBytes(port)
            }
        }

    /** A decoded target and how many bytes it consumed. */
    data class Decoded(
        val target: Target,
        val consumed: Int,
    )

    companion object {
        const val ATYP_IPV4: Byte = 0x01
        const val ATYP_DOMAIN: Byte = 0x03
        const val ATYP_IPV6: Byte = 0x04

        const val IPV4_ADDRESS_LENGTH: Int = 4
        const val IPV6_ADDRESS_LENGTH: Int = 16
        const val IPV4_TARGET_LENGTH: Int = 7
        const val IPV6_TARGET_LENGTH: Int = 19

        const val DOMAIN_LENGTH_MIN: Int = 1
        const val DOMAIN_LENGTH_MAX: Int = 253
        const val LABEL_LENGTH_MAX: Int = 63

        fun ofIpv4(
            octets: ByteArray,
            port: Int,
        ): DecodeResult<Target> {
            require(octets.size == IPV4_ADDRESS_LENGTH) { "IPv4 needs 4 octets" }
            return validatePort(port) ?: Ip(octets.copyOf(), port).ok()
        }

        fun ofIpv6(
            octets: ByteArray,
            port: Int,
        ): DecodeResult<Target> {
            require(octets.size == IPV6_ADDRESS_LENGTH) { "IPv6 needs 16 octets" }
            return validatePort(port) ?: Ip(octets.copyOf(), port).ok()
        }

        fun ofDomain(
            host: String,
            port: Int,
        ): DecodeResult<Target> {
            validatePort(port)?.let { return it }
            validateDomain(host.encodeToByteArray())?.let { return it }
            return Domain(host, port).ok()
        }

        /**
         * Decodes one target from the front of [input], reporting how many bytes
         * it used.
         *
         * A prefix decode rather than an exact-length one, because a cold
         * connection may write `AuthFrame || FlowHeader || Target || payload` in
         * a single write (NW-P-10) — so the bytes after the target are the
         * caller's, not an error.
         */
        fun decode(
            input: ByteArray,
            offset: Int = 0,
        ): DecodeResult<Decoded> {
            if (offset >= input.size) return invalid(TargetReason.Truncated)
            return when (input[offset]) {
                ATYP_IPV4 -> decodeIp(input, offset, IPV4_ADDRESS_LENGTH, IPV4_TARGET_LENGTH)
                ATYP_IPV6 -> decodeIp(input, offset, IPV6_ADDRESS_LENGTH, IPV6_TARGET_LENGTH)
                ATYP_DOMAIN -> decodeDomain(input, offset)
                else -> invalid(TargetReason.UnknownAddressType(input[offset].toInt() and 0xFF))
            }
        }

        private fun decodeIp(
            input: ByteArray,
            offset: Int,
            addressLength: Int,
            totalLength: Int,
        ): DecodeResult<Decoded> {
            if (input.size - offset < totalLength) return invalid(TargetReason.Truncated)
            val octets = input.copyOfRange(offset + 1, offset + 1 + addressLength)
            val port = readPort(input, offset + 1 + addressLength)
            validatePort(port)?.let { return it }
            return Decoded(Ip(octets, port), totalLength).ok()
        }

        private fun decodeDomain(
            input: ByteArray,
            offset: Int,
        ): DecodeResult<Decoded> {
            // Validate the smallest outer header before reading anything
            // variable-length — this is the rule that keeps a hostile length
            // byte from driving an unbounded read.
            if (input.size - offset < 2) return invalid(TargetReason.Truncated)
            val length = input[offset + 1].toInt() and 0xFF
            val total = 1 + 1 + length + 2
            if (input.size - offset < total) return invalid(TargetReason.Truncated)

            val bytes = input.copyOfRange(offset + 2, offset + 2 + length)
            validateDomain(bytes)?.let { return it }
            val port = readPort(input, offset + 2 + length)
            validatePort(port)?.let { return it }
            return Decoded(Domain(String(bytes, Charsets.US_ASCII), port), total).ok()
        }

        private fun readPort(
            input: ByteArray,
            at: Int,
        ): Int = ((input[at].toInt() and 0xFF) shl 8) or (input[at + 1].toInt() and 0xFF)

        private fun portBytes(port: Int): ByteArray = byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())

        private fun validatePort(port: Int): DecodeResult<Nothing>? = if (port == 0) invalid(TargetReason.PortZero) else null

        /**
         * Per `docs/protocol.md` section 5: 1–253 ASCII bytes; each DNS label is
         * 1–63 bytes of letters, digits or `-`, and does not begin or end with
         * `-`. No trailing NUL on the wire.
         */
        private fun validateDomain(bytes: ByteArray): DecodeResult<Nothing>? {
            if (bytes.size !in DOMAIN_LENGTH_MIN..DOMAIN_LENGTH_MAX) {
                return invalid(TargetReason.DomainLength(bytes.size))
            }
            if (bytes.any { (it.toInt() and 0xFF) > 0x7F }) {
                return invalid(TargetReason.DomainNotAscii)
            }
            var labelStart = 0
            for (index in 0..bytes.size) {
                val atEnd = index == bytes.size
                if (!atEnd && bytes[index] != '.'.code.toByte()) continue
                val labelLength = index - labelStart
                if (labelLength !in 1..LABEL_LENGTH_MAX) {
                    return invalid(TargetReason.DomainLabelLength(labelLength))
                }
                val first = bytes[labelStart]
                val last = bytes[index - 1]
                if (first == HYPHEN || last == HYPHEN) {
                    return invalid(TargetReason.DomainLabelHyphen)
                }
                for (position in labelStart until index) {
                    if (!isLabelByte(bytes[position])) return invalid(TargetReason.DomainLabelCharacter)
                }
                labelStart = index + 1
            }
            return null
        }

        private const val HYPHEN: Byte = '-'.code.toByte()

        private fun isLabelByte(byte: Byte): Boolean {
            val value = byte.toInt() and 0xFF
            return value in 'a'.code..'z'.code ||
                value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code ||
                byte == HYPHEN
        }
    }
}
