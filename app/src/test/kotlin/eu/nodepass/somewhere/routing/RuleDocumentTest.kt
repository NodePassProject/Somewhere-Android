// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What a rule document is allowed to be. */
class RuleDocumentTest {
    @Test
    fun aDocumentOfEverySupportedKindParses() {
        val parsed =
            RuleDocument
                .parse(
                    """
                    # a comment
                    DOMAIN,exact.example.com,DIRECT
                    DOMAIN-SUFFIX,example.com,TUNNEL
                    DOMAIN-KEYWORD,ads,REJECT
                    IP-CIDR,10.0.0.0/8,DIRECT
                    IP-CIDR6,2001:db8::/32,DIRECT

                    """.trimIndent(),
                ).getOrThrow()
        assertEquals(5, parsed.rules.size)
        assertTrue(parsed.unsupported.isEmpty())
    }

    @Test
    fun proxyIsAcceptedOnTheWayInAndTunnelIsWhatItMeans() {
        val parsed = RuleDocument.parse("DOMAIN-SUFFIX,example.com,PROXY").getOrThrow()
        assertEquals(RouteAction.Tunnel, parsed.rules.single().action)
    }

    @Test
    fun aRuleKindThisClientDoesNotImplementIsCountedRatherThanDropped() {
        // Real lists contain these. Ignoring them silently would leave the user
        // routing against a set they believe is loaded.
        val parsed =
            RuleDocument
                .parse(
                    """
                    GEOIP,CN,DIRECT
                    GEOIP,US,PROXY
                    PROCESS-NAME,ssh,DIRECT
                    DOMAIN-SUFFIX,example.com,DIRECT
                    """.trimIndent(),
                ).getOrThrow()
        assertEquals(1, parsed.rules.size)
        assertEquals(mapOf("GEOIP" to 2, "PROCESS-NAME" to 1), parsed.unsupported)
    }

    @Test
    fun aLineThatIsNotARuleFailsTheWholeDocumentWithItsNumber() {
        // Never half-applied: "which half" is not a question anybody can answer
        // after the traffic has gone somewhere.
        val outcome =
            RuleDocument.parse(
                """
                DOMAIN-SUFFIX,example.com,DIRECT
                this is not a rule
                DOMAIN-SUFFIX,other.example,DIRECT
                """.trimIndent(),
            )
        assertTrue(outcome.isFailure)
        assertTrue(
            "the reason must name the line: ${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull()?.message?.contains("line 2") == true,
        )
    }

    @Test
    fun anUnknownActionAndAMissingFieldBothFail() {
        assertTrue(RuleDocument.parse("DOMAIN-SUFFIX,example.com,SIDEWAYS").isFailure)
        assertTrue(RuleDocument.parse("DOMAIN-SUFFIX,example.com").isFailure)
        assertTrue(RuleDocument.parse("DOMAIN-SUFFIX,,DIRECT").isFailure)
    }

    @Test
    fun aDocumentThatParsesButCannotBecomeARuleSetFailsTheImport() {
        // A malformed CIDR is a valid-looking line and an impossible rule. It
        // fails here rather than at the first lookup after it.
        assertTrue(RuleDocument.parse("IP-CIDR,10.0.0.0/33,DIRECT").isFailure)
    }

    @Test
    fun aSubscriptionOrNodeUrlIsRefusedRatherThanStored() {
        // Pasting a link into a rule box is an ordinary mistake. The link is a
        // credential, and a rule file has no business holding one.
        for (
        line in
        listOf(
            "nowhere://secret@portal.example:20001?up=tcp",
            "https://dash.example/sub/portal?token=abcdef",
            "DOMAIN-SUFFIX,nowhere://x,DIRECT",
        )
        ) {
            val outcome = RuleDocument.parse(line)
            assertTrue("$line should be refused", outcome.isFailure)
        }
    }

    @Test
    fun aDocumentPastTheSizeLimitIsRefusedRatherThanRead() {
        val huge = "DOMAIN-SUFFIX,example.com,DIRECT\n".repeat(400_000)
        assertTrue(huge.length > RuleDocument.MAX_DOCUMENT_BYTES)
        assertTrue(RuleDocument.parse(huge).isFailure)
    }

    @Test
    fun commentsAndBlankLinesCostNothing() {
        val parsed = RuleDocument.parse("\n\n  # only a comment\nDOMAIN,a.example,DIRECT # trailing\n").getOrThrow()
        assertEquals(1, parsed.rules.size)
        assertEquals("a.example", parsed.rules.single().value)
    }

    @Test
    fun arbitraryBytesNeitherCrashNorImport() {
        val random = Random(20260827)
        repeat(2_000) {
            val text =
                (1..random.nextInt(0, 200))
                    .map { (random.nextInt(32, 127)).toChar() }
                    .joinToString("")
            val outcome = RuleDocument.parse(text)
            // Either it is refused, or every rule it produced is one this
            // client can act on. There is no third answer.
            outcome.getOrNull()?.rules?.forEach { rule ->
                assertTrue("a parsed rule must have a value", rule.value.isNotEmpty())
            }
        }
    }
}
