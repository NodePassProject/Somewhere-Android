// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * How long a lookup takes against a realistic rule set.
 *
 * Not a benchmark harness and not trying to be: the claim this needs to
 * support is only that a routing decision is a rounding error next to the TLS
 * handshake it precedes, and an order of magnitude is enough for that. The
 * assertion is deliberately loose — a tight one would fail on a loaded CI
 * machine and teach everyone to ignore it.
 */
class RoutingBenchmark {
    @Test
    fun aMillionLookupsAgainstARealisticSetIsARoundingError() {
        val random = Random(20260827)
        val rules =
            buildList {
                repeat(40_000) { add(Rule(RuleType.DomainSuffix, "host$it.example.com", RouteAction.Direct)) }
                repeat(2_000) { add(Rule(RuleType.IpCidr, "10.${it / 256}.${it % 256}.0/24", RouteAction.Direct)) }
                repeat(200) { add(Rule(RuleType.DomainKeyword, "keyword$it", RouteAction.Reject)) }
            }
        val set = RoutingRules.of(rules).getOrThrow()
        val names = List(1_000) { "sub.host${random.nextInt(40_000)}.example.com" }
        val addresses = List(1_000) { RoutingRules.parseAddress("10.${random.nextInt(8)}.${random.nextInt(256)}.5")!! }

        // Warm the JIT, then measure. Without this the first pass measures
        // interpretation rather than the code.
        repeat(50_000) { set.decide(names[it % names.size]) }

        val started = System.nanoTime()
        repeat(500_000) { set.decide(names[it % names.size]) }
        repeat(500_000) { set.decide(addresses[it % addresses.size]) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        println("routing: 1,000,000 lookups against ${set.size} rules in $elapsedMillis ms")
        assertTrue("a million lookups took $elapsedMillis ms, which is not a rounding error", elapsedMillis < 10_000)
    }
}
