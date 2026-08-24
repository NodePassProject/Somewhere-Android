// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionEndpointTest {
    private val allCapabilities = setOf(Capability.MUX, Capability.QUIC)

    private fun ready(
        url: String,
        version: String = "0.1.0",
        capabilities: Set<Capability> = allCapabilities,
    ): SubscriptionEndpoint.Result.Ready = SubscriptionEndpoint.prepare(url, version, capabilities) as SubscriptionEndpoint.Result.Ready

    private fun rejected(url: String): SubscriptionEndpoint.Reason =
        (SubscriptionEndpoint.prepare(url, "0.1.0", allCapabilities) as SubscriptionEndpoint.Result.Rejected).reason

    // ── Capability encoding ─────────────────────────────────────────────────

    @Test
    fun encodingIsStableRegardlessOfSetOrder() {
        val one = Capability.encode(linkedSetOf(Capability.QUIC, Capability.MUX))
        val other = Capability.encode(linkedSetOf(Capability.MUX, Capability.QUIC))
        assertEquals(one, other)
        assertEquals("mux,quic", one)
    }

    @Test
    fun encodingAnEmptySetProducesAnEmptyString() {
        assertEquals("", Capability.encode(emptySet()))
    }

    @Test
    fun capabilityTokensAreStableWireValues() {
        // Renaming any of these breaks the contract with dashboards, so they are
        // asserted literally rather than derived from the enum.
        assertEquals("mux", Capability.MUX.token)
        assertEquals("quic", Capability.QUIC.token)
    }

    // ── Capability negotiation ──────────────────────────────────────────────

    @Test
    fun appendsTypeVersionAndCapabilities() {
        val result = ready("https://dash.example/sub/portal?token=abc")
        assertEquals(
            "https://dash.example/sub/portal?token=abc&type=somewhere&ver=0.1.0&caps=mux%2Cquic",
            result.requestUrl,
        )
    }

    @Test
    fun preservesExistingParameters() {
        val result = ready("https://dash.example/sub/portal?token=abc&format=raw")
        assertTrue(result.requestUrl.contains("token=abc"))
        assertTrue(result.requestUrl.contains("format=raw"))
    }

    @Test
    fun addsAQueryWhenTheUrlHadNone() {
        val result = ready("https://dash.example/sub/portal")
        assertEquals(
            "https://dash.example/sub/portal?type=somewhere&ver=0.1.0&caps=mux%2Cquic",
            result.requestUrl,
        )
    }

    @Test
    fun replacesPreExistingClientParameters() {
        // These describe the client making the request, so a value already in the
        // URL cannot be trusted and must not survive.
        val result = ready("https://dash.example/sub?token=t&type=anywhere&ver=9.9&caps=everything")
        assertFalse(result.requestUrl.contains("type=anywhere"))
        assertFalse(result.requestUrl.contains("ver=9.9"))
        assertFalse(result.requestUrl.contains("caps=everything"))
        assertTrue(result.requestUrl.contains("type=somewhere"))
        assertTrue(result.requestUrl.contains("token=t"))
    }

    @Test
    fun encodesParameterValues() {
        val result = ready("https://dash.example/sub", version = "1.0 beta+1")
        assertTrue(result.requestUrl.contains("ver=1.0+beta%2B1"))
    }

    @Test
    fun anEmptyCapabilitySetStillSendsTheParameter() {
        // Absent and empty mean different things to a server: absent is an old
        // client, empty is a client that declares it supports neither.
        val result = ready("https://dash.example/sub", capabilities = emptySet())
        assertTrue(result.requestUrl.contains("caps="))
    }

    @Test
    fun preservesPathAndFragment() {
        val result = ready("https://dash.example/a/b/c?token=t#name")
        assertTrue(result.requestUrl.startsWith("https://dash.example/a/b/c?"))
        assertTrue(result.requestUrl.endsWith("#name"))
    }

    @Test
    fun preservesPortAndUserInfoInAuthority() {
        val result = ready("https://dash.example:8443/sub?token=t")
        assertTrue(result.requestUrl.startsWith("https://dash.example:8443/sub?"))
    }

    @Test
    fun doesNotDecodeExistingParameterValues() {
        // A round trip through decode/encode is a way to corrupt a token.
        val result = ready("https://dash.example/sub?token=a%2Bb%2Fc")
        assertTrue(result.requestUrl.contains("token=a%2Bb%2Fc"))
    }

    @Test
    fun toleratesValuelessParameters() {
        val result = ready("https://dash.example/sub?debug&token=t")
        assertTrue(result.requestUrl.contains("debug="))
    }

    // ── Transport safety ────────────────────────────────────────────────────

    @Test
    fun httpsIsNotFlaggedAsPlaintext() {
        assertFalse(ready("https://dash.example/sub?token=t").plaintextTransport)
    }

    @Test
    fun httpIsFlaggedAsPlaintext() {
        // The token crosses the network in the clear; the caller has to be told.
        assertTrue(ready("http://dash.example/sub?token=t").plaintextTransport)
    }

    @Test
    fun schemeMatchingIsCaseInsensitive() {
        assertFalse(ready("HTTPS://dash.example/sub?token=t").plaintextTransport)
        assertTrue(ready("HTTP://dash.example/sub?token=t").plaintextTransport)
    }

    // ── Rejection ───────────────────────────────────────────────────────────

    @Test
    fun rejectsNonHttpSchemes() {
        assertEquals(SubscriptionEndpoint.Reason.UNSUPPORTED_SCHEME, rejected("ftp://dash.example/sub"))
        assertEquals(SubscriptionEndpoint.Reason.UNSUPPORTED_SCHEME, rejected("nowhere://key@host:443"))
        assertEquals(SubscriptionEndpoint.Reason.UNSUPPORTED_SCHEME, rejected("file:///etc/passwd"))
    }

    @Test
    fun rejectsInputWithNoScheme() {
        assertEquals(SubscriptionEndpoint.Reason.MALFORMED, rejected("dash.example/sub?token=t"))
    }

    @Test
    fun rejectsMalformedInput() {
        assertEquals(SubscriptionEndpoint.Reason.MALFORMED, rejected("ht tp://dash.example"))
    }

    @Test
    fun rejectsAUrlWithNoHost() {
        assertEquals(SubscriptionEndpoint.Reason.MISSING_HOST, rejected("https:///sub?token=t"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        // Subscription URLs are usually pasted, and paste brings whitespace.
        val result = ready("  https://dash.example/sub?token=t\n")
        assertTrue(result.requestUrl.startsWith("https://dash.example/sub?"))
    }

    // ── Redaction ───────────────────────────────────────────────────────────

    @Test
    fun redactsTheToken() {
        val redacted = SubscriptionEndpoint.redactForLogging("https://dash.example/sub?token=s3cr3t")
        assertFalse(redacted.contains("s3cr3t"))
        assertEquals("https://dash.example/sub?token=***", redacted)
    }

    @Test
    fun redactsEveryKnownCredentialParameter() {
        val redacted =
            SubscriptionEndpoint.redactForLogging("https://d.example/s?token=a&sub=b&key=c&secret=d&page=2")
        listOf("a", "b", "c", "d").forEach { assertFalse(redacted.contains("=$it&") || redacted.endsWith("=$it")) }
        assertTrue(redacted.contains("page=2"))
    }

    @Test
    fun redactionIsCaseInsensitiveOnParameterNames() {
        val redacted = SubscriptionEndpoint.redactForLogging("https://d.example/s?TOKEN=s3cr3t")
        assertFalse(redacted.contains("s3cr3t"))
    }

    @Test
    fun redactsTheFragmentBecauseItMayCarryANodeName() {
        val redacted = SubscriptionEndpoint.redactForLogging("https://d.example/s?token=t#MyHomeNode")
        assertFalse(redacted.contains("MyHomeNode"))
    }

    @Test
    fun redactionKeepsAUrlWithoutAQueryIntact() {
        assertEquals("https://d.example/s", SubscriptionEndpoint.redactForLogging("https://d.example/s"))
    }

    @Test
    fun unparseableInputRedactsToAPlaceholderRatherThanPassingThrough() {
        // The case where a token is most likely to be somewhere unexpected.
        assertEquals("<unparseable url>", SubscriptionEndpoint.redactForLogging("ht tp://x?token=s3cr3t"))
        assertEquals("<unparseable url>", SubscriptionEndpoint.redactForLogging("token=s3cr3t"))
    }

    @Test
    fun redactedOutputNeverContainsTheOriginalSecret() {
        val secret = "aVeryDistinctiveSecretValue"
        listOf(
            "https://d.example/s?token=$secret",
            "https://d.example/s?a=1&token=$secret&b=2",
            "http://d.example:9/s/deep/path?token=$secret#$secret",
            "not a url at all $secret",
        ).forEach { assertFalse(it, SubscriptionEndpoint.redactForLogging(it).contains(secret)) }
    }
}
