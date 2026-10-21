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
import eu.nodepass.somewhere.data.NodeStore
import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.nodes.Attempt
import eu.nodepass.somewhere.nodes.Failover
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.session.StreamTransport
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.quic.QuicConnection
import eu.nodepass.somewhere.quic.QuicDial
import eu.nodepass.somewhere.quic.QuicException
import eu.nodepass.somewhere.quic.QuicFailure
import eu.nodepass.somewhere.quic.QuicStreamTransport
import eu.nodepass.somewhere.routing.BundledRules
import eu.nodepass.somewhere.routing.DirectDialer
import eu.nodepass.somewhere.routing.Router
import eu.nodepass.somewhere.ui.state.Format
import kotlinx.coroutines.launch
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
 * **The carrier is the node's choice.** `net=tcp` gives every flow its own TLS
 * connection, which is what v3 specifies for TLS; `net=udp` carries every flow
 * as a stream on one QUIC connection. The parameter is the user's — or their
 * dashboard's — and this client does not override it in either direction.
 */
class SomewhereVpnService : VpnService() {
    companion object {
        private const val TAG = "SomewhereVpn"
        const val CHANNEL = "tunnel"
        private const val NOTIFICATION = 1

        const val ACTION_START = "eu.nodepass.somewhere.START"
        const val ACTION_STOP = "eu.nodepass.somewhere.STOP"
        const val EXTRA_NODE_URL = "node"

        /**
         * Whether an unreachable node may be swapped for another.
         *
         * True when the user left the choice to the client, false when they
         * picked this node. Defaults to true for a start that does not say,
         * which is how every start behaved before there was a choice to make.
         */
        const val EXTRA_MAY_FAIL_OVER = "may-fail-over"

        // Addresses, routes, resolvers and MTU live in [TunConfiguration],
        // which is a value a test can read. What is left here is applying it.

        fun start(
            context: Context,
            node: NowhereUrl,
            mayFailOver: Boolean = true,
        ) {
            context.startService(
                Intent(context, SomewhereVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_NODE_URL, node.toUrl())
                    putExtra(EXTRA_MAY_FAIL_OVER, mayFailOver)
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

    /** Whether this attempt may move to another node. See [EXTRA_MAY_FAIL_OVER]. */
    private var mayFailOver: Boolean = true

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
                    mayFailOver = intent.getBooleanExtra(EXTRA_MAY_FAIL_OVER, true)
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

        // Read once, as the per-app selection is: a change applies at the next
        // connect, and the screen that changes it says so.
        val network = (applicationContext as SomewhereApplication).network.load()
        val plan = TunConfiguration.plan(network.exclusions, network.mtu, Build.VERSION.SDK_INT)

        // Read before the tunnel takes DNS over, because afterwards the active
        // network is this one and its only resolver is the one we announce.
        // The user's own resolvers, when they named some, take their place.
        val resolvers =
            network.dnsServers.mapNotNull(TunConfiguration::parse).ifEmpty { underlyingResolvers() }

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(plan.mtu)
                // Blocking reads: a non-blocking TUN returns 0 in a tight loop
                // and burns a core doing nothing.
                .setBlocking(true)

        // Both families, addresses before routes, and a resolver per family.
        // Applied from the configuration rather than written out here so that
        // the relationships between them — a resolver that must be routed, an
        // address that must not be one the fake-IP pool could mint — stay
        // checkable by a test that needs no device.
        TunConfiguration.addresses.forEach { builder.addAddress(it.address, it.prefix) }
        // The plan has already dropped what the builder refuses, loopback
        // above all. A route it refuses anyway is reported rather than left
        // to throw on the main thread, where it killed the process — and with
        // connect-on-launch, every launch after.
        try {
            plan.routes.forEach { builder.addRoute(it.address, it.prefix) }
            if (Build.VERSION.SDK_INT >= TunConfiguration.EXCLUDE_ROUTE_API) {
                plan.excludes.forEach { builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(it.address), it.prefix)) }
            }
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "a route was refused: ${error.message}")
            TunnelController.report(TunnelState.Failed(R.string.tunnel_bad_routes))
            stopSelf()
            return
        }
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
                    // Names only: the full candidate list loads every label and
                    // install time, one call per app, on this thread.
                    installed = application.installedApps.installedPackageNames(),
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

        // `net=udp` is a QUIC node: one connection, opened here, and every
        // flow a stream on it. `net=tcp` — the v3 default — opens nothing yet;
        // each flow dials its own TLS connection when it starts.
        //
        // Verification is never skipped, on either carrier: v3 has no mode for
        // it. A pin is compared inside the QUIC handshake; otherwise the chain
        // is checked against the platform store once the handshake completes,
        // before the AuthFrame is written. Either way the name sent as SNI is
        // the one being verified — `sni`, or the host the link named — unless
        // it is an IP literal, which RFC 6066 does not allow as SNI.
        //
        // The node is the one the user configured, not `resolved`: the name to
        // verify is the host they named, and `resolved` has the address in its
        // place so that dialling it does not resolve through our own tunnel.
        val quicConnection =
            if (resolved.usesQuic) {
                ReconnectingQuic {
                    QuicDial.open(node, InetSocketAddress(portal, resolved.port), protect = { socket -> protect(socket) })
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
        // rather than guesswork. A refused certificate is *not* unreachability —
        // the Portal answered, and every other node will answer the same way to
        // the same wrong pin — so it stops here instead of walking the list.
        try {
            quicConnection?.current()
        } catch (error: Exception) {
            Log.w(TAG, "the QUIC Portal did not answer: ${error.message}")
            descriptor.close()
            val refused =
                (error as? QuicException)?.failure is QuicFailure.Untrusted || error.message?.contains("pin") == true
            if (refused) {
                (applicationContext as SomewhereApplication).nodeHealth.record(url, Attempt.Refused)
                TunnelController.report(TunnelState.Failed(R.string.tunnel_certificate_refused))
                stopSelf()
            } else {
                unreachable(url, R.string.tunnel_quic_unreachable)
            }
            return
        }

        val nowhere =
            NowhereSession(
                sharedKey = node.sharedKey,
                connect = {
                    when (val transport = dialer.connect(resolved)) {
                        is DecodeResult.Ok -> transport.value
                        is DecodeResult.Invalid -> error(transport.reason.detail)
                    }
                },
                quicStreams = quicConnection?.streams(),
                // A QUIC UDP flow sends every packet as a DATAGRAM.
                quicDatagrams = quicConnection?.datagrams(),
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
        val router =
            Router(
                { ruleSets },
                { routingSettings.mode },
                routingSettings.fallback,
                local = if (network.excludeLocal) bundled.map { it.loaded.rules } else emptyList(),
            )
        val direct = DirectDialer(protect = { socket -> protect(socket) })
        val owners = ConnectionOwners(applicationContext)

        val flows =
            NowhereFlowHandler(
                nowhere,
                fakeIp,
                resolvers,
                router,
                direct,
                { pump },
                TunnelController.connectionLog,
                TunnelController.tracker,
                TunnelController.dnsLog,
                owners::ownerOf,
            )
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
        val app = applicationContext as SomewhereApplication
        app.nodeHealth.record(url, Attempt.Succeeded)
        TunnelController.report(
            TunnelState.Connected(
                node = node.displayName ?: "${node.host}:${node.port}",
                sinceElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
                nodeUrl = url,
            ),
        )
        Log.i(TAG, "tunnel up via ${node.host}:${node.port} over ${node.net.token}${if (node.morph) " with morph" else ""}")
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
        if (!mayFailOver) {
            // The user picked this node. Moving their traffic to a different
            // Portal — a different country, perhaps — without asking is the
            // automatic override `Failover.preferred` refuses to be.
            Log.w(TAG, "the chosen node is unreachable; not failing over")
            TunnelController.report(TunnelState.Failed(reason))
            stopSelf()
            return
        }
        // Not nodes the subscription has withdrawn: they are expired or out of
        // quota (NW-D-04), and trying them would only spend the attempt.
        val candidates =
            application.nodes.nodes.value
                .filter { it.origin != NodeStore.Origin.RemovedFromFeed }
                .map { it.url.toUrl() }
        val next =
            Failover.next(candidates, application.nodeHealth, attempted, application.nodes::latencyOf) ?: run {
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
     * A QUIC connection that comes back after the path under it goes away.
     *
     * A network change is not something QUIC recovers from here: the connection
     * was built on a path that no longer exists, ngtcp2 gives up, and every
     * later call answers the same way. Without this the tunnel stays up and
     * carries nothing — which is worse than failing, because a user watching a
     * "Connected" screen has no reason to restart it.
     *
     * **A rebuilt connection is a new one and has never authenticated.** Its
     * stream ids start again at 0, and the carrier puts the AuthFrame on
     * Stream 0 of whatever connection it is given, so the first flow after a
     * rebuild authenticates again without anything here having to remember.
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
                override fun open(): StreamTransport {
                    val live = current()
                    return QuicStreamTransport(live, live.openStream())
                }
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
        startForeground(NOTIFICATION, notification(rates = null))
        watchRates()
    }

    /** Updates the notification's rates. Cancelled with the tunnel. */
    private var rateWatcher: kotlinx.coroutines.Job? = null

    /**
     * The two rates in the notification, once a second, while someone could
     * be looking.
     *
     * Only with the screen on: a notification update wakes the system UI, and
     * a tunnel left up overnight would otherwise redraw a shade nobody pulled
     * down eighty thousand times. Each direction separately, as on Home.
     */
    private fun watchRates() {
        rateWatcher?.cancel()
        val power = getSystemService(android.os.PowerManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        rateWatcher =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                TunnelController.traffic.collect { sample ->
                    if (!sample.measured || power?.isInteractive == false || pump == null) return@collect
                    manager?.notify(NOTIFICATION, notification(rates = sample))
                }
            }
    }

    private fun stopForegroundCompat() {
        rateWatcher?.cancel()
        rateWatcher = null
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

    private fun notification(rates: TrafficSample?): Notification {
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
        // Through the service's own stop action, not an exported entry point:
        // the PendingIntent carries this app's identity, so no other app can
        // fire it.
        val disconnect =
            PendingIntent.getService(
                this,
                1,
                Intent(this, SomewhereVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val connected = TunnelController.state.value as? TunnelState.Connected
        val text =
            if (rates == null) {
                getString(R.string.notification_connected)
            } else {
                getString(
                    R.string.notification_rates,
                    Format.throughput(rates.upstreamBytesPerSecond).let { "${it.value} ${it.unit}" },
                    Format.throughput(rates.downstreamBytesPerSecond).let { "${it.value} ${it.unit}" },
                )
            }
        return builder
            // The node, which is the thing worth seeing from the shade; the
            // app's name is already on the notification's header.
            .setContentTitle(connected?.node ?: getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action
                    .Builder(null, getString(R.string.notification_disconnect), disconnect)
                    .build(),
            ).build()
    }
}
