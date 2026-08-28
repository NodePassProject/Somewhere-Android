// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The connection log, and the one thing it must never carry.
 *
 * **People paste logs into issues.** This repository is public, and a shared
 * key or a subscription token in a pasted log is a credential published by
 * someone trying to be helpful. That is not a hypothetical failure mode here:
 * this project already shipped a defect where a subscription title could rewrite
 * the stored URL, and it was found by fuzzing the store hours after writing it
 * rather than by reviewing it.
 *
 * So the redaction is fuzzed rather than inspected.
 */
class ConnectionLogTest {
    private fun log() = ConnectionLog(now = { 0L })

    private fun domain(host: String) = Target.Domain(host, 443)

    private fun ip(vararg octets: Int) = (Target.ofIpv4(octets.map { it.toByte() }.toByteArray(), 443) as DecodeResult.Ok).value

    @Test
    fun anEntryCarriesWhatThePortalSaid() {
        val log = log()
        log.record(SetupResult.Ready, ip(93, 184, 216, 34), 7u, "QUIC")

        val entry = log.recent.value.single()
        assertEquals(SetupResult.Ready, entry.result)
        assertEquals("93.184.216.34:443", entry.target)
        assertEquals(7, entry.flowId)
        assertEquals("QUIC", entry.carrier)
    }

    @Test
    fun theNewestEntryIsFirst() {
        // The order a reader wants: what just happened, not what happened when
        // the tunnel came up an hour ago.
        val log = log()
        log.record(SetupResult.Ready, ip(1, 1, 1, 1), 1u, "TLS")
        log.record(SetupResult.DialFailed, ip(2, 2, 2, 2), 2u, "TLS")
        assertEquals(listOf(2, 1), log.recent.value.map { it.flowId })
    }

    @Test
    fun theLogIsBoundedAndDropsTheOldest() {
        // A busy device opens hundreds of flows a minute. A log that grew with
        // the tunnel would be a memory leak with a user interface.
        val log = log()
        repeat(1_000) { log.record(SetupResult.Ready, ip(10, 0, 0, 1), it.toUInt(), "TLS") }

        val entries = log.recent.value
        assertTrue("the log grew without bound: ${entries.size}", entries.size <= 200)
        assertEquals("the newest entry was dropped instead of the oldest", 999, entries.first().flowId)
    }

    /**
     * There is nowhere in an entry to put a credential.
     *
     * This is the guarantee that matters, and it is structural rather than
     * careful: an entry has a result, a timestamp, a target, a flow id and a
     * carrier, and nothing else. A shared key or a subscription token cannot
     * appear in a log line because there is no field it could occupy — which
     * survives a future edit in a way "we were careful" does not.
     *
     * The target *is* shown, and should be: it is where the user asked to go.
     * What it must not be is a URL, a header, or anything else that could carry
     * a credential smuggled through punctuation — see the next case.
     */
    @Test
    fun anEntryHasNoFieldACredentialCouldOccupy() {
        val fields =
            ConnectionLogEntry::class
                .java
                .declaredFields
                .filterNot { it.isSynthetic }
                // Compose adds a $stable field to every @Immutable class.
                .filterNot { it.name.startsWith("\$") }
                .map { it.name }
                .toSet()
        assertEquals(
            "the log entry grew a field; check it cannot carry a credential",
            setOf("result", "timestamp", "target", "flowId", "carrier"),
            fields,
        )
    }

    /**
     * A target reaches a log line as a host and a port, and never as a URL.
     *
     * The values below are the credential-bearing shapes this client actually
     * handles — a `nowhere://` URL with userinfo, a subscription URL with a
     * token, an `Authorization` header. None of them can survive as itself,
     * because every character that makes them what they are is one a hostname
     * cannot contain.
     */
    @Test
    fun nothingUrlShapedSurvivesIntoALogLine() {
        val log = log()
        listOf(
            "nowhere://s3cret-shared-key@portal.example:443",
            "https://dash.example/sub?token=abcdef0123456789",
            "Bearer abcdef0123456789",
            "%73%33%63%72%65%74",
            "host\nInjected: line",
        ).forEach { log.record(SetupResult.Ready, domain(it), 1u, "TLS") }

        log.recent.value.forEach { entry ->
            val host = entry.target!!.substringBeforeLast(':')
            listOf("@", "/", "?", "=", "%", " ", "\n", ":").forEach { forbidden ->
                assertFalse(
                    "a host reached a log line containing '$forbidden': $host",
                    host.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun arbitraryBytesInAHostNeitherCrashNorEscape() {
        // Every byte value, as a host. A hostname is not a place a peer's
        // choice of punctuation should be able to reach.
        val log = log()
        val random = Random(3)
        repeat(500) {
            val host = String(CharArray(random.nextInt(1, 40)) { random.nextInt(0, 0x2FF).toChar() })
            log.record(SetupResult.Ready, domain(host), 1u, "TLS")
        }
        log.recent.value.forEach { entry ->
            val target = entry.target ?: return@forEach
            val host = target.substringBeforeLast(':')
            assertTrue(
                "a host survived containing something no hostname can: $host",
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' },
            )
        }
    }

    @Test
    fun clearingLeavesNothingBehind() {
        val log = log()
        log.record(SetupResult.Ready, ip(1, 2, 3, 4), 1u, "TLS")
        log.clear()
        assertTrue(log.recent.value.isEmpty())
    }
}
