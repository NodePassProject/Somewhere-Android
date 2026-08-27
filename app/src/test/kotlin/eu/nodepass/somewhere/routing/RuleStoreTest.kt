// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Importing a rule set, and what happens when the import is bad. */
class RuleStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(name: String = "rules.txt") = RuleStore(File(folder.root, name))

    private val good =
        """
        DOMAIN-SUFFIX,example.com,DIRECT
        IP-CIDR,10.0.0.0/8,DIRECT
        """.trimIndent()

    @Test
    fun anImportedDocumentIsLoadedBackWithItsCount() {
        val store = store()
        val imported = store.import(good).getOrThrow()
        assertEquals(2, imported.count)

        val loaded = store.load()
        assertEquals(2, loaded.count)
        assertEquals(RouteAction.Direct, loaded.rules.decide("www.example.com"))
    }

    @Test
    fun nothingIsStoredUntilThereIsSomethingToStore() {
        val loaded = store().load()
        assertEquals(0, loaded.count)
        assertNull("an empty set decides nothing", loaded.rules.decide("example.com"))
    }

    @Test
    fun aBadDocumentLeavesThePreviousOneExactlyAsItWas() {
        val store = store()
        store.import(good).getOrThrow()

        val outcome = store.import("DOMAIN-SUFFIX,other.example,DIRECT\nnot a rule\n")
        assertTrue(outcome.isFailure)

        val loaded = store.load()
        assertEquals("the previous set must be untouched", 2, loaded.count)
        assertEquals(RouteAction.Direct, loaded.rules.decide("www.example.com"))
        assertNull("and the failed import must not have partly applied", loaded.rules.decide("other.example"))
    }

    @Test
    fun anInterruptedWriteLeavesThePreviousDocument() {
        val file = File(folder.root, "rules.txt")
        val store = RuleStore(file)
        store.import(good).getOrThrow()

        File(folder.root, "rules.txt.tmp").writeText("DOMAIN-SUFFIX,interrupted.example,REJECT\n")
        val loaded = store.load()
        assertEquals(2, loaded.count)
        assertNull(loaded.rules.decide("interrupted.example"))
    }

    @Test
    fun aStoredDocumentThatNoLongerParsesReadsAsNoRules() {
        val file = File(folder.root, "rules.txt")
        file.writeText("this was written by something else\n")
        val loaded = RuleStore(file).load()
        assertEquals(0, loaded.count)
        assertNull(loaded.rules.decide("example.com"))
    }

    @Test
    fun unsupportedKindsSurviveTheRoundTripSoTheScreenCanSaySo() {
        val store = store()
        store.import("GEOIP,CN,DIRECT\nDOMAIN-SUFFIX,example.com,DIRECT\n").getOrThrow()
        assertEquals(mapOf("GEOIP" to 1), store.load().unsupported)
    }

    @Test
    fun clearingRemovesTheRulesAndNothingElse() {
        val store = store()
        store.import(good).getOrThrow()
        assertTrue(store.clear())
        assertEquals(0, store.load().count)
        assertFalse("clearing an empty store is not a failure", !store.clear())
    }
}
