// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.AppSettings
import eu.nodepass.somewhere.ui.screens.SettingsScreen
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.update.Release
import eu.nodepass.somewhere.update.UpdateCheck
import eu.nodepass.somewhere.update.UpdateController
import eu.nodepass.somewhere.update.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Settings → About: the update rows say what a check found, and their switch is the setting. */
class UpdateUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun eachOutcomeIsSaidAndTappingChecks() {
        var state by mutableStateOf<UpdateController.State>(UpdateController.State.Idle)
        var checks = 0
        compose.setContent {
            SomewhereTheme { SettingsScreen(onBack = {}, updateState = state, onCheckForUpdates = { checks++ }) }
        }
        scrollTo(context.getString(R.string.update_check))
        compose.onNodeWithText(context.getString(R.string.update_check)).performClick()
        assertEquals(1, checks)

        val outcomes =
            listOf(
                UpdateCheck.Current to context.getString(R.string.update_current),
                UpdateCheck.NoRelease to context.getString(R.string.update_no_release),
                UpdateCheck.Failed("UnknownHostException") to context.getString(R.string.update_failed, "UnknownHostException"),
                UpdateCheck.Available(
                    Release(
                        "v0.2.0",
                        Version.parse("0.2.0")!!,
                        "https://github.com/NodePassProject/Somewhere-Android/releases/tag/v0.2.0",
                        "",
                        null,
                    ),
                ) to context.getString(R.string.update_found, "0.2.0"),
            )
        for ((result, text) in outcomes) {
            state = UpdateController.State.Done(result)
            scrollTo(text)
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun theSwitchIsTheSettingAndItIsOnByDefault() {
        var settings by mutableStateOf(AppSettings())
        compose.setContent {
            SomewhereTheme {
                SettingsScreen(onBack = {}, settings = settings, onSettingsChange = { change -> settings = change(settings) })
            }
        }
        assertEquals(true, settings.checkUpdates)
        val label = context.getString(R.string.update_automatic)
        scrollTo(label)
        compose.onNodeWithText(label).performClick()
        assertFalse(settings.checkUpdates)
    }
}
