// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide which applications a user may route.
 *
 * All of them are pure functions of data written down here, which is why the
 * `PackageManager` adapter holds no policy: everything that could be wrong is
 * wrong in this file's subject, where a test can reach it.
 */
class AppInventoryTest {
    private val self = "eu.nodepass.somewhere"

    private fun candidate(
        packageName: String,
        label: String? = null,
        uid: Int = 10_001,
        hasInternet: Boolean = true,
    ) = AppCandidate(packageName, label, uid, hasInternet)

    @Test
    fun anApplicationThatCannotOpenASocketIsNotOffered() {
        val inventory =
            AppInventory.of(
                listOf(
                    candidate("com.example.online", "Online", hasInternet = true),
                    candidate("com.example.offline", "Offline", hasInternet = false),
                ),
                self,
            )
        assertEquals(listOf("com.example.online"), inventory.routable.map { it.packageName })
    }

    @Test
    fun theClientIsAbsentFromItsOwnListRatherThanPresentAndSwitchedOff() {
        // NW-A-04. Present-and-off would be a control that must never change.
        val inventory =
            AppInventory.of(
                listOf(candidate(self, "Somewhere"), candidate("com.example.other", "Other")),
                self,
            )
        assertEquals(listOf("com.example.other"), inventory.routable.map { it.packageName })
    }

    @Test
    fun uidsTheSystemNeverRoutesAreCountedRatherThanListed() {
        val inventory =
            AppInventory.of(
                listOf(
                    candidate("com.example.system", "System", uid = 1000),
                    candidate("com.example.shell", "Shell", uid = 2000),
                    candidate("com.example.root", "Root", uid = 0),
                    candidate("com.example.normal", "Normal", uid = 10_042),
                ),
                self,
            )
        assertEquals(listOf("com.example.normal"), inventory.routable.map { it.packageName })
        assertEquals(3, inventory.unroutableCount)
    }

    @Test
    fun anApplicationWithNoLabelFallsBackToItsPackageNameRatherThanToABlankRow() {
        val inventory =
            AppInventory.of(
                listOf(
                    candidate("com.example.unlabelled", label = null),
                    candidate("com.example.blank", label = "   "),
                ),
                self,
            )
        assertEquals(
            listOf("com.example.blank", "com.example.unlabelled"),
            inventory.routable.map { it.label },
        )
        assertTrue("no label may be blank", inventory.routable.none { it.label.isBlank() })
    }

    @Test
    fun theOrderIsTotalAndDoesNotDependOnTheOrderTheyArrivedIn() {
        val candidates =
            listOf(
                candidate("com.example.zebra", "zebra"),
                candidate("com.example.apple", "Apple"),
                // Two applications may share a label; the package name breaks
                // the tie so that the order is total rather than arbitrary.
                candidate("com.example.b", "Shared"),
                candidate("com.example.a", "Shared"),
            )
        val forwards = AppInventory.of(candidates, self).routable.map { it.packageName }
        val backwards = AppInventory.of(candidates.reversed(), self).routable.map { it.packageName }
        assertEquals("the order must not depend on the input order", forwards, backwards)
        assertEquals(
            listOf("com.example.apple", "com.example.a", "com.example.b", "com.example.zebra"),
            forwards,
        )
    }

    @Test
    fun theUnroutableUidsAreTheOnesReadOffADevice() {
        // Pinned so that widening them later is a deliberate edit with a
        // reason, not a quiet change to what the list hides.
        assertEquals(setOf(0, 1000, 2000), AppInventory.UNROUTABLE_UIDS)
    }
}
