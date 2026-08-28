// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import eu.nodepass.somewhere.MainActivity
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.SomewhereApplication
import eu.nodepass.somewhere.apps.VpnAppTarget
import eu.nodepass.somewhere.apps.applyTo
import eu.nodepass.somewhere.apps.carriesNothing
import eu.nodepass.somewhere.apps.ruleFor
import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.quic.QuicConnection
import eu.nodepass.somewhere.quic.QuicStreamTransport
import eu.nodepass.somewhere.routing.DirectDialer
import eu.nodepass.somewhere.routing.Router
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The tunnel.
 *
 * Brings up a TUN, runs lwIP against it, and carries what comes out over
 * Nowhere. Everything mechanical lives in [TunPump]; everything protocol-shaped
 * lives in [NowhereFlowHandler]; this class is the Android lifecycle and the
 * routing configuration, which is the part that is easy to get subtly wrong and
 * impossible to unit test.
 *
 * ## Names
 *
 * The TUN announces **its own resolver**, at [TUN_DNS], and that is what makes
 * the fake-IP layer work at all. Android's resolver does not simply emit a UDP
 * packet and let the routing table decide: it asks `netd`, and `netd` queries
 * the servers configured for the network the app is on. A tunnel that announces
 * none leaves those queries on the underlying network, where this client never
 * sees them — the names would be resolved on the device, and every flow would
 * open to an address again.
 *
 * Announcing one has a cost, and it is paid two lines below. [TUN_DNS] exists
 * nowhere but inside this TUN, so a query that the interceptor declines cannot
 * simply be forwarded to where it was addressed. The device's **own** resolvers
 * are therefore read off the underlying network before the tunnel replaces
 * them, and the declined queries go there. Choosing a public resolver instead
 * would be a policy decision a tunnel has no business making; the user's
 * network already made it.
 *
 * ## What this version still does not do
 *
 * **The Portal address is resolved before the TUN comes up, and only once.**
 * Once a default route points into the tunnel, an ordinary `InetAddress` lookup
 * goes through it. Resolving first and reusing the address avoids the circle for
 * as long as the session lasts; it does not survive the Portal changing address.
 * The fake-IP layer does not help here — the Portal is where the names go, so
 * its own name cannot be one of them.
 *
 * **Whether a flow gets its own TLS connection is the node's choice.** A node
 * carrying `mux=1` multiplexes: a page with forty subresources costs ten
 * connections rather than forty. Without it, every flow is its own connection,
 * which works and is slow. The parameter is the user's — or their dashboard's —
 * and this client does not override it in either direction.
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

        /**
         * The resolver this tunnel announces.
         *
         * Inside the TUN's own subnet, so a query to it is routed here rather
         * than anywhere. Nothing listens on it in the ordinary sense — the
         * packets arrive at lwIP and are answered by
         * [NowhereFlowHandler.onUdpDatagram].
         */
        private const val TUN_DNS = "10.66.0.1"

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

    /**
     * The QUIC connection, when the node selected one. Held beside the session
     * because the session owns the carrier but not the connection underneath
     * it, and the connection has a thread that has to be stopped.
     */
    private var quic: QuicConnection? = null

    /**
     * Names, held for as long as the tunnel is up and no longer.
     *
     * Per tunnel rather than per process: a synthetic address means nothing
     * outside the session that minted it, and a pool that outlived one would
     * hand the next session mappings whose flows are gone.
     */
    private val fakeIp = FakeIpPool()

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

        // Read before the tunnel takes DNS over, because afterwards the active
        // network is this one and its only resolver is the one we announce.
        val resolvers = underlyingResolvers()

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS)
                .setMtu(TUN_MTU)
                // Blocking reads: a non-blocking TUN returns 0 in a tight loop
                // and burns a core doing nothing.
                .setBlocking(true)

        // Fixed here for the life of this descriptor: Android has no way to
        // change the per-application set on a tunnel that is already up, which
        // is why changing it elsewhere has to rebuild rather than pretend.
        //
        // Filtered against what is installed *now*, because a package that has
        // been uninstalled since it was chosen makes the builder throw, and
        // that exception does not fail the selection — it fails establish().
        val application = applicationContext as SomewhereApplication
        val rule =
            application.appSelection
                .load()
                .ruleFor(
                    installed =
                        application.installedApps
                            .candidates()
                            .map { it.packageName }
                            .toSet(),
                    self = packageName,
                )
        rule.applyTo(
            object : VpnAppTarget {
                override fun allow(packageName: String) {
                    builder.addAllowedApplication(packageName)
                }

                override fun disallow(packageName: String) {
                    builder.addDisallowedApplication(packageName)
                }
            },
        )
        if (rule.carriesNothing) {
            // Asked for by name — "only these applications", and none of them
            // are installed. Reported rather than established, because a tunnel
            // that carries nothing is indistinguishable from a broken one and
            // the user would debug the wrong thing.
            Log.w(TAG, "the selection carries no application at all")
            TunnelController.report(TunnelState.Failed(R.string.tunnel_no_apps))
            stopSelf()
            return
        }

        val descriptor = builder.establish()

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

        // A node whose carriers are `udp` is a QUIC node, and that is the
        // specification's default for both directions — so a bare
        // `nowhere://key@host:port` arrives here, not as an exotic case.
        //
        // The connection is opened once and every flow becomes a stream on it,
        // which is why `mux` has nothing to add: QUIC multiplexes by
        // construction.
        // Certificate verification is not implemented on the QUIC carrier, and
        // a node that asked for it is refused rather than carried without it.
        // The TLS path implements `pin` and `sni` with upstream's own
        // precedence (D-11); silently dropping them here because the carrier
        // changed would be a security downgrade the user configured against and
        // has no way to observe. `sni=none` and `pin=none` are the documented
        // defaults and what NowhereDash emits, so this refuses the configured
        // case rather than the ordinary one.
        if (resolved.requiresQuic && resolved.certificateVerification !is CertificateVerification.Skipped) {
            Log.w(TAG, "a QUIC node asked for certificate verification, which this carrier does not do")
            TunnelController.report(TunnelState.Failed(R.string.tunnel_quic_verification_unsupported))
            descriptor.close()
            stopSelf()
            return
        }

        val quicConnection =
            if (resolved.requiresQuic) {
                QuicConnection.open(
                    remote = InetSocketAddress(portal, resolved.port),
                    alpn = resolved.alpn,
                    // No SNI: this branch is only reached when verification is
                    // Skipped, which is what `sni=none` means.
                    serverName = null,
                    protect = { socket -> protect(socket) },
                )
            } else {
                null
            }
        quic = quicConnection
        quicConnection?.completeHandshake()

        val nowhere =
            NowhereSession(
                sharedKey = node.sharedKey,
                mux = node.mux,
                connect = {
                    when (val transport = dialer.connect(resolved)) {
                        is DecodeResult.Ok -> transport.value
                        is DecodeResult.Invalid -> error(transport.reason.detail)
                    }
                },
                quicStreams =
                    quicConnection?.let { connection ->
                        QuicCarrier.StreamFactory {
                            QuicStreamTransport(connection, connection.openStream())
                        }
                    },
            )

        // Read once, at the moment the tunnel is built, exactly as the
        // per-application selection is. A rule set that changed under a live
        // flow would decide one thing for its first packet and another for its
        // last, which is not something a user could ever reason about.
        val loadedRules = application.rules.load().rules
        val routingSettings = application.routing.load()
        val router = Router({ loadedRules }, { routingSettings.mode }, routingSettings.fallback)
        val direct = DirectDialer(protect = { socket -> protect(socket) })

        val flows = NowhereFlowHandler(nowhere, fakeIp, resolvers, router, direct) { pump }
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
        TunnelController.report(
            TunnelState.Connected(
                node = node.displayName ?: "${node.host}:${node.port}",
                sinceElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
            ),
        )
        Log.i(TAG, "tunnel up via ${node.host}:${node.port}${if (node.mux) " (mux)" else ""}")
    }

    /**
     * The resolvers the device was using before this tunnel existed.
     *
     * Reported as raw octets because that is the shape the relay path needs; an
     * empty list is an ordinary outcome on a network that advertised none, and
     * the caller says SERVFAIL rather than pretending otherwise.
     */
    private fun underlyingResolvers(): List<ByteArray> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        val servers = properties.dnsServers.mapNotNull { it.address }
        Log.i(TAG, "${servers.size} resolver(s) will carry the queries we do not answer")
        return servers
    }

    private fun teardown() {
        pump?.stop()
        handler?.shutdown()
        runCatching { session?.close() }
        // After the session, which closes the carrier that uses it: the
        // connection owns a thread, and stopping it first would leave the
        // carrier writing into a bridge whose memory has gone.
        runCatching { quic?.close() }
        // After the handlers, so that anything still releasing a hold releases
        // it into a pool that is still there.
        fakeIp.clear()
        pump = null
        handler = null
        session = null
        quic = null
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
