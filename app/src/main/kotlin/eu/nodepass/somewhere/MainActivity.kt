// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.ui.SomewhereApp
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.vpn.SomewhereVpnService
import eu.nodepass.somewhere.vpn.TunnelController
import eu.nodepass.somewhere.vpn.TunnelState

/**
 * The single activity.
 *
 * Edge to edge on purpose: the design's screens start 52 dp from the top of the
 * display, with the status bar sitting over the app's own ground rather than in
 * a band of its own. A system bar in a different colour would put a seam across
 * every screen.
 */
class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)

    /**
     * The node waiting for the system's consent dialog to come back.
     *
     * Held here rather than passed through the intent because the contract
     * hands back only a result code. It is cleared on every outcome, including
     * refusal, so a second tap starts a fresh request rather than reusing a
     * node the user may since have changed.
     */
    private var awaitingConsent: NowhereUrl? = null

    /**
     * `VpnService.prepare()` returns an intent the first time, and null once
     * the user has agreed. Both paths end in the same place, which is why the
     * launcher and the direct start share [startTunnel].
     */
    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val node = awaitingConsent
            awaitingConsent = null
            if (result.resultCode == RESULT_OK && node != null) {
                startTunnel(node)
            } else {
                // Refusing is a decision, not an error. Saying so beats a
                // button that was pressed and did nothing.
                TunnelController.report(TunnelState.Failed(R.string.tunnel_no_consent))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingLink = linkFrom(intent)

        val repository = (application as SomewhereApplication).nodes

        setContent {
            SomewhereTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SomewhereTheme.colors.ground),
                ) {
                    SomewhereApp(
                        nodes = repository,
                        pendingLink = pendingLink,
                        onLinkHandled = { pendingLink = null },
                        onToggleTunnel = ::toggleTunnel,
                    )
                }
            }
        }
    }

    /**
     * Connect, or disconnect if something is already up.
     *
     * The consent dialog is asked for only when the system says it is needed:
     * `prepare()` returns null once the user has agreed, and asking again every
     * time would put a system dialog in front of an ordinary reconnect.
     */
    private fun toggleTunnel(node: NowhereUrl) {
        if (TunnelController.isEngaged) {
            SomewhereVpnService.stop(this)
            return
        }
        val request = VpnService.prepare(this)
        if (request == null) {
            startTunnel(node)
        } else {
            awaitingConsent = node
            consent.launch(request)
        }
    }

    private fun startTunnel(node: NowhereUrl) {
        SomewhereVpnService.start(this, node)
    }

    /**
     * The activity is `singleTop`-shaped in practice: tapping a second import
     * link while it is already open delivers here rather than to [onCreate], so
     * without this the second link would be silently dropped.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    /**
     * The link a VIEW intent carries, whatever its scheme.
     *
     * All three declared schemes are passed through unchanged rather than
     * filtered here. `nowhere://` is the one that parses; `somewhere://` and
     * `anywhere://` reach the import screen and are refused there, by the
     * parser, with its own reason — which is a better answer than an app that
     * opens and shows nothing.
     */
    private fun linkFrom(intent: Intent?): String? =
        intent
            ?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data
            ?.toString()
}
