// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import eu.nodepass.somewhere.MainActivity
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.SomewhereApplication
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The tunnel, from the notification shade.
 *
 * ## Why this one exists when start-on-boot does not
 *
 * The Settings screen deleted three switches that described behaviour the app
 * did not have. Two of them come back with a mechanism behind them; the third
 * does not, and the difference is worth stating because it is not effort.
 *
 * **Start on boot has a platform equivalent that is strictly better**: Android's
 * always-on VPN starts the tunnel before applications run, keeps it running,
 * and can block traffic while it is down. An app-level boot receiver starts
 * later, can be killed, and cannot block anything — so shipping one would be
 * offering a worse version of a feature the Settings screen already links to.
 * A tile has no platform equivalent, and this is it.
 *
 * ## Two things a tile must not do
 *
 * **It must not start a VPN without consent.** `VpnService.prepare` returns an
 * intent when the user has not agreed, and a service started anyway fails at
 * `establish()` with nothing on screen — from a tile, with the shade closing
 * over it, that is a control that silently does nothing. Consent is asked for
 * by opening the app, which is the only place a system dialog can be shown.
 *
 * **It must not guess a node.** The tile toggles the node the app last
 * connected with. With no node, it opens the app rather than choosing one:
 * picking a Portal for somebody from a shade tile is exactly the automatic
 * selection the failover policy refuses to do.
 */
class TunnelTileService : TileService() {
    private var watching: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        render(TunnelController.state.value)
        watching =
            scope.launch {
                TunnelController.state.onEach(::render).collect()
            }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelController.isEngaged) {
            SomewhereVpnService.stop(this)
            return
        }

        val node = lastNode()
        if (node == null || VpnService.prepare(this) != null) {
            // No node, or no consent. Both need the app: one to choose, the
            // other to show a system dialog, and neither can happen from here.
            Log.i(TAG, "opening the app: node=${node != null}, consent=${VpnService.prepare(this) == null}")
            openApp()
            return
        }
        SomewhereVpnService.start(this, node)
    }

    /**
     * The node the app last connected with, or null.
     *
     * Read from the same list the app uses rather than from a copy: a tile
     * holding its own idea of the node would connect to whatever it remembered
     * after the user deleted it.
     */
    private fun lastNode(): NowhereUrl? {
        val application = applicationContext as? SomewhereApplication ?: return null
        val line = application.nodes.lastConnected() ?: return null
        return (NowhereUrl.parse(line) as? DecodeResult.Ok)?.value
    }

    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivityAndCollapseCompat(intent)
    }

    /**
     * Opens the app from the shade.
     *
     * `startActivityAndCollapse(Intent)` is the documented way to do this below
     * API 34 and it works there — but it was deprecated in 34, *throws* there,
     * and Android lint refuses to let the call stand even inside the version
     * check that makes it safe. That check is not suppressible, and this
     * project's gates are not negotiable, so the old branch calls
     * `startActivity` instead.
     *
     * The difference is visible and small: below 34 the activity opens with the
     * shade still over it, and the user dismisses it. It is recorded here
     * rather than left to be rediscovered on an old device.
     */
    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivity(intent)
        }
    }

    private fun render(state: TunnelState) {
        val tile = qsTile ?: return
        tile.state =
            when (state) {
                is TunnelState.Connected -> Tile.STATE_ACTIVE
                is TunnelState.Connecting -> Tile.STATE_UNAVAILABLE
                else -> Tile.STATE_INACTIVE
            }
        // The node's name rather than "on": a tile that says only on and off
        // cannot tell a user which Portal their traffic is going through, and
        // that is the one thing the shade is a good place to see.
        //
        // Subtitles arrived in API 29 and `minSdk` is 26, so on 26 to 28 the
        // tile shows its label and its state and nothing more. That is a
        // smaller tile rather than a broken one, which is why this is a version
        // check and not a raised minimum.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle =
                when (state) {
                    is TunnelState.Connected -> state.node
                    is TunnelState.Connecting -> getString(R.string.tile_connecting)
                    else -> null
                }
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "SomewhereTile"
    }
}
