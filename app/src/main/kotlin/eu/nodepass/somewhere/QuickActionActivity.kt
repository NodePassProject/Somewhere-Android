// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import eu.nodepass.somewhere.ui.AppLanguage
import eu.nodepass.somewhere.vpn.SomewhereVpnService
import eu.nodepass.somewhere.vpn.TunnelController

/**
 * Connect, disconnect or toggle, with no screen: what a launcher shortcut and
 * an automation app ask for.
 *
 * ## Two doors, one room
 *
 * The launcher's shortcuts reach this activity directly. It is not exported,
 * and a shortcut is started with this app's own identity, so nothing else can
 * use that door.
 *
 * Other apps reach it through [AUTOMATION_ALIAS], an exported alias, and that
 * door is shut unless the user opened it in Settings. An exported stop that
 * any installed app could call silently would be a tunnel any app can turn off
 * — a leak with a convenience attached — so it is off by default and the
 * switch says what it allows.
 *
 * ## What it decides
 *
 * The same thing Home and the tile decide: the chosen node, or automatic
 * selection, failing over only when automatic. It never asks for VPN consent:
 * a system dialog cannot be shown from here with nothing behind it, so a start
 * without consent opens the app, where it can.
 */
class QuickActionActivity : Activity() {
    // The app's language below Android 13, where the platform does not keep it.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val application = applicationContext as SomewhereApplication
        val viaAlias = intent?.component?.className == AUTOMATION_ALIAS
        if (viaAlias && !application.settings.settings.value.automation) {
            Log.i(TAG, "an automation request was refused: the switch is off")
            return
        }
        val engaged = TunnelController.isEngaged
        val wantUp =
            when (intent?.action) {
                ACTION_START -> true
                ACTION_STOP -> false
                ACTION_TOGGLE -> !engaged
                else -> return
            }
        if (!wantUp) {
            if (engaged) SomewhereVpnService.stop(this)
            return
        }
        if (engaged) return
        val target = application.nodes.target()
        if (target == null || VpnService.prepare(this) != null) {
            // No node to use, or no consent yet: both need the app.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        SomewhereVpnService.start(this, target.url, application.nodes.effectiveChoice().mayFailOver)
    }

    companion object {
        private const val TAG = "SomewhereQuickAction"

        const val ACTION_START = "eu.nodepass.somewhere.action.START"
        const val ACTION_STOP = "eu.nodepass.somewhere.action.STOP"
        const val ACTION_TOGGLE = "eu.nodepass.somewhere.action.TOGGLE"

        /** The exported alias other apps use. Its name is how a request says which door it came in by. */
        const val AUTOMATION_ALIAS = "eu.nodepass.somewhere.AutomationActivity"
    }
}
