// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.data.SubscriptionStore
import eu.nodepass.somewhere.subscription.SubscriptionFetcher
import eu.nodepass.somewhere.ui.screens.ImportScreen
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The import screen with a real repository behind it, on files of its own.
 *
 * Its own directory under the app's cache rather than the app's node list: a
 * test that imported into the real list would leave nodes behind on the device
 * it ran on.
 */
class ImportFlowUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "import-flow-test").apply { deleteRecursively() }

    private val repository =
        NodeRepository(
            store = NodeStore(File(root, "nodes.txt")),
            subscriptions = SubscriptionStore(File(root, "sub.txt")),
            fetcher = SubscriptionFetcher(clientVersion = "test"),
            io = Dispatchers.IO,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @After
    fun clean() {
        root.deleteRecursively()
    }

    private val key = "0123456789abcdef0123456789abcdef"

    @Test
    fun aBatchIsSummarisedAndAddedInOneTap() {
        var closed = false
        val pasted =
            listOf(
                "nowhere://$key@fra.example.net:443#Frankfurt",
                "nowhere://$key@sgp.example.net:443?net=udp#Singapore",
                "not a link",
            ).joinToString("\n")
        compose.setContent {
            SomewhereTheme { ImportScreen(nodes = repository, link = pasted, onClose = { closed = true }) }
        }

        compose.onNodeWithText(context.getString(R.string.import_batch_summary, 2, 0)).performScrollTo().assertIsDisplayed()
        // The line that failed is named by its number, not counted.
        compose.onNodeWithText(context.getString(R.string.import_batch_line, 3, ""), substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.import_add_all)).performClick()
        compose.waitForIdle()

        assertTrue("the screen should close once the batch is added", closed)
        assertEquals(listOf("fra.example.net", "sgp.example.net"), repository.nodes.value.map { it.url.host })
    }

    @Test
    fun oneLinkIsStillReadAsOneLink() {
        compose.setContent {
            SomewhereTheme {
                ImportScreen(nodes = repository, link = "nowhere://$key@fra.example.net:443#Frankfurt", onClose = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.import_add_node)).assertIsDisplayed()
    }
}
