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
import eu.nodepass.somewhere.nodes.Attempt
import eu.nodepass.somewhere.nodes.Failover
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.session.SplitCarrier
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.quic.QuicConnection
import eu.nodepass.somewhere.quic.QuicStreamTransport
import eu.nodepass.somewhere.routing.BundledRules
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
 * The TUN announces **its own resolvers**, from [TunConfiguration], and that is
 * what makes the fake-IP layer work at all. Android's resolver does not simply emit a UDP
 * packet and let the routing table decide: it asks `netd`, and `netd` queries
 * the servers configured for the network the app is on. A tunnel that announces
 * none leaves those queries on the underlying network, where this client never
 * sees them — the names would be resolved on the device, and every flow would
 * open to an address again.
 *
 * Announcing one has a cost, and it is paid in [underlyingResolvers]. Those
 * addresses exist nowhere but inside this TUN, so a query that the interceptor
 * declines cannot simply be forwarded to where it was addressed. The device's **own** resolvers
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

        // Addresses, routes, resolvers and MTU live in [TunConfiguration],
        // which is a value a test can read. What is left here is applying it.

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
    private var quic: ReconnectingQuic? = null

    /**
     * Names, held for as long as the tunnel is up and no longer.
     *
     * Per tunnel rather than per process: a synthetic address means nothing
     * outside the session that minted it, and a pool that outlived one would
     * hand the next session mappings whose flows are gone.
     */
    private val fakeIp = FakeIpPool()

    /**
     * Nodes this connection attempt has already used.
     *
     * Reset by every start that arrives as an intent, and carried only across
     * the internal failover below. Without it, two nodes that each fail over to
     * the other spin until something else notices.
     */
    private var tried: Set<String> = emptySet()

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
                    // A start that came through an intent is a fresh attempt,
                    // so whatever a previous one had already tried is
                    // forgotten. Only the internal failover path below keeps
                    // the set, which is what stops two nodes that each fail
                    // over to the other from spinning.
                    tried = emptySet()
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
                unreachable(url, R.string.tunnel_unresolved)
                return
            }
        if (portal == null) {
            Log.w(TAG, "cannot resolve ${node.host}")
            unreachable(url, R.string.tunnel_unresolved)
            return
        }

        // Read before the tunnel takes DNS over, because afterwards the active
        // network is this one and its only resolver is the one we announce.
        val resolvers = underlyingResolvers()

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TunConfiguration.MTU)
                // Blocking reads: a non-blocking TUN returns 0 in a tight loop
                // and burns a core doing nothing.
                .setBlocking(true)

        // Both families, addresses before routes, and a resolver per family.
        // Applied from the configuration rather than written out here so that
        // the relationships between them — a resolver that must be routed, an
        // address that must not be one the fake-IP pool could mint — stay
        // checkable by a test that needs no device.
        TunConfiguration.addresses.forEach { builder.addAddress(it.address, it.prefix) }
        TunConfiguration.routes.forEach { builder.addRoute(it.address, it.prefix) }
        TunConfiguration.dnsServers.forEach { builder.addDnsServer(it) }

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
        // `pin` works on both carriers; chain verification against an `sni`
        // name does not work on QUIC yet, and a node that asked for it is
        // refused rather than carried without it. Silently dropping it because
        // the carrier changed would be a security downgrade the user configured
        // against and has no way to observe. `sni=none` and `pin=none` are the
        // documented defaults and what NowhereDash emits, so this refuses the
        // configured case rather than the ordinary one.
        if (resolved.requiresQuic && resolved.certificateVerification is CertificateVerification.Sni) {
            Log.w(TAG, "a QUIC node asked for certificate verification, which this carrier does not do")
            TunnelController.report(TunnelState.Failed(R.string.tunnel_quic_verification_unsupported))
            descriptor.close()
            stopSelf()
            return
        }

        // A split node uses both carriers at once: one direction on QUIC, the
        // other on TLS. So a QUIC connection is opened whenever either
        // direction asks for it, which `requiresQuic` already means.
        val quicConnection =
            if (resolved.requiresQuic) {
                ReconnectingQuic {
                    QuicConnection.open(
                        remote = InetSocketAddress(portal, resolved.port),
                        alpn = resolved.alpn,
                        // No SNI: an `sni` node was refused above, so what is
                        // left is `pin` or nothing, and neither names a host to
                        // send. A literal address as SNI is both wrong and a
                        // fingerprint.
                        serverName = null,
                        pinSha256 =
                            (resolved.certificateVerification as? CertificateVerification.Pin)?.bytes,
                        protect = { socket -> protect(socket) },
                    )
                }
            } else {
                null
            }
        quic = quicConnection
        // Fail here rather than later: a tunnel that came up and then could not
        // reach its Portal is harder to read than one that never came up.
        //
        // And it is the one place a QUIC node's reachability is known at
        // startup, which is what makes failing over to another node possible
        // rather than guesswork. A refused pin is *not* unreachability — the
        // Portal answered, and every other node will answer the same way to the
        // same wrong pin — so it stops here instead of walking the list.
        try {
            quicConnection?.current()
        } catch (error: Exception) {
            Log.w(TAG, "the QUIC Portal did not answer: ${error.message}")
            descriptor.close()
            val refused = error.message?.contains("pin") == true
            if (refused) {
                (applicationContext as SomewhereApplication).nodeHealth.record(url, Attempt.Refused)
                TunnelController.report(TunnelState.Failed(R.string.tunnel_quic_unreachable))
                stopSelf()
            } else {
                unreachable(url, R.string.tunnel_quic_unreachable)
            }
            return
        }

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
                quicStreams = quicConnection?.streams(),
                // Needed by both shapes: a duplex QUIC flow sends every packet
                // as a DATAGRAM, and a split flow does it in whichever
                // direction is QUIC.
                quicDatagrams = quicConnection?.datagrams(),
                // Present together or not at all. `up != down` puts one
                // direction on each carrier, and which is which is the node's
                // choice rather than this client's.
                splitUplink =
                    if (resolved.isSplit) {
                        laneFactory(resolved.up, quicConnection!!, dialer, resolved)
                    } else {
                        null
                    },
                splitDownlink =
                    if (resolved.isSplit) {
                        laneFactory(resolved.down, quicConnection!!, dialer, resolved)
                    } else {
                        null
                    },
            )

        // Read once, at the moment the tunnel is built, exactly as the
        // per-application selection is. A rule set that changed under a live
        // flow would decide one thing for its first packet and another for its
        // last, which is not something a user could ever reason about.
        val loadedRules = application.rules.load().rules
        val bundled = BundledRules.load { assets.open(it).bufferedReader().use { reader -> reader.readText() } }
        // Imported first, bundled after. A user who imported rules has said
        // something specific; a bundled set is what this client thought before
        // being told.
        val ruleSets = listOf(loadedRules) + bundled.map { it.loaded.rules }
        val routingSettings = application.routing.load()
        val router = Router({ ruleSets }, { routingSettings.mode }, routingSettings.fallback)
        val direct = DirectDialer(protect = { socket -> protect(socket) })

        val flows =
            NowhereFlowHandler(nowhere, fakeIp, resolvers, router, direct, { pump }, TunnelController.connectionLog)
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
        // The node worked. Recorded before the state is published, so a screen
        // that reacts to Connected already sees the health that goes with it.
        (applicationContext as SomewhereApplication).nodeHealth.record(url, Attempt.Succeeded)
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

    /**
     * This node could not be reached. Try the next one, or report and stop.
     *
     * **Only unreachability reaches here.** A Portal that answered and said no
     * — a refused pin, a rejected key — is a configuration fact, and the next
     * node will answer the same way; moving on would spend the whole list on
     * one wrong character and then report the last node's failure, which is the
     * wrong node and the wrong message.
     *
     * The next attempt goes straight back into [startTunnel], which takes any
     * running tunnel down before it builds another — there is no path here that
     * leaves two descriptors or two pumps alive, which against a NO_SYS lwIP
     * would not complain and would break something else much later.
     */
    private fun unreachable(
        url: String,
        reason: Int,
    ) {
        val application = applicationContext as SomewhereApplication
        application.nodeHealth.record(url, Attempt.Unreachable)

        val attempted = tried + url
        val candidates =
            application.nodes.nodes.value
                .map { it.line }
        val next =
            Failover.next(candidates, application.nodeHealth, attempted) ?: run {
                Log.w(TAG, "no other node to try; ${attempted.size} attempted")
                TunnelController.report(TunnelState.Failed(reason))
                stopSelf()
                return
            }

        // Not written to the connection log, which is NW-A-06's record of what
        // the Portal answered flow by flow. A failover is not a flow, and
        // giving it a SetupResult it never had would make the one surface a
        // user reads for protocol answers carry something that is not one. The
        // visible signal is the node name on the Connected state, which
        // changes.
        Log.i(TAG, "failing over after ${attempted.size} node(s)")
        tried = attempted
        startTunnel(next)
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

    /**
     * A lane on whichever carrier this direction names.
     *
     * The two directions of a split flow are asymmetric on the wire but not
     * here: each is simply a transport, and which carrier provides it is read
     * off the node rather than decided by position.
     */
    private fun laneFactory(
        carrier: NextHopCarrier,
        connection: ReconnectingQuic,
        dialer: NowhereDialer,
        node: NowhereUrl,
    ): SplitCarrier.LaneFactory =
        when (carrier) {
            NextHopCarrier.Udp ->
                SplitCarrier.LaneFactory {
                    val live = connection.current()
                    QuicStreamTransport(live, live.openStream())
                }
            NextHopCarrier.Tcp ->
                SplitCarrier.LaneFactory {
                    when (val transport = dialer.connect(node)) {
                        is DecodeResult.Ok -> transport.value
                        is DecodeResult.Invalid -> error(transport.reason.detail)
                    }
                }
        }

    /**
     * A QUIC connection that comes back after the path under it goes away.
     *
     * A network change is not something QUIC recovers from here: the connection
     * was built on a path that no longer exists, ngtcp2 gives up, and every
     * later call answers the same way. Without this the tunnel stays up and
     * carries nothing — which is worse than failing, because a user watching a
     * "Connected" screen has no reason to restart it.
     *
     * **A rebuilt connection is a new one and has never authenticated**, which
     * is why the stream factory reports a generation. The carrier remembers
     * which one it authenticated on, so the first flow after a rebuild carries
     * an AuthFrame again. Getting that wrong produces a tunnel that survives a
     * network change and then carries nothing, for the opposite reason.
     */
    private class ReconnectingQuic(
        private val open: () -> QuicConnection,
    ) {
        private val lock = Any()
        private var connection: QuicConnection? = null
        private var generation = 0

        fun current(): QuicConnection =
            synchronized(lock) {
                connection?.takeIf { it.isAlive }?.let { return it }
                runCatching { connection?.close() }
                generation++
                Log.i(TAG, "opening QUIC connection, generation $generation")
                open().also {
                    it.completeHandshake()
                    connection = it
                }
            }

        fun streams(): QuicCarrier.StreamFactory =
            object : QuicCarrier.StreamFactory {
                override fun open(): Transport {
                    val live = current()
                    return QuicStreamTransport(live, live.openStream())
                }

                override fun generation(): Int = synchronized(lock) { generation }
            }

        /**
         * The datagram side, resolved on every call.
         *
         * Held as an interface rather than as the connection so that a rebuild
         * is invisible to the carrier above: the lane it owns keeps working
         * against whatever connection is current.
         */
        fun datagrams(): QuicCarrier.Datagrams =
            object : QuicCarrier.Datagrams {
                override fun send(bytes: ByteArray) = current().sendDatagram(bytes)

                override fun receive(timeoutMillis: Long): ByteArray? = current().receiveDatagram(timeoutMillis)

                override fun maxDatagram(): Int = current().maxDatagramSize()
            }

        fun close() =
            synchronized(lock) {
                runCatching { connection?.close() }
                connection = null
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
