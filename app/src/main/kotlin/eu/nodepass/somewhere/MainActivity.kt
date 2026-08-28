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
import androidx.lifecycle.lifecycleScope
import eu.nodepass.somewhere.apps.AppsController
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.routing.RoutingController
import eu.nodepass.somewhere.ui.SomewhereApp
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.vpn.SomewhereVpnService
import eu.nodepass.somewhere.vpn.TunnelController
import eu.nodepass.somewhere.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * The node the running tunnel was started with.
     *
     * Kept so that a per-application change can rebuild the same tunnel rather
     * than asking the user which node they meant. Android fixes that set at
     * `establish()`, so rebuilding is the only way a change takes effect.
     */
    private var running: NowhereUrl? = null

    /**
     * The routing controller, once the composition has been built.
     *
     * Held as a field because the document picker is registered before
     * `onCreate` runs — an `ActivityResultLauncher` has to be, or the system
     * cannot restore it — and its callback needs somewhere to deliver a file.
     */
    private var routing: RoutingController? = null

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

        val application = application as SomewhereApplication
        val repository = application.nodes
        val apps =
            AppsController(
                store = application.appSelection,
                apps = application.installedApps,
                scope = lifecycleScope,
                io = Dispatchers.IO,
                engaged = { TunnelController.isEngaged },
            )
        val routingController =
            RoutingController(
                rules = application.rules,
                preferences = application.routing,
                scope = lifecycleScope,
                io = Dispatchers.IO,
                engaged = { TunnelController.isEngaged },
                openAsset = { assets.open(it).bufferedReader().use { reader -> reader.readText() } },
            ).also { routing = it }

        setContent {
            SomewhereTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SomewhereTheme.colors.ground),
                ) {
                    SomewhereApp(
                        nodes = repository,
                        apps = apps,
                        routing = routingController,
                        pendingLink = pendingLink,
                        onLinkHandled = { pendingLink = null },
                        onToggleTunnel = ::toggleTunnel,
                        onReconnect = { running?.let(::startTunnel) },
                        onImportRules = { importRules.launch(ruleMimeTypes) },
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
        running = node
        SomewhereVpnService.start(this, node)
    }

    /**
     * What the picker will offer.
     *
     * Rule files are text and are named `.list`, `.txt`, `.conf` and a dozen
     * other things, so the type filter is the broad one; a file that is not a
     * rule set fails at the parser with a line number, which is a better
     * answer than a picker that would not show it.
     */
    private val ruleMimeTypes = arrayOf("text/*", "application/octet-stream")

    /**
     * Reads a rule file the user picked, through the system's own picker.
     *
     * The picker rather than a text box: rule sets are thousands of lines and
     * arrive as files. The document is read here and handed to the controller
     * as text, so everything that decides whether something is a rule set
     * stays in one place, where a test can reach it.
     *
     * A file this app cannot read is reported the same way a malformed one is.
     * "Permission denied" and "line 12 is not a rule" are both answers to the
     * same question the user just asked.
     */
    private val importRules =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openInputStream(target)?.use { it.readBytes().decodeToString() }
                        }.getOrNull()
                    }
                routing?.import(text ?: "")
            }
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
