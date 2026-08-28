// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What ships in the APK, checked against what it claims to be.
 *
 * The bundled set makes two promises that nothing enforces on its own: that it
 * carries a provenance header, and that it names no country and no service.
 * The second is the whole reason D-14's cost is not being paid, so it is
 * checked rather than asserted in a comment — a later edit adding one line
 * would otherwise change what this project publishes without changing anything
 * a reviewer looks at.
 */
class BundledRulesTest {
    private val assets = File("src/main/assets/rules")

    private fun bundled(): List<File> = assets.listFiles { file -> file.extension == "list" }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun thereIsAtLeastOneBundledSet() {
        assertTrue("no rule set ships at all; D-14 says one does", bundled().isNotEmpty())
    }

    @Test
    fun everyBundledSetCarriesACompleteProvenanceHeader() {
        bundled().forEach { file ->
            val provenance = BundledRules.provenanceOf(file.readText())
            BundledRules.REQUIRED_HEADERS.forEach { key ->
                assertTrue("${file.name} declares no $key", provenance[key]?.isNotBlank() == true)
            }
        }
    }

    @Test
    fun everyBundledSetParsesAndEveryRuleIsSupported() {
        bundled().forEach { file ->
            val parsed = RuleDocument.parse(file.readText()).getOrThrow()
            assertTrue("${file.name} is empty", parsed.rules.isNotEmpty())
            assertEquals(
                "${file.name} carries rule kinds this client does not implement",
                emptyMap<String, Int>(),
                parsed.unsupported,
            )
        }
    }

    @Test
    fun everyBundledRuleIsDirect() {
        // Tier one exists to keep traffic *out* of the tunnel. A TUNNEL or
        // REJECT rule here would be this client deciding where someone's
        // traffic goes, which is the thing the tier does not do.
        bundled().forEach { file ->
            RuleDocument.parse(file.readText()).getOrThrow().rules.forEach { rule ->
                assertEquals("${file.name} routes ${rule.value} somewhere", RouteAction.Direct, rule.action)
            }
        }
    }

    /**
     * The claim that D-14's cost is not being paid, as a test.
     *
     * Every address must be inside a range IANA has reserved for something
     * other than the public internet, and every name must be one that only ever
     * means the local network. A country's address block or a service's domain
     * would fail this, which is the point.
     */
    @Test
    fun theBundledSetNamesNoCountryAndNoService() {
        val allowedNames = setOf("local", "localhost", "home.arpa", "lan", "internal")
        bundled().forEach { file ->
            RuleDocument.parse(file.readText()).getOrThrow().rules.forEach { rule ->
                when (rule.type) {
                    RuleType.IpCidr ->
                        assertTrue(
                            "${file.name} routes ${rule.value}, which is publicly routable address space",
                            isReserved(rule.value),
                        )
                    RuleType.DomainSuffix, RuleType.DomainExact ->
                        assertTrue(
                            "${file.name} names ${rule.value}, which is not a local-network name",
                            rule.value in allowedNames,
                        )
                    RuleType.DomainKeyword ->
                        assertFalse("a keyword rule cannot be shown to name nothing", true)
                }
            }
        }
    }

    /**
     * Whether a CIDR lies inside space reserved for something other than the
     * public internet. Written out rather than pattern-matched: the list is the
     * claim, and a reader can check it against RFC 6890 in one pass.
     */
    private fun isReserved(cidr: String): Boolean {
        val reserved =
            listOf(
                "0.",
                "10.",
                "127.",
                "169.254.",
                "192.0.2.",
                "198.51.100.",
                "203.0.113.",
                "224.",
                "225.",
                "226.",
                "227.",
                "228.",
                "229.",
                "230.",
                "231.",
                "232.",
                "233.",
                "234.",
                "235.",
                "236.",
                "237.",
                "238.",
                "239.",
                "255.255.255.255",
            )
        if (reserved.any { cidr.startsWith(it) }) return true
        // 172.16.0.0/12 and 100.64.0.0/10 need their prefix length read.
        if (cidr == "172.16.0.0/12" || cidr == "100.64.0.0/10" || cidr == "192.168.0.0/16") return true
        return false
    }
}
