// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult

/**
 * Just enough DNS to answer a question, and no more. RFC 1035 sections 4.1.1
 * to 4.1.3.
 *
 * This is not a resolver. It reads one question out of a query and writes one
 * address back, because that is the whole of what fake-IP needs; anything else
 * — a second question, a type nobody here can synthesise, a name that is not a
 * name — is declined and relayed to a real resolver instead.
 *
 * Declining is a first-class outcome rather than an error. Every query this
 * client cannot answer still has to be answered by *somebody*, and the
 * difference between "malformed" and "not ours" is the difference between
 * dropping a packet and forwarding it.
 *
 * ## Why the question is copied rather than re-encoded
 *
 * The answer is built by copying the query's bytes up to the end of the
 * question and appending a record. That is cheaper, and it is also the only
 * way to preserve the query verbatim — resolvers randomise the case of the
 * name (DNS 0x20) and compare the echo, so a re-encoded lower-case name would
 * be read as a spoofed answer by the very defence it looks like.
 */
sealed interface DnsReason : DecodeReason {
    data class Truncated(
        val available: Int,
    ) : DnsReason {
        override val detail: String = "DNS message needs at least 12 bytes, $available available"
    }

    data object NotAQuery : DnsReason {
        override val detail: String = "QR is set; this is a response, not a query"
    }

    data class QuestionCount(
        val count: Int,
    ) : DnsReason {
        override val detail: String = "expected exactly one question, found $count"
    }

    data object CompressedName : DnsReason {
        override val detail: String = "a query's QNAME must not use compression pointers"
    }

    data class LabelLength(
        val length: Int,
    ) : DnsReason {
        override val detail: String = "DNS label is $length bytes; 1-63 are allowed"
    }

    data class NameLength(
        val length: Int,
    ) : DnsReason {
        override val detail: String = "name is $length bytes; 1-255 are allowed"
    }

    data object UnterminatedName : DnsReason {
        override val detail: String = "QNAME ran off the end of the message"
    }

    data class AddressLength(
        val length: Int,
    ) : DnsReason {
        override val detail: String = "an answer for this type needs a different address length, got $length"
    }
}

/** One question, and where it ends — which is where an answer gets appended. */
data class DnsQuestion(
    /** Lower-cased, for matching. The wire bytes keep whatever case they arrived in. */
    val name: String,
    val type: Int,
    val recordClass: Int,
    /** Offset one past the question, i.e. the length of header plus question. */
    val end: Int,
) {
    /** Only A and AAAA can be answered with a synthetic address. */
    val isAddressQuery: Boolean
        get() = recordClass == DnsMessage.CLASS_IN && (type == DnsMessage.TYPE_A || type == DnsMessage.TYPE_AAAA)

    val addressLength: Int
        get() = if (type == DnsMessage.TYPE_AAAA) FakeIpPool.IPV6_LENGTH else FakeIpPool.IPV4_LENGTH
}

object DnsMessage {
    const val HEADER_LENGTH: Int = 12

    const val TYPE_A: Int = 1
    const val TYPE_AAAA: Int = 28
    const val CLASS_IN: Int = 1

    const val LABEL_LENGTH_MAX: Int = 63
    const val NAME_LENGTH_MAX: Int = 255

    /** RFC 1035 section 4.1.1: server failure. */
    const val RCODE_SERVFAIL: Int = 2

    /** A compression pointer is the top two bits of a length byte. */
    private const val POINTER_MASK: Int = 0xC0

    /**
     * How long a device may cache a synthetic address.
     *
     * Short, and deliberately so. The address means nothing outside this
     * client, and a device that keeps it after the tunnel stops will spend the
     * TTL sending traffic into a range that routes nowhere. One second is long
     * enough to serve the burst of connections that follows a single lookup and
     * short enough that nothing survives the tunnel going down.
     *
     * It is not a zero: a zero TTL is legal but tells some resolvers not to
     * cache at all, which turns each of a page's connections into its own
     * query.
     */
    const val SYNTHETIC_TTL_SECONDS: Int = 1

    /**
     * Reads the single question from a query.
     *
     * Everything variable-length is bounded before it is read: the header
     * first, then each label against what remains. A length byte that claims
     * more than the message holds is [DnsReason.UnterminatedName] rather than
     * an exception (NW-Q-03).
     */
    fun parseQuestion(message: ByteArray): DecodeResult<DnsQuestion> {
        if (message.size < HEADER_LENGTH) return DecodeResult.Invalid(DnsReason.Truncated(message.size))
        if (message[2].toInt() and 0x80 != 0) return DecodeResult.Invalid(DnsReason.NotAQuery)

        val questions = ((message[4].toInt() and 0xFF) shl 8) or (message[5].toInt() and 0xFF)
        if (questions != 1) return DecodeResult.Invalid(DnsReason.QuestionCount(questions))

        val name = StringBuilder()
        var offset = HEADER_LENGTH
        var encodedLength = 0
        while (true) {
            if (offset >= message.size) return DecodeResult.Invalid(DnsReason.UnterminatedName)
            val labelLength = message[offset].toInt() and 0xFF
            offset++
            if (labelLength == 0) break
            if (labelLength and POINTER_MASK != 0) return DecodeResult.Invalid(DnsReason.CompressedName)
            if (labelLength > LABEL_LENGTH_MAX) return DecodeResult.Invalid(DnsReason.LabelLength(labelLength))
            if (offset + labelLength > message.size) return DecodeResult.Invalid(DnsReason.UnterminatedName)

            // The length byte counts toward the 255-byte encoded form, which is
            // what the limit is actually about.
            encodedLength += labelLength + 1
            if (encodedLength > NAME_LENGTH_MAX) return DecodeResult.Invalid(DnsReason.NameLength(encodedLength))

            if (name.isNotEmpty()) name.append('.')
            for (index in 0 until labelLength) {
                name.append(lowerAscii(message[offset + index]))
            }
            offset += labelLength
        }

        if (name.isEmpty()) return DecodeResult.Invalid(DnsReason.NameLength(0))
        if (offset + 4 > message.size) return DecodeResult.Invalid(DnsReason.Truncated(message.size))

        val type = ((message[offset].toInt() and 0xFF) shl 8) or (message[offset + 1].toInt() and 0xFF)
        val recordClass = ((message[offset + 2].toInt() and 0xFF) shl 8) or (message[offset + 3].toInt() and 0xFF)
        return DecodeResult.Ok(DnsQuestion(name.toString(), type, recordClass, offset + 4))
    }

    /**
     * Builds an authoritative answer carrying [address].
     *
     * The header is rebuilt rather than copied wholesale: the query's opcode
     * and RD bit are kept because they are the client's, while QR, AA and RA
     * are ours to set and the counts describe what this message actually
     * contains. Anything the query carried beyond its question — an EDNS OPT
     * record, most often — is dropped, which a resolver reads as a server
     * without EDNS and handles by asking again without it.
     */
    fun answer(
        query: ByteArray,
        question: DnsQuestion,
        address: ByteArray,
        ttlSeconds: Int = SYNTHETIC_TTL_SECONDS,
    ): DecodeResult<ByteArray> {
        if (address.size != question.addressLength) {
            return DecodeResult.Invalid(DnsReason.AddressLength(address.size))
        }

        // name pointer(2) + type(2) + class(2) + ttl(4) + rdlength(2) + rdata
        val record = 12 + address.size
        val out = ByteArray(question.end + record)
        query.copyInto(out, 0, 0, question.end)
        writeHeader(out, query, answerCount = 1)

        var at = question.end
        // A pointer to offset 12, where the question's own name sits. Copying
        // the name again would be legal and twice the size.
        out[at++] = 0xC0.toByte()
        out[at++] = HEADER_LENGTH.toByte()
        out[at++] = ((question.type shr 8) and 0xFF).toByte()
        out[at++] = (question.type and 0xFF).toByte()
        out[at++] = ((CLASS_IN shr 8) and 0xFF).toByte()
        out[at++] = (CLASS_IN and 0xFF).toByte()
        out[at++] = ((ttlSeconds shr 24) and 0xFF).toByte()
        out[at++] = ((ttlSeconds shr 16) and 0xFF).toByte()
        out[at++] = ((ttlSeconds shr 8) and 0xFF).toByte()
        out[at++] = (ttlSeconds and 0xFF).toByte()
        out[at++] = ((address.size shr 8) and 0xFF).toByte()
        out[at++] = (address.size and 0xFF).toByte()
        address.copyInto(out, at)
        return DecodeResult.Ok(out)
    }

    /**
     * An answer with no records — the correct reply to a name we hold but a
     * type we cannot synthesise, once the name itself is ours.
     *
     * NODATA is NOERROR with zero answers, not NXDOMAIN: the name exists, this
     * type does not. Saying NXDOMAIN instead would tell the device the host is
     * unknown and stop it trying the type that does work.
     */
    fun noData(
        query: ByteArray,
        question: DnsQuestion,
    ): ByteArray {
        val out = ByteArray(question.end)
        query.copyInto(out, 0, 0, question.end)
        writeHeader(out, query, answerCount = 0)
        return out
    }

    /**
     * SERVFAIL — for a query this client can neither answer nor forward.
     *
     * A definite failure rather than a drop. A device that gets nothing back
     * spends its whole retry schedule finding out and reports the result as a
     * network that is down; a device that gets SERVFAIL moves on to its next
     * resolver at once.
     */
    fun serverFailure(
        query: ByteArray,
        question: DnsQuestion,
    ): ByteArray {
        val out = ByteArray(question.end)
        query.copyInto(out, 0, 0, question.end)
        writeHeader(out, query, answerCount = 0)
        out[3] = (0x80 or RCODE_SERVFAIL).toByte()
        return out
    }

    private fun writeHeader(
        out: ByteArray,
        query: ByteArray,
        answerCount: Int,
    ) {
        // QR=1, AA=1, TC=0; opcode and RD are the client's and are kept.
        out[2] = (((query[2].toInt() and 0x79) or 0x80 or 0x04) and 0xFF).toByte()
        // RA=1, Z=0, RCODE=0 (NOERROR).
        out[3] = 0x80.toByte()
        out[6] = ((answerCount shr 8) and 0xFF).toByte()
        out[7] = (answerCount and 0xFF).toByte()
        out[8] = 0
        out[9] = 0
        out[10] = 0
        out[11] = 0
    }

    private fun lowerAscii(byte: Byte): Char {
        val value = byte.toInt() and 0xFF
        return if (value in 'A'.code..'Z'.code) (value + 32).toChar() else value.toChar()
    }
}
