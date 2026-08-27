// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a flow goes, and what decides it. */
class RouterTest {
    private fun router(
        mode: RoutingMode = RoutingMode.Rules,
        fallback: RouteAction = RouteAction.Tunnel,
        vararg rules: Rule,
    ) = Router({ RoutingRules.of(rules.toList()).getOrThrow() }, { mode }, fallback)

    private fun domain(host: String) = Target.Domain(host, 443)

    private fun ip(text: String) = (Target.ofIpv4(RoutingRules.parseAddress(text)!!, 443) as DecodeResult.Ok).value

    @Test
    fun everythingModeIgnoresTheRulesEntirely() {
        // The behaviour the app had before rules existed, and the fallback the
        // contingency plan leans on: it must not depend on what is loaded.
        val router =
            router(
                mode = RoutingMode.Everything,
                rules = arrayOf(Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct)),
            )
        assertEquals(RouteAction.Tunnel, router.decide(domain("www.example.com")))
        assertEquals(RouteAction.Tunnel, router.decide(ip("10.0.0.1")))
    }

    @Test
    fun aNameIsDecidedByName() {
        val router = router(rules = arrayOf(Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct)))
        assertEquals(RouteAction.Direct, router.decide(domain("www.example.com")))
    }

    @Test
    fun anAddressIsDecidedByAddress() {
        val router = router(rules = arrayOf(Rule(RuleType.IpCidr, "10.0.0.0/8", RouteAction.Direct)))
        assertEquals(RouteAction.Direct, router.decide(ip("10.1.2.3")))
    }

    @Test
    fun aDestinationNoRuleMentionsGetsTheFallback() {
        assertEquals(RouteAction.Tunnel, router(fallback = RouteAction.Tunnel).decide(domain("unmentioned.test")))
        assertEquals(RouteAction.Direct, router(fallback = RouteAction.Direct).decide(domain("unmentioned.test")))
    }

    @Test
    fun anIpLiteralIsNotDecidedByAnyNameRule() {
        // A connection that never had a name must not acquire one here. A
        // reverse lookup would put somebody else's DNS in charge of this
        // device's routing.
        val router =
            router(
                rules =
                    arrayOf(
                        Rule(RuleType.DomainSuffix, "example.com", RouteAction.Direct),
                        Rule(RuleType.DomainKeyword, "10", RouteAction.Reject),
                    ),
            )
        assertEquals(RouteAction.Tunnel, router.decide(ip("10.0.0.1")))
    }

    @Test
    fun rejectReachesTheCallerAsARuleRatherThanAsAnAbsence() {
        val router = router(rules = arrayOf(Rule(RuleType.DomainSuffix, "ads.example", RouteAction.Reject)))
        assertEquals(RouteAction.Reject, router.decide(domain("x.ads.example")))
    }
}
