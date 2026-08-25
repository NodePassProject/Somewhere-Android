// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import eu.nodepass.somewhere.MainActivity
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import java.net.InetAddress

/**
 * The tunnel.
 *
 * Brings up a TUN, runs lwIP against it, and carries what comes out over
 * Nowhere. Everything mechanical lives in [TunPump]; everything protocol-shaped
 * lives in [NowhereFlowHandler]; this class is the Android lifecycle and the
 * routing configuration, which is the part that is easy to get subtly wrong and
 * impossible to unit test.
 *
 * ## What this version does not do
 *
 * **The Portal address is resolved before the TUN comes up, and only once.**
 * Once a default route points into the tunnel, an ordinary `InetAddress` lookup
 * goes through it, reaching lwIP, which has nowhere to send a DNS query — the
 * lookup that would tell us where the Portal is needs the tunnel that needs the
 * Portal. Resolving first and reusing the address avoids the circle for as long
 * as the session lasts; it does not survive the Portal changing address, and it
 * is not a general answer. The general answer is the fake-IP DNS layer, which
 * is next.
 *
 * **UDP is dropped.** See [NowhereFlowHandler.onUdpDatagram].
 *
 * **Every flow is a fresh TLS connection.** That is what L1 is: mux is L2. A
 * page with forty subresources opens forty connections to the Portal, which
 * works and is slow.
 */
class SomewhereVpnService : VpnService() {
    companion object {
        private const val TAG = "SomewhereVpn"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION = 1

        const val ACTION_START = "eu.nodepass.somewhere.START"
        const val ACTION_STOP = "eu.nodepass.somewhere.STOP"
        const val EXTRA_NODE_URL = "node"

        /**
         * The address the TUN presents to the device.
         *
         * `10.66.0.0/24` rather than one of the ranges every other tunnel picks
         * — a collision with the device's real network makes the LAN
         * unreachable while connected, and the symptom ("my printer stopped
         * working") is never attributed to this.
         */
        private const val TUN_ADDRESS = "10.66.0.2"
        private const val TUN_PREFIX = 24
        private const val TUN_MTU = 1500

        fun start(
            context: Context,
            node: NowhereUrl,
        ) {
            context.startService(
                Intent(context, SomewhereVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_NODE_URL, node.toUrl())
                },
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SomewhereVpnService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private var pump: TunPump? = null
    private var handler: NowhereFlowHandler? = null
    private var session: NowhereSession? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
            }

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_NODE_URL)
                if (url == null) {
                    Log.w(TAG, "start with no node")
                    TunnelController.report(TunnelState.Failed(R.string.tunnel_no_node))
                    stopSelf()
                } else {
                    startTunnel(url)
                }
            }

            else -> Log.w(TAG, "unknown action ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked consent. There is no
        // tunnel any more whatever this service thinks.
        Log.i(TAG, "consent revoked")
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startTunnel(url: String) {
        // A second start while one is running would establish a second TUN and
        // leave the first pump alive against the old descriptor — two threads
        // driving one NO_SYS lwIP, which has no locks and will not complain.
        // The system delivers a repeat ACTION_START on its own after
        // START_STICKY, so this is a normal path rather than a defensive one.
        if (pump != null) {
            Log.i(TAG, "restarting an already-running tunnel")
            teardown()
        }

        TunnelController.report(TunnelState.Connecting(""))

        val node =
            when (val parsed = NowhereUrl.parse(url)) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid -> {
                    Log.w(TAG, "unparseable node: ${parsed.reason.detail}")
                    TunnelController.report(TunnelState.Failed(R.string.tunnel_bad_node))
                    stopSelf()
                    return
                }
            }

        // Before the TUN exists, while ordinary DNS still works. See the class
        // comment for why this is a stopgap rather than a design.
        val portal =
            try {
                InetAddress.getByName(node.host).hostAddress
            } catch (error: Exception) {
                Log.w(TAG, "cannot resolve ${node.host}: ${error.message}")
                TunnelController.report(TunnelState.Failed(R.string.tunnel_unresolved))
                stopSelf()
                return
            }
        if (portal == null) {
            Log.w(TAG, "cannot resolve ${node.host}")
            TunnelController.report(TunnelState.Failed(R.string.tunnel_unresolved))
            stopSelf()
            return
        }

        val descriptor =
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .setMtu(TUN_MTU)
                // Blocking reads: a non-blocking TUN returns 0 in a tight loop
                // and burns a core doing nothing.
                .setBlocking(true)
                .establish()

        if (descriptor == null) {
            // establish() returns null when consent was never granted or was
            // revoked between the dialog and here.
            Log.w(TAG, "the system refused to establish a TUN")
            TunnelController.report(TunnelState.Failed(R.string.tunnel_no_consent))
            stopSelf()
            return
        }

        val dialer = NowhereDialer(protect = { socket -> protect(socket) })
        val resolved = node.copy(host = portal)
        val nowhere =
            NowhereSession(
                sharedKey = node.sharedKey,
                connect = {
                    when (val transport = dialer.connect(resolved)) {
                        is DecodeResult.Ok -> transport.value
                        is DecodeResult.Invalid -> error(transport.reason.detail)
                    }
                },
            )

        val flows = NowhereFlowHandler(nowhere) { pump }
        val running = TunPump(descriptor, flows)

        session = nowhere
        handler = flows
        pump = running

        startForegroundCompat()
        running.start()
        // "Connected" here means the TUN is up and lwIP is running, not that
        // any flow has reached the Portal. A probe proved the address, the
        // port, TLS and the ALPN; the shared key is only tested when a flow
        // opens, and the Portal answers a wrong key with silence.
        TunnelController.report(TunnelState.Connected(node.displayName ?: "${node.host}:${node.port}"))
        Log.i(TAG, "tunnel up via ${node.host}:${node.port}")
    }

    private fun teardown() {
        pump?.stop()
        handler?.shutdown()
        runCatching { session?.close() }
        pump = null
        handler = null
        session = null
        stopForegroundCompat()
        if (TunnelController.state.value !is TunnelState.Failed) {
            TunnelController.report(TunnelState.Disconnected)
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannel()
        startForeground(NOTIFICATION, notification())
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_connected))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
