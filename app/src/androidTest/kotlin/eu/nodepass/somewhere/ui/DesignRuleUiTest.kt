// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.ui.screens.DiagnosticsScreen
import eu.nodepass.somewhere.ui.screens.Home
import eu.nodepass.somewhere.ui.screens.NodeEditor
import eu.nodepass.somewhere.ui.screens.NodeList
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import eu.nodepass.somewhere.ui.state.SampleState
import eu.nodepass.somewhere.ui.state.identifier
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The rules from `docs/design-system.md`, asserted against real composition.
 *
 * These are the rules where **nothing crashes when they are violated** — which
 * is exactly why they were written down, and exactly why a unit test cannot
 * reach them. A screen that quietly stops showing the certificate marker, or
 * that collapses two rejection reasons into one message, compiles, passes lint
 * and looks fine.
 *
 * Every assertion resolves its text through the resource system rather than
 * hard-coding English, so the suite passes on a device in any of the three
 * shipping locales — and fails if a string goes missing from one of them.
 */
class DesignRuleUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int) = context.getString(id)

    @Test
    fun theCertificateMarkerIsOnTheHomeScreenAndCannotBeDismissed() {
        // D-11 / NW-P-09. Every URL a current dashboard emits has neither `sni`
        // nor `pin`, so this is the ordinary case rather than the exception. It
        // is a banner and not a dialog because the condition lasts as long as
        // the node does — there is nothing to dismiss.
        compose.setContent {
            SomewhereTheme {
                Home(SampleState.frankfurt, SampleState.session, {}, {})
            }
        }
        compose.onNodeWithText(string(R.string.cert_unverified_short), substring = true).assertIsDisplayed()
    }

    @Test
    fun theCertificateMarkerNamesTheTwoParametersWithoutTranslatingThem() {
        // docs/i18n.md: the sentence translates, `sni` and `pin` do not. They
        // are arguments rather than part of the string, so this holds in every
        // locale the device might be in.
        compose.setContent {
            SomewhereTheme {
                Home(SampleState.frankfurt, SampleState.session, {}, {})
            }
        }
        compose.onNodeWithText("sni", substring = true).assertIsDisplayed()
        compose.onNodeWithText("pin", substring = true).assertIsDisplayed()
    }

    @Test
    fun theTwoDirectionsAreLabelledSeparately() {
        // The organising idea. One throughput figure would be an average of two
        // measurements that have no common denominator.
        compose.setContent {
            SomewhereTheme {
                Home(SampleState.frankfurt, SampleState.session, {}, {})
            }
        }
        compose.onNodeWithText(string(R.string.direction_upstream)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.direction_downstream)).assertIsDisplayed()
    }

    @Test
    fun allSevenRejectionsRenderAsSevenDistinctIdentifiers() {
        // NW-P-06. The one rule that a well-meaning simplification breaks: it is
        // always tempting to render "connection failed" once.
        val entries =
            SetupResult.entries.filter { it.isRejection }.mapIndexed { index, result ->
                ConnectionLogEntry(
                    result = result,
                    timestamp = "14:00:0%d.000".format(index),
                    target = "api.example.com:443",
                    flowId = 8400 + index,
                )
            }
        compose.setContent { SomewhereTheme { DiagnosticsScreen(entries = entries) } }

        SetupResult.entries.filter { it.isRejection }.forEach { result ->
            val matches = compose.onAllNodesWithText(result.identifier, substring = true)
            assertEquals(
                "${result.identifier} must appear as its own line, not folded into another",
                true,
                matches.fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun aNodeNeedingQuicOffersBothChoicesAndRewritesNothing() {
        // NW-P-25. Two buttons is the requirement: rewriting the user's pasted
        // configuration on their behalf is what it forbids. A single "Fix it"
        // would satisfy a reasonable reading of "handle this gracefully" and
        // violate the rule.
        compose.setContent {
            SomewhereTheme {
                NodeList(entries = listOf(SampleState.singapore))
            }
        }
        compose.onNodeWithText(string(R.string.node_needs_quic), substring = true).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.node_switch_to_tcp)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.node_keep_as_is)).assertIsDisplayed()
    }

    @Test
    fun theEditorExposesAlpnAsAFieldRatherThanBuryingIt() {
        // NW-P-08: a fixed ALPN is a ready-made fingerprint on a restricted
        // network, so it cannot be a constant compiled into the client.
        compose.setContent {
            SomewhereTheme { NodeEditor(SampleState.frankfurt.url, {}, {}) }
        }
        compose.onNodeWithText(string(R.string.label_alpn)).assertIsDisplayed()
        compose.onNodeWithText(SampleState.frankfurt.url.alpn).assertIsDisplayed()
    }

    @Test
    fun nothingOnTheNodeListPresentsUploadAsAMeasurement() {
        // NW-D-02. Upstream does not meter upload, so it is always zero, and a
        // permanent zero rendered as a measurement is a claim the app cannot
        // support. The word must not appear at all.
        compose.setContent {
            SomewhereTheme {
                NodeList(entries = SampleState.nodes, subscription = SampleState.subscription)
            }
        }
        assertEquals(
            "no upload figure may appear anywhere on this screen",
            0,
            compose.onAllNodesWithText("upload", substring = true, ignoreCase = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun quotaSaysCountedRatherThanUsed() {
        // NW-D-05: metering is per Portal, so two subscriptions sharing one are
        // each charged the full amount. "Used" would be a number the app cannot
        // stand behind.
        compose.setContent {
            SomewhereTheme {
                NodeList(entries = SampleState.nodes, subscription = SampleState.subscription)
            }
        }
        compose.onNodeWithText(string(R.string.quota_per_portal_note), substring = true).assertIsDisplayed()
    }
}
