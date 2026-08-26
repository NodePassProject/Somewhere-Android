// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A DNS query, built the way a resolver builds one. */
internal fun dnsQuery(
    name: String,
    type: Int,
    id: Int = 0x1234,
    recordClass: Int = DnsMessage.CLASS_IN,
    questions: Int = 1,
    flags: Int = 0x0100,
): ByteArray {
    val labels = name.split('.').filter { it.isNotEmpty() }
    val encoded = labels.sumOf { it.length + 1 } + 1
    val out = ByteArray(DnsMessage.HEADER_LENGTH + encoded + 4)
    out[0] = ((id shr 8) and 0xFF).toByte()
    out[1] = (id and 0xFF).toByte()
    out[2] = ((flags shr 8) and 0xFF).toByte()
    out[3] = (flags and 0xFF).toByte()
    out[4] = ((questions shr 8) and 0xFF).toByte()
    out[5] = (questions and 0xFF).toByte()
    var at = DnsMessage.HEADER_LENGTH
    labels.forEach { label ->
        out[at++] = label.length.toByte()
        label.forEach { out[at++] = it.code.toByte() }
    }
    out[at++] = 0
    out[at++] = ((type shr 8) and 0xFF).toByte()
    out[at++] = (type and 0xFF).toByte()
    out[at++] = ((recordClass shr 8) and 0xFF).toByte()
    out[at] = (recordClass and 0xFF).toByte()
    return out
}

class DnsMessageTest {
    private fun question(bytes: ByteArray): DnsQuestion = (DnsMessage.parseQuestion(bytes) as DecodeResult.Ok).value

    @Test
    fun `a query's name and type are read back`() {
        val parsed = question(dnsQuery("www.example.com", DnsMessage.TYPE_A))
        assertEquals("www.example.com", parsed.name)
        assertEquals(DnsMessage.TYPE_A, parsed.type)
        assertEquals(DnsMessage.CLASS_IN, parsed.recordClass)
        assertTrue(parsed.isAddressQuery)
    }

    @Test
    fun `a name is matched case-insensitively but the wire bytes are not touched`() {
        // Resolvers randomise the case of the name and compare the echo (DNS
        // 0x20). An answer that lower-cased it would be read as a spoof.
        val query = dnsQuery("wWw.ExAmPlE.cOm", DnsMessage.TYPE_A)
        val parsed = question(query)
        assertEquals("www.example.com", parsed.name)

        val answer = (DnsMessage.answer(query, parsed, FakeIpPool.ipv4(1)) as DecodeResult.Ok).value
        assertArrayEquals(
            query.copyOfRange(DnsMessage.HEADER_LENGTH, parsed.end),
            answer.copyOfRange(DnsMessage.HEADER_LENGTH, parsed.end),
        )
    }

    @Test
    fun `an A answer carries one four-byte record with a short TTL`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_A)
        val parsed = question(query)
        val answer = (DnsMessage.answer(query, parsed, FakeIpPool.ipv4(7)) as DecodeResult.Ok).value

        assertArrayEquals("the id is echoed", query.copyOfRange(0, 2), answer.copyOfRange(0, 2))
        assertEquals("QR and AA set", 0x85, answer[2].toInt() and 0xFF)
        assertEquals("RA set, RCODE 0", 0x80, answer[3].toInt() and 0xFF)
        assertEquals("one question", 1, beShort(answer, 4))
        assertEquals("one answer", 1, beShort(answer, 6))
        assertEquals("no authority records", 0, beShort(answer, 8))
        assertEquals("no additional records", 0, beShort(answer, 10))

        val record = parsed.end
        assertEquals("the name is a pointer to offset 12", 0xC00C, beShort(answer, record))
        assertEquals(DnsMessage.TYPE_A, beShort(answer, record + 2))
        assertEquals(DnsMessage.CLASS_IN, beShort(answer, record + 4))
        assertEquals("TTL is short and deliberate", DnsMessage.SYNTHETIC_TTL_SECONDS, beInt(answer, record + 6))
        assertTrue("a synthetic TTL outlives one burst, not one session", DnsMessage.SYNTHETIC_TTL_SECONDS in 1..10)
        assertEquals(4, beShort(answer, record + 10))
        assertArrayEquals(FakeIpPool.ipv4(7), answer.copyOfRange(record + 12, record + 16))
        assertEquals("nothing trails the record", record + 16, answer.size)
    }

    @Test
    fun `an AAAA answer carries one sixteen-byte record`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_AAAA)
        val parsed = question(query)
        val answer = (DnsMessage.answer(query, parsed, FakeIpPool.ipv6(9)) as DecodeResult.Ok).value

        val record = parsed.end
        assertEquals(1, beShort(answer, 6))
        assertEquals(DnsMessage.TYPE_AAAA, beShort(answer, record + 2))
        assertEquals(16, beShort(answer, record + 10))
        assertArrayEquals(FakeIpPool.ipv6(9), answer.copyOfRange(record + 12, record + 28))
    }

    @Test
    fun `an address of the wrong length is refused rather than written short`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_AAAA)
        val parsed = question(query)
        val built = DnsMessage.answer(query, parsed, FakeIpPool.ipv4(1))
        assertTrue(built is DecodeResult.Invalid)
    }

    @Test
    fun `the client's opcode and recursion-desired bit are kept`() {
        // RD clear, and a non-zero opcode: both are the client's statement about
        // the query and neither is ours to rewrite.
        val query = dnsQuery("example.com", DnsMessage.TYPE_A, flags = 0x0800)
        val parsed = question(query)
        val answer = (DnsMessage.answer(query, parsed, FakeIpPool.ipv4(1)) as DecodeResult.Ok).value
        assertEquals("QR, opcode 1, AA, RD clear", 0x84 or 0x08, answer[2].toInt() and 0xFF)
    }

    @Test
    fun `NODATA is NOERROR with no answers, not NXDOMAIN`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_AAAA)
        val parsed = question(query)
        val answer = DnsMessage.noData(query, parsed)
        assertEquals("no answers", 0, beShort(answer, 6))
        assertEquals("RCODE 0: the name exists, this type does not", 0x80, answer[3].toInt() and 0xFF)
        assertEquals(parsed.end, answer.size)
    }

    @Test
    fun `a response is not a query`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_A, flags = 0x8180)
        val parsed = DnsMessage.parseQuestion(query)
        assertEquals(DnsReason.NotAQuery, (parsed as DecodeResult.Invalid).reason)
    }

    @Test
    fun `only a single question is handled`() {
        listOf(0, 2, 65_535).forEach { count ->
            val parsed = DnsMessage.parseQuestion(dnsQuery("example.com", DnsMessage.TYPE_A, questions = count))
            assertTrue("$count questions", (parsed as DecodeResult.Invalid).reason is DnsReason.QuestionCount)
        }
    }

    @Test
    fun `a compression pointer in a query name is refused`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_A)
        query[DnsMessage.HEADER_LENGTH] = 0xC0.toByte()
        assertEquals(
            DnsReason.CompressedName,
            (DnsMessage.parseQuestion(query) as DecodeResult.Invalid).reason,
        )
    }

    @Test
    fun `a label length past the end of the message does not read past it`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_A)
        query[DnsMessage.HEADER_LENGTH] = 60
        assertEquals(
            DnsReason.UnterminatedName,
            (DnsMessage.parseQuestion(query) as DecodeResult.Invalid).reason,
        )
    }

    @Test
    fun `a name that never terminates is refused`() {
        val query = dnsQuery("example.com", DnsMessage.TYPE_A)
        assertTrue(
            DnsMessage.parseQuestion(query.copyOfRange(0, query.size - 5)) is DecodeResult.Invalid,
        )
    }

    @Test
    fun `an over-long name is refused before it is assembled`() {
        val name = (1..40).joinToString(".") { "abcdefghij" }
        assertTrue(
            (DnsMessage.parseQuestion(dnsQuery(name, DnsMessage.TYPE_A)) as DecodeResult.Invalid)
                .reason is DnsReason.NameLength,
        )
    }

    @Test
    fun `a header shorter than twelve bytes is refused`() {
        (0 until DnsMessage.HEADER_LENGTH).forEach { size ->
            assertTrue(
                (DnsMessage.parseQuestion(ByteArray(size)) as DecodeResult.Invalid).reason is DnsReason.Truncated,
            )
        }
    }

    @Test
    fun `a root query has no name to answer`() {
        val out = ByteArray(DnsMessage.HEADER_LENGTH + 5)
        out[5] = 1
        out[DnsMessage.HEADER_LENGTH + 1] = 0
        out[DnsMessage.HEADER_LENGTH + 2] = 1
        assertTrue((DnsMessage.parseQuestion(out) as DecodeResult.Invalid).reason is DnsReason.NameLength)
    }

    @Test
    fun `a class other than IN is not an address query`() {
        val parsed = question(dnsQuery("example.com", DnsMessage.TYPE_A, recordClass = 3))
        assertTrue("the name still parses", parsed.name == "example.com")
        assertTrue("but it is not one we can answer", !parsed.isAddressQuery)
    }

    private fun beShort(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun beInt(
        bytes: ByteArray,
        at: Int,
    ): Int = (beShort(bytes, at) shl 16) or beShort(bytes, at + 2)
}
