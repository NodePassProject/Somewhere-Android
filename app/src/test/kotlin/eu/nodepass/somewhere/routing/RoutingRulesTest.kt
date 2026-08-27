// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What matches what, and in which order. */
class RoutingRulesTest {
    private fun rules(vararg rules: Rule) = RoutingRules.of(rules.toList()).getOrThrow()

    private fun ipv4(text: String) = RoutingRules.parseAddress(text)!!

    @Test
    fun aSuffixMatchesTheNameAndItsSubdomains() {
        val set = rules(Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct))
        assertEquals(RouteAction.Direct, set.decide("example.com"))
        assertEquals(RouteAction.Direct, set.decide("www.example.com"))
        assertEquals(RouteAction.Direct, set.decide("a.b.example.com"))
    }

    @Test
    fun aSuffixIsLabelAlignedAndNotASubstring() {
        // The defect a substring search has and a reverse-label trie cannot:
        // `notexample.com` is a different label from `example.com`.
        val set = rules(Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct))
        assertNull(set.decide("notexample.com"))
        assertNull(set.decide("example.com.evil.test"))
    }

    @Test
    fun theLongestSuffixWins() {
        val set =
            rules(
                Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct),
                Rule(RuleType.DomainSuffix, "api.example.com", RouteAction.Tunnel),
            )
        assertEquals(RouteAction.Direct, set.decide("www.example.com"))
        assertEquals(RouteAction.Tunnel, set.decide("api.example.com"))
        assertEquals(RouteAction.Tunnel, set.decide("v2.api.example.com"))
    }

    @Test
    fun anExactRuleBeatsASuffixThatWouldAlsoMatch() {
        val set =
            rules(
                Rule(RuleType.DomainSuffix, "example.com", RouteAction.Tunnel),
                Rule(RuleType.DomainExact, "www.example.com", RouteAction.Direct),
            )
        assertEquals(RouteAction.Direct, set.decide("www.example.com"))
        assertEquals("a subdomain of the exact name is not the exact name", RouteAction.Tunnel, set.decide("a.www.example.com"))
    }

    @Test
    fun aKeywordIsConsultedOnlyWhenNoNameRuleMatched() {
        val set =
            rules(
                Rule(RuleType.DomainKeyword, "example", RouteAction.Reject),
                Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct),
            )
        assertEquals("the suffix answers first", RouteAction.Direct, set.decide("www.example.com"))
        assertEquals("and the keyword catches the rest", RouteAction.Reject, set.decide("example.net"))
    }

    @Test
    fun theLongerKeywordWins() {
        val set =
            rules(
                Rule(RuleType.DomainKeyword, "ads", RouteAction.Reject),
                Rule(RuleType.DomainKeyword, "adservice", RouteAction.Direct),
            )
        assertEquals(RouteAction.Direct, set.decide("adservice.test"))
        assertEquals(RouteAction.Reject, set.decide("ads.test"))
    }

    @Test
    fun aNameNothingMentionsGetsNoAnswerRatherThanADefault() {
        // What to do with an unmatched name is the caller's policy. Two callers
        // read this set — the resolver and the flow handler — and a default
        // buried here is how they would come to disagree.
        assertNull(rules().decide("example.com"))
        assertNull(RoutingRules.EMPTY.decide("anything.test"))
    }

    @Test
    fun namesAreComparedWithoutCaseOrATrailingDot() {
        val set = rules(Rule(RuleType.DomainSuffix, "Example.COM", RouteAction.Direct))
        assertEquals(RouteAction.Direct, set.decide("WWW.example.com."))
    }

    @Test
    fun anAddressInsideACidrBlockMatches() {
        val set = rules(Rule(RuleType.IpCidr, "10.0.0.0/8", RouteAction.Direct))
        assertEquals(RouteAction.Direct, set.decide(ipv4("10.0.0.1")))
        assertEquals(RouteAction.Direct, set.decide(ipv4("10.255.255.255")))
        assertNull(set.decide(ipv4("11.0.0.1")))
    }

    @Test
    fun theBoundariesOfARangeAreInsideIt() {
        val set = rules(Rule(RuleType.IpCidr, "192.0.2.0/24", RouteAction.Reject))
        assertEquals(RouteAction.Reject, set.decide(ipv4("192.0.2.0")))
        assertEquals(RouteAction.Reject, set.decide(ipv4("192.0.2.255")))
        assertNull(set.decide(ipv4("192.0.1.255")))
        assertNull(set.decide(ipv4("192.0.3.0")))
    }

    @Test
    fun theWholeSpaceAndASingleAddressBothWork() {
        val everything = rules(Rule(RuleType.IpCidr, "0.0.0.0/0", RouteAction.Tunnel))
        assertEquals(RouteAction.Tunnel, everything.decide(ipv4("8.8.8.8")))

        val single = rules(Rule(RuleType.IpCidr, "192.0.2.7/32", RouteAction.Direct))
        assertEquals(RouteAction.Direct, single.decide(ipv4("192.0.2.7")))
        assertNull(single.decide(ipv4("192.0.2.8")))
    }

    @Test
    fun theMostSpecificPrefixWins() {
        val set =
            rules(
                Rule(RuleType.IpCidr, "10.0.0.0/8", RouteAction.Direct),
                Rule(RuleType.IpCidr, "10.1.0.0/16", RouteAction.Tunnel),
            )
        assertEquals(RouteAction.Direct, set.decide(ipv4("10.2.0.1")))
        assertEquals(RouteAction.Tunnel, set.decide(ipv4("10.1.0.1")))
    }

    @Test
    fun ipv6MatchesOnItsOwnTrieAndNotTheOtherOne() {
        val set =
            rules(
                Rule(RuleType.IpCidr, "2001:db8::/32", RouteAction.Direct),
                Rule(RuleType.IpCidr, "10.0.0.0/8", RouteAction.Tunnel),
            )
        assertEquals(RouteAction.Direct, set.decide(RoutingRules.parseAddress("2001:db8::1")!!))
        assertNull(set.decide(RoutingRules.parseAddress("2001:db9::1")!!))
        assertEquals(RouteAction.Tunnel, set.decide(ipv4("10.1.2.3")))
    }

    @Test
    fun aSingleAddressAtTheFullIpv6PrefixWorks() {
        val set = rules(Rule(RuleType.IpCidr, "2001:db8::1/128", RouteAction.Reject))
        assertEquals(RouteAction.Reject, set.decide(RoutingRules.parseAddress("2001:db8::1")!!))
        assertNull(set.decide(RoutingRules.parseAddress("2001:db8::2")!!))
    }

    @Test
    fun anAddressOfTheWrongLengthMatchesNothingRatherThanCrashing() {
        val set = rules(Rule(RuleType.IpCidr, "10.0.0.0/8", RouteAction.Direct))
        assertNull(set.decide(ByteArray(0)))
        assertNull(set.decide(ByteArray(5)))
    }

    @Test
    fun aLaterRuleOverridesAnEarlierOneOnTheSameValue() {
        // The ordering the sources are loaded in: a user's own rule is loaded
        // after a bundled one and is meant to win.
        val set =
            rules(
                Rule(RuleType.DomainSuffix, "example.com", RouteAction.Tunnel),
                Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct),
            )
        assertEquals(RouteAction.Direct, set.decide("example.com"))
    }

    @Test
    fun aMalformedCidrIsRefusedWithAReasonRatherThanIgnored() {
        for (bad in listOf("10.0.0.0", "10.0.0.0/33", "10.0.0.0/-1", "not-an-address/8", "10.0.0/8", "10.0.0.0/x")) {
            val outcome = RoutingRules.of(listOf(Rule(RuleType.IpCidr, bad, RouteAction.Direct)))
            assertTrue("$bad should be refused", outcome.isFailure)
        }
    }

    @Test
    fun aLeadingZeroIsNotAnAddress() {
        // "010.0.0.1" is how an address is smuggled past a filter that parses
        // it more generously than the thing that will dial it.
        assertNull(RoutingRules.parseAddress("010.0.0.1"))
        assertNull(RoutingRules.parseAddress("10.00.0.1"))
    }

    @Test
    fun aRuleSetLargerThanTheMaximumIsRefusedRatherThanTruncated() {
        val tooMany = List(RoutingRules.MAX_RULES + 1) { Rule(RuleType.DomainSuffix, "host$it.test", RouteAction.Direct) }
        val outcome = RoutingRules.of(tooMany)
        assertTrue("a truncated rule set routes traffic nobody asked to route", outcome.isFailure)
    }

    @Test
    fun arbitraryQueriesAgainstArbitraryRulesNeitherCrashNorAllocateWithoutBound() {
        val random = Random(20260827)
        val alphabet = "abc.:/0123456789-*"
        repeat(2_000) {
            val value = (1..random.nextInt(1, 40)).map { alphabet.random(random) }.joinToString("")
            val type = RuleType.entries.random(random)
            val built = RoutingRules.of(listOf(Rule(type, value, RouteAction.Direct)))
            val set = built.getOrNull() ?: return@repeat
            val query = (1..random.nextInt(0, 60)).map { alphabet.random(random) }.joinToString("")
            set.decide(query)
            set.decide(ByteArray(random.nextInt(0, 20)) { random.nextInt(0, 256).toByte() })
        }
    }
}
