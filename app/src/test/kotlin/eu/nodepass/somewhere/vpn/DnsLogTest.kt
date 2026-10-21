// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DnsLogTest {
    private val log = DnsLog { 0L }

    @Test
    fun `entries are newest first with the type named`() {
        log.record("example.com", 1, DnsOutcome.FakeIp)
        log.record("example.com", 28, DnsOutcome.NoData)
        log.record("_srv._tcp.example.com", 33, DnsOutcome.Relayed)
        val entries = log.recent.value
        assertEquals(listOf("SRV", "AAAA", "A"), entries.map { it.type })
        assertEquals("_srv._tcp.example.com", entries.first().name)
    }

    @Test
    fun `an unnamed type shows its number, and an unreadable query shows nothing`() {
        log.record(null, null, DnsOutcome.Failed)
        log.record("x.example", 99, DnsOutcome.Relayed)
        assertEquals("99", log.recent.value[0].type)
        assertEquals("", log.recent.value[1].name)
    }

    @Test
    fun `a name cannot carry anything a host name cannot`() {
        // A query name is whatever bytes an app put on the wire, and this log
        // is what gets pasted into issues.
        log.record("evil\nnowhere://0123456789abcdef0123456789abcdef@x:1", 1, DnsOutcome.FakeIp)
        val name =
            log.recent.value
                .single()
                .name
        assertFalse(name.contains('\n'))
        assertFalse(name.contains('@'))
        assertFalse(name.contains(':'))
    }

    @Test
    fun `the log is bounded`() {
        repeat(1_000) { log.record("n$it.example", 1, DnsOutcome.FakeIp) }
        assertEquals(300, log.recent.value.size)
        assertEquals(
            "n999.example",
            log.recent.value
                .first()
                .name,
        )
    }
}
