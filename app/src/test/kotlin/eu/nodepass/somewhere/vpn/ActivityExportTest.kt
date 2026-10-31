// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityExportTest {
    private val text =
        ActivityExport.render(
            appVersion = "0.1.0",
            protocolBaseline = "Nowhere v3.0.1",
            exportedAt = "2026-10-21T12:00:00Z",
            portalAnswers =
                listOf(
                    ConnectionLogEntry(SetupResult.DialFailed, "12:00:01", "example.com:443", null, "TLS"),
                    ConnectionLogEntry(SetupResult.Ready, "12:00:00", "example.com:443", 3, "QUIC", setupMillis = 41),
                ),
            dns = listOf(DnsLogEntry("12:00:00", "example.com", "A", DnsOutcome.FakeIp)),
            live =
                listOf(
                    LiveConnection(7, ConnectionProtocol.Tcp, "example.com:443", ConnectionRoute.Direct, "Browser", 10, 20, 1_000),
                ),
            nowElapsedMillis = 6_000,
        )

    @Test
    fun `results are the specification's identifiers, not Kotlin's names`() {
        // Pasted into issues and compared with the spec and other people's
        // logs, so DIAL_FAILED, never DialFailed.
        assertTrue(text.contains("DIAL_FAILED"))
        assertTrue(text.contains("READY"))
        assertFalse(text.contains("DialFailed"))
    }

    @Test
    fun `every surface is in it`() {
        assertTrue(text.contains("41 ms"))
        assertTrue(text.contains("12:00:00  A  example.com  FAKE-IP"))
        assertTrue(text.contains("TCP  example.com:443  Direct  Browser  up 10 B  down 20 B  5s"))
    }
}
