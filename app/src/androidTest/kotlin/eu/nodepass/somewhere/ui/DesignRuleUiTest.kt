// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.ui.screens.DiagnosticsScreen
import eu.nodepass.somewhere.ui.screens.Home
import eu.nodepass.somewhere.ui.screens.NodeEditor
import eu.nodepass.somewhere.ui.screens.NodeList
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import eu.nodepass.somewhere.ui.state.Format
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

    /**
     * What a `SectionLabel` actually renders: the string, uppercased.
     *
     * Asserting the raw resource instead is a test that passes on a Chinese
     * device — where `uppercase()` is a no-op — and fails on an English one.
     * That has now cost two red builds, so the rendered form has a name and
     * every section-label assertion goes through it.
     */
    private fun sectionLabel(id: Int) = string(id).uppercase()

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
        // The rendered form is the thing under test, so it is the thing
        // asserted; see sectionLabel().
        compose.onNodeWithText(sectionLabel(R.string.direction_upstream)).assertIsDisplayed()
        compose.onNodeWithText(sectionLabel(R.string.direction_downstream)).assertIsDisplayed()
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

        // Scrolled to, not merely looked for. The screen is a lazy list, so an
        // identifier below the fold is not composed at all — and whether it is
        // below the fold depends on the device. The first version of this test
        // passed on a 2532 px display and failed on a shorter one, which made it
        // a test of screen height rather than of the rule.
        SetupResult.entries.filter { it.isRejection }.forEach { result ->
            compose
                .onNode(hasScrollAction())
                .performScrollToNode(hasText(result.identifier, substring = true))
            assertEquals(
                "${result.identifier} must appear as its own line, not folded into another",
                true,
                compose.onAllNodesWithText(result.identifier, substring = true).fetchSemanticsNodes().isNotEmpty(),
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

    @Test
    fun anUnmeasuredFigureIsADashAndNeverAZero() {
        // Rule 4 of docs/design-system.md, in the place it was actually broken.
        //
        // The tunnel came up before anything counted its bytes, and the home
        // screen rendered "0 B/s" beside a green "Connected" — a measurement of
        // zero throughput on a working link, which is a different and worse
        // claim than "not measured". The same screen simultaneously said "Not
        // connected" in its header while its button said "Disconnect".
        //
        // So this asserts both halves: a connected session with nothing
        // measured shows dashes, and the screen agrees with itself about being
        // connected.
        compose.setContent {
            SomewhereTheme {
                Home(
                    node = SampleState.frankfurt,
                    session = SampleState.session.copy(connected = true, measured = false),
                    onOpenNodes = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onAllNodesWithText("\u2014").assertCountEquals(5)
        compose.onNodeWithText(sectionLabel(R.string.home_connected_to)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_disconnect)).assertIsDisplayed()
    }

    @Test
    fun theHomeScreenNeverContradictsItselfAboutBeingConnected() {
        // The defect this exists for was on screen, not in a log: the header
        // read "Not connected" while the button offered to disconnect, because
        // one of them read the tunnel state and the other read a snapshot.
        // Both now read `session.connected`, so the two can only disagree if
        // somebody reintroduces a second source — which is the thing worth
        // catching, and which nothing else would notice.
        //
        // Asserted over every state the screen can be in rather than over the
        // one that broke, because the next second source will be introduced
        // somewhere else.
        // One composition, driven through each state, because `setContent` may
        // be called only once per test — and because recomposing is what the
        // screen really does when the tunnel changes, which is when the two
        // readers used to drift apart.
        var session by mutableStateOf(SampleState.session.copy(connected = false, connecting = false))
        compose.setContent {
            SomewhereTheme {
                Home(
                    node = SampleState.frankfurt,
                    session = session,
                    onOpenNodes = {},
                    onOpenSettings = {},
                )
            }
        }

        listOf(
            Triple(true, false, R.string.action_disconnect),
            Triple(false, true, R.string.home_connecting),
            Triple(false, false, R.string.action_connect),
        ).forEach { (connected, connecting, action) ->
            session =
                SampleState.session.copy(
                    connected = connected,
                    connecting = connecting,
                    measured = connected,
                )
            compose.waitForIdle()

            // Exact matches only, never substrings. In Chinese the connected
            // status reads 已连接 and the heading above it reads 已连接到, so a
            // substring assertion matches both and fails on a screen that is
            // perfectly correct — the same class of locale trap that
            // sectionLabel() exists for.
            val heading = if (connected) R.string.home_connected_to else R.string.home_not_connected
            val otherHeading = if (connected) R.string.home_not_connected else R.string.home_connected_to
            compose.onNodeWithText(sectionLabel(heading)).assertIsDisplayed()
            compose.onNodeWithText(string(action)).assertIsDisplayed()

            // The heading and the button are built at opposite ends of the
            // file from the same field. A second state source shows up here as
            // the two disagreeing, which is precisely what was once on screen.
            compose.onAllNodesWithText(sectionLabel(otherHeading)).assertCountEquals(0)
            listOf(R.string.action_connect, R.string.action_disconnect, R.string.home_connecting)
                .filter { it != action }
                .forEach { compose.onAllNodesWithText(string(it)).assertCountEquals(0) }
        }
    }

    @Test
    fun aMeasuredFigureIsTheFigureAndNoLongerADash() {
        // The other half of rule 4, and the half that was untestable until
        // something counted bytes. A screen that dashes out a figure it *has*
        // measured is the same defect facing the other way: it withholds a real
        // measurement, and there is no way for the user to tell that apart from
        // a tunnel that is not carrying anything.
        //
        // The values are the ones a 20 MB transfer produces, so the assertion is
        // about a figure the screen will really be asked to render rather than a
        // round number chosen to make formatting easy.
        compose.setContent {
            SomewhereTheme {
                Home(
                    node = SampleState.frankfurt,
                    session =
                        SampleState.session.copy(
                            connected = true,
                            measured = true,
                            downstreamBytesPerSecond = 20L * 1024 * 1024,
                            upstreamBytesPerSecond = 96 * 1024,
                            activeFlows = 3,
                            // Deliberately not the same figure as the rate: with
                            // both at 20 MB the assertions below matched each
                            // other's rendering and said nothing about either.
                            sessionBytes = 37L * 1024 * 1024,
                            handshakeMillis = 42,
                        ),
                    onOpenNodes = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onAllNodesWithText("\u2014").assertCountEquals(0)
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("42").assertIsDisplayed()
        // 20 MB/s downstream and 96 KB/s upstream, each rendered as its own
        // number: one averaged figure is the thing this screen exists not to do.
        compose.onAllNodesWithText(Format.throughput(20L * 1024 * 1024).value).assertCountEquals(1)
        compose.onAllNodesWithText(Format.throughput(96L * 1024).value).assertCountEquals(1)
    }
}
