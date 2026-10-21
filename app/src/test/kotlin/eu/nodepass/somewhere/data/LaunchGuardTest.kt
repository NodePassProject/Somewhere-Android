// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchGuardTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun guard() = LaunchGuard(File(folder.root, "guard.txt"))

    @Test
    fun `a launch that survives never stops the next`() {
        repeat(10) {
            assertTrue(guard().beginAttempt())
            guard().survived()
        }
    }

    @Test
    fun `two launches that died in a row stop the third`() {
        assertTrue(guard().beginAttempt())
        assertTrue(guard().beginAttempt())
        assertFalse("two unsurvived attempts should skip the third", guard().beginAttempt())
    }

    @Test
    fun `after a skipped launch the next one tries again`() {
        guard().beginAttempt()
        guard().beginAttempt()
        guard().beginAttempt()
        assertTrue(guard().beginAttempt())
    }

    @Test
    fun `an unreadable counter is no count`() {
        File(folder.root, "guard.txt").writeText("garbage")
        assertTrue(guard().beginAttempt())
    }
}
