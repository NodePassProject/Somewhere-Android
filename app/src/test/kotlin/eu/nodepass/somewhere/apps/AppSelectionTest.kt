// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a stored selection becomes when it meets a real device. */
class AppSelectionTest {
    private val self = "eu.nodepass.somewhere"
    private val installed = setOf(self, "com.example.one", "com.example.two")

    @Test
    fun carryingEverythingStillMeansEverythingExceptThisClient() {
        // NW-A-04. There is no mode that puts this app inside its own tunnel,
        // which is why AppRule has no Everything case to return here.
        val rule = AppSelection(SelectionMode.AllButThese).ruleFor(installed, self)
        assertEquals(AppRule.AllButThese(setOf(self)), rule)
    }

    @Test
    fun theClientCannotBeSelectedIntoItsOwnTunnel() {
        val rule =
            AppSelection(SelectionMode.OnlyThese, setOf(self, "com.example.one"))
                .ruleFor(installed, self)
        assertEquals(AppRule.OnlyThese(setOf("com.example.one")), rule)
    }

    @Test
    fun theClientIsAlwaysAmongTheExcludedEvenWhenNobodyAskedForIt() {
        val rule =
            AppSelection(SelectionMode.AllButThese, setOf("com.example.one"))
                .ruleFor(installed, self)
        assertEquals(AppRule.AllButThese(setOf("com.example.one", self)), rule)
    }

    @Test
    fun aPackageThatIsNoLongerInstalledIsDroppedRatherThanPassedToTheBuilder() {
        // Both builder calls throw NameNotFoundException for a package the
        // device does not have, and that exception does not fail the
        // selection — it fails establish(), taking the tunnel with it.
        val only =
            AppSelection(SelectionMode.OnlyThese, setOf("com.example.one", "com.example.gone"))
                .ruleFor(installed, self)
        assertEquals(AppRule.OnlyThese(setOf("com.example.one")), only)

        val allBut =
            AppSelection(SelectionMode.AllButThese, setOf("com.example.two", "com.example.gone"))
                .ruleFor(installed, self)
        assertEquals(AppRule.AllButThese(setOf("com.example.two", self)), allBut)
    }

    @Test
    fun selectingNothingAtAllIsRepresentableAndSaysSo() {
        // A tunnel that carries nothing is a strange thing to have asked for
        // and exactly what was asked for. It is reported rather than quietly
        // turned into its opposite.
        val rule = AppSelection(SelectionMode.OnlyThese, emptySet()).ruleFor(installed, self)
        assertTrue(rule.carriesNothing)
        assertFalse(AppSelection(SelectionMode.AllButThese).ruleFor(installed, self).carriesNothing)
        assertFalse(
            AppSelection(SelectionMode.AllButThese, setOf("com.example.one"))
                .ruleFor(installed, self)
                .carriesNothing,
        )
    }
}
