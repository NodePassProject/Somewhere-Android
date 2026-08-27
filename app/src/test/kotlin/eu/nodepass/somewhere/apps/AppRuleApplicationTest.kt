// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What reaches the tunnel builder, and what a change to it means. */
class AppRuleApplicationTest {
    /** Records both calls, so a rule reaching the wrong one is visible. */
    private class RecordingTarget : VpnAppTarget {
        val allowed = mutableListOf<String>()
        val disallowed = mutableListOf<String>()

        override fun allow(packageName: String) {
            allowed += packageName
        }

        override fun disallow(packageName: String) {
            disallowed += packageName
        }
    }

    @Test
    fun onlyTheseReachesTheAllowedCallAndNothingElse() {
        val target = RecordingTarget()
        AppRule.OnlyThese(setOf("com.example.one", "com.example.two")).applyTo(target)
        assertEquals(setOf("com.example.one", "com.example.two"), target.allowed.toSet())
        assertTrue("a builder given both calls throws", target.disallowed.isEmpty())
    }

    @Test
    fun allButTheseReachesTheDisallowedCallAndNothingElse() {
        val target = RecordingTarget()
        AppRule.AllButThese(setOf("com.example.one", "eu.nodepass.somewhere")).applyTo(target)
        assertEquals(setOf("com.example.one", "eu.nodepass.somewhere"), target.disallowed.toSet())
        assertTrue("a builder given both calls throws", target.allowed.isEmpty())
    }

    @Test
    fun everyPackageIsPassedExactlyOnce() {
        val target = RecordingTarget()
        val packages = (1..20).map { "com.example.app$it" }.toSet()
        AppRule.OnlyThese(packages).applyTo(target)
        assertEquals(packages.size, target.allowed.size)
        assertEquals(packages, target.allowed.toSet())
    }

    @Test
    fun changingTheSelectionWhileConnectedNeedsARebuildAndSaysSo() {
        val before = AppSelection(SelectionMode.Everything)
        val after = AppSelection(SelectionMode.OnlyThese, setOf("com.example.one"))
        assertEquals(SelectionChange.NeedsRestart, changeOutcome(true, before, after))
    }

    @Test
    fun changingItWhileDisconnectedIsJustStored() {
        val before = AppSelection(SelectionMode.Everything)
        val after = AppSelection(SelectionMode.OnlyThese, setOf("com.example.one"))
        assertEquals(SelectionChange.Stored, changeOutcome(false, before, after))
    }

    @Test
    fun openingTheScreenAndChangingNothingDoesNotRebuildATunnel() {
        val same = AppSelection(SelectionMode.AllButThese, setOf("com.example.one"))
        assertEquals(SelectionChange.Stored, changeOutcome(true, same, same))
    }
}
