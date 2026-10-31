// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The policy tier: the mainland-China set, generated from `tools/rules/SOURCES`.
 *
 * `BundledRulesTest` keeps the structural tier honest — no country, no service
 * — and reads only the top level of `rules/`, so this set is outside its claim by where it
 * lives. What this checks instead is what the policy tier promises: provenance,
 * only DIRECT, the counts its header states, and that it stays out of every
 * mode it was not switched on for.
 */
class PolicyRulesTest {
    private val asset = File("src/main/assets/rules/policy/mainland-china.list")
    private val text = asset.readText()
    private val parsed = RuleDocument.parse(text).getOrThrow()
    private val rules = RoutingRules.of(parsed.rules).getOrThrow()

    private fun ip(text: String) = RoutingRules.parseAddress(text)!!

    @Test
    fun itCarriesACompleteProvenanceHeaderNamingItsGenerator() {
        val provenance = BundledRules.provenanceOf(text)
        BundledRules.REQUIRED_HEADERS.forEach { key -> assertTrue("no $key", provenance[key]?.isNotBlank() == true) }
        assertTrue(text.contains("tools/rules/generate-mainland-china.py"))
        assertTrue("the licence names both inputs", provenance["licence"]!!.contains("WTFPL"))
    }

    @Test
    fun everyRuleIsDirectAndSupported() {
        assertEquals(emptyMap<String, Int>(), parsed.unsupported)
        parsed.rules.forEach { assertEquals("routes ${it.value} somewhere", RouteAction.Direct, it.action) }
    }

    @Test
    fun theCountsAreTheOnesTheHeaderStates() {
        val stated =
            Regex("""# (\d+) domains, (\d+) IPv4 and (\d+) IPv6 networks""")
                .find(text)!!
                .groupValues
                .drop(1)
                .map(String::toInt)
        val domains = parsed.rules.count { it.type == RuleType.DomainSuffix }
        val cidrs = parsed.rules.filter { it.type == RuleType.IpCidr }
        assertEquals(stated, listOf(domains, cidrs.count { !it.value.contains(':') }, cidrs.count { it.value.contains(':') }))
    }

    @Test
    fun itAnswersForMainlandDestinationsAndNothingElse() {
        assertEquals(RouteAction.Direct, rules.decide("www.baidu.com"))
        assertEquals(RouteAction.Direct, rules.decide("baidu.com"))
        assertEquals(RouteAction.Direct, rules.decide("news.qq.com"))
        assertEquals(RouteAction.Direct, rules.decide("114.114.114.114".let(::ip)))
        assertNull(rules.decide("www.google.com"))
        assertNull(rules.decide("notbaidu.com"))
        assertNull(rules.decide(ip("8.8.8.8")))
        assertNull(rules.decide(ip("2001:4860:4860::8888")))
    }

    @Test
    fun theStructuralTierDoesNotIncludeIt() {
        val sets = BundledRules.load { File("src/main/assets", it).readText() }
        assertEquals(listOf(BundledRules.Tier.Structural, BundledRules.Tier.Policy), sets.map { it.tier })
        assertFalse(
            "the policy set must not be under rules/*.list, which BundledRulesTest holds to naming no country",
            File("src/main/assets/rules", asset.name).exists(),
        )
    }

    @Test
    fun keepingLocalNetworksOutDoesNotSendChinaDirectInEverythingMode() {
        // The service hands the router's `local` list the structural tier only.
        // Were it given the policy tier, Everything mode would send mainland
        // destinations around the tunnel.
        val sets = BundledRules.load { File("src/main/assets", it).readText() }
        val structural = sets.filter { it.tier == BundledRules.Tier.Structural }.map { it.loaded.rules }
        val all = sets.map { it.loaded.rules }
        val router = Router({ all }, { RoutingMode.Everything }, local = structural)
        assertEquals(
            RouteAction.Tunnel,
            router.decide(
                eu.nodepass.somewhere.protocol.target.Target
                    .Domain("www.baidu.com", 443),
            ),
        )
        assertEquals(
            RouteAction.Direct,
            router.decide(
                eu.nodepass.somewhere.protocol.target.Target
                    .Domain("printer.local", 631),
            ),
        )
        val rulesMode = Router({ all }, { RoutingMode.Rules }, local = structural)
        assertEquals(
            RouteAction.Direct,
            rulesMode.decide(
                eu.nodepass.somewhere.protocol.target.Target
                    .Domain("www.baidu.com", 443),
            ),
        )
        assertEquals(
            RouteAction.Tunnel,
            rulesMode.decide(
                eu.nodepass.somewhere.protocol.target.Target
                    .Domain("www.google.com", 443),
            ),
        )
    }

    @Test
    fun anOldPreferencesFileReadsTheSetAsOn() {
        val folder =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        val file = File(folder, "routing.txt").apply { writeText("Rules,Tunnel\n") }
        assertTrue(RoutingPreferences(file).load().mainlandChina)
        RoutingPreferences(file).save(RoutingPreferences.Settings(RoutingMode.Rules, mainlandChina = false))
        assertFalse(RoutingPreferences(file).load().mainlandChina)
        folder.deleteRecursively()
    }

    @Test
    fun theTrustedParserReadsEveryShippedAssetExactlyAsTheImportParserDoes() {
        // The fast path skips the import's defences because the assets are
        // checked here, by the slow one. That bargain holds only if the two
        // agree, rule for rule, on everything that ships.
        File("src/main/assets/rules").walk().filter { it.extension == "list" }.forEach { file ->
            val text = file.readText()
            assertEquals(file.name, RuleDocument.parse(text).getOrThrow().rules, RuleDocument.parseTrusted(text))
        }
    }

    @Test
    fun theOnePassCompilerTakesTheGeneratedSetAndAgreesWithTheOrdinaryPath() {
        val compiled = RoutingRules.compileTrusted(text)
        assertTrue("the generated set must take the fast path; check the generator's ordering", compiled != null)
        assertEquals(rules.size, compiled!!.size)
        val names =
            parsed.rules.filter { it.type == RuleType.DomainSuffix }.map { it.value }.let { all ->
                all.filterIndexed { index, _ -> index % 97 == 0 }
            }
        for (name in names + listOf("www.baidu.com", "x.news.qq.com", "www.google.com", "notbaidu.com", "cn")) {
            assertEquals(name, rules.decide(name), compiled.decide(name))
            assertEquals("sub.$name", rules.decide("sub.$name"), compiled.decide("sub.$name"))
        }
        for (address in listOf("114.114.114.114", "8.8.8.8", "223.5.5.5", "240e::1", "2001:4860:4860::8888")) {
            assertEquals(address, rules.decide(ip(address)), compiled.decide(ip(address)))
        }
    }

    @Test
    fun theOnePassCompilerRefusesWhatItCannotVouchFor() {
        assertNull(RoutingRules.compileTrusted("DOMAIN-SUFFIX,b.com,DIRECT\nDOMAIN-SUFFIX,a.com,DIRECT\n"))
        assertNull(RoutingRules.compileTrusted("DOMAIN-SUFFIX,A.com,DIRECT\n"))
        assertNull(RoutingRules.compileTrusted("DOMAIN-SUFFIX,a.com,TUNNEL\n"))
        assertNull(RoutingRules.compileTrusted("DOMAIN-KEYWORD,a,DIRECT\n"))
        assertNull(RoutingRules.compileTrusted("IP-CIDR,1.0.0.0/8,DIRECT\nDOMAIN-SUFFIX,a.com,DIRECT\n"))
        assertTrue(RoutingRules.compileTrusted("# header\n\nDOMAIN-SUFFIX,a.com,DIRECT\nIP-CIDR,1.0.0.0/8,DIRECT\n") != null)
    }
}
