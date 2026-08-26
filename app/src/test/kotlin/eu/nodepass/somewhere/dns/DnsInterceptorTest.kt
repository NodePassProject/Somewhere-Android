// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class DnsInterceptorTest {
    private val pool = FakeIpPool(capacity = 64)
    private val interceptor = DnsInterceptor(pool)

    private fun answerFor(
        name: String,
        type: Int = DnsMessage.TYPE_A,
    ): DnsInterceptor.Outcome = interceptor.handle(dnsQuery(name, type))

    @Test
    fun `an A query is answered from the pool`() {
        val outcome = answerFor("example.com")
        assertTrue(outcome is DnsInterceptor.Outcome.Answer)
        val answer = outcome as DnsInterceptor.Outcome.Answer
        assertEquals("example.com", answer.name)

        val address = answer.message.copyOfRange(answer.message.size - 4, answer.message.size)
        assertTrue("the address is synthetic", FakeIpPool.isFake(address))
        assertEquals("and it maps back to the name", "example.com", pool.nameFor(address))
    }

    @Test
    fun `asking twice gets the same address`() {
        val first = answerFor("example.com") as DnsInterceptor.Outcome.Answer
        val second = answerFor("example.com") as DnsInterceptor.Outcome.Answer
        assertArrayEquals(first.message, second.message)
        assertEquals(1, pool.size)
    }

    @Test
    fun `AAAA is NODATA while the tunnel carries no route for it`() {
        val outcome = answerFor("example.com", DnsMessage.TYPE_AAAA)
        val answer = outcome as DnsInterceptor.Outcome.Answer
        assertEquals("no answer records", 0, (answer.message[7].toInt() and 0xFF))
        assertEquals("NOERROR, not NXDOMAIN", 0x80, answer.message[3].toInt() and 0xFF)
        assertEquals("and nothing was minted for it", 0, pool.size)
    }

    @Test
    fun `AAAA is synthesised once the tunnel can carry it`() {
        val v6 = DnsInterceptor(pool, synthesiseIpv6 = true)
        val answer = v6.handle(dnsQuery("example.com", DnsMessage.TYPE_AAAA)) as DnsInterceptor.Outcome.Answer
        val address = answer.message.copyOfRange(answer.message.size - 16, answer.message.size)
        assertTrue(FakeIpPool.isFake(address))
        assertEquals("example.com", pool.nameFor(address))
    }

    @Test
    fun `queries that are not for an address are relayed untouched`() {
        // MX, TXT, SRV, PTR, NS, SOA, HTTPS, and ANY: every one of them needs a
        // real resolver, and none of them can be answered with an address.
        listOf(2, 6, 12, 15, 16, 33, 65, 255).forEach { type ->
            val outcome = answerFor("example.com", type)
            assertTrue("type $type must be relayed", outcome is DnsInterceptor.Outcome.Relay)
        }
        assertEquals("nothing was minted for any of them", 0, pool.size)
    }

    @Test
    fun `a class other than IN is relayed`() {
        val outcome = interceptor.handle(dnsQuery("example.com", DnsMessage.TYPE_A, recordClass = 4))
        assertTrue(outcome is DnsInterceptor.Outcome.Relay)
    }

    @Test
    fun `a name the protocol cannot encode as a target is relayed`() {
        // Underscore labels are ordinary in service discovery and are not legal
        // in a Nowhere domain target. Minting an address would only move the
        // failure to the moment a flow opens.
        listOf("_dns-sd._udp.local", "-leading.example", "trailing-.example").forEach { name ->
            val outcome = interceptor.handle(dnsQuery(name, DnsMessage.TYPE_A))
            assertTrue("'$name' must be relayed", outcome is DnsInterceptor.Outcome.Relay)
        }
        assertEquals(0, pool.size)
    }

    @Test
    fun `every name that is answered is one the protocol will carry`() {
        val random = Random(20260826)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-._"
        repeat(3_000) {
            val name = (0..random.nextInt(1, 40)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            when (val outcome = interceptor.handle(dnsQuery(name, DnsMessage.TYPE_A))) {
                is DnsInterceptor.Outcome.Answer ->
                    assertTrue(
                        "answered '$name', which is not a legal target",
                        Target.ofDomain(outcome.name, 443) is DecodeResult.Ok,
                    )

                is DnsInterceptor.Outcome.Relay -> Unit
            }
        }
    }

    @Test
    fun `a full pool with every mapping live relays rather than reassigning`() {
        val small = FakeIpPool(capacity = 2)
        val tight = DnsInterceptor(small)
        listOf("one.example", "two.example").forEach { name ->
            val answer = tight.handle(dnsQuery(name, DnsMessage.TYPE_A)) as DnsInterceptor.Outcome.Answer
            small.retain(answer.message.copyOfRange(answer.message.size - 4, answer.message.size))
        }

        val outcome = tight.handle(dnsQuery("three.example", DnsMessage.TYPE_A))
        assertTrue("a worse route beats a wrong one", outcome is DnsInterceptor.Outcome.Relay)
    }

    @Test
    fun `arbitrary bytes neither crash nor allocate without bound`() {
        // NW-Q-03. Seeds are fixed: a fuzz test that cannot be replayed is a
        // flake generator. The pool is capped so a run that minted an address
        // per input would be caught by the size assertion rather than by the
        // machine running out of memory.
        val lengths = listOf(0, 1, 2, 11, 12, 13, 14, 16, 17, 32, 64, 255, 256, 512, 4096)
        listOf(1L, 7L, 42L, 1337L, 20260826L).forEach { seed ->
            val random = Random(seed)
            repeat(4_000) {
                val bytes = ByteArray(lengths[random.nextInt(lengths.size)]).also(random::nextBytes)
                try {
                    when (val outcome = interceptor.handle(bytes)) {
                        is DnsInterceptor.Outcome.Answer ->
                            assertTrue(
                                "an answer must not be longer than the query plus one record",
                                outcome.message.size <= bytes.size + 28,
                            )

                        is DnsInterceptor.Outcome.Relay -> Unit
                    }
                } catch (error: Throwable) {
                    fail("seed $seed: ${bytes.size} bytes threw ${error::class.simpleName}: ${error.message}")
                }
                assertTrue("the pool stayed bounded", pool.size <= 64)
            }
        }
    }

    @Test
    fun `a truncated query is relayed rather than dropped`() {
        // Somebody has to answer it, and a drop is indistinguishable from a
        // broken network for as long as the device's retry schedule lasts.
        val query = dnsQuery("example.com", DnsMessage.TYPE_A)
        (0 until query.size).forEach { size ->
            val outcome = interceptor.handle(query.copyOfRange(0, size))
            if (outcome is DnsInterceptor.Outcome.Answer) {
                fail("a $size-byte fragment must not be answered")
            }
        }
    }
}
