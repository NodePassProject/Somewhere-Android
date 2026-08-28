// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.util.Log
import eu.nodepass.somewhere.dns.DnsInterceptor
import eu.nodepass.somewhere.dns.DnsMessage
import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.dns.FakeIpResolver
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.routing.DirectDialer
import eu.nodepass.somewhere.routing.RouteAction
import eu.nodepass.somewhere.routing.Router
import eu.nodepass.somewhere.routing.RoutingMode
import eu.nodepass.somewhere.routing.RoutingRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries the connections lwIP accepts out over Nowhere.
 *
 * One device TCP connection becomes one Nowhere flow, which at L1 is one TLS
 * connection to the Portal. Two pumps run per connection: the device's bytes go
 * up, the Portal's come down.
 *
 * ## The two rules that shape this class
 *
 * **`onTcpOpen` runs on the lwIP thread and must return immediately.** Opening
 * a flow means a TCP connect, a TLS handshake, an authentication frame and a
 * `SetupResult` — tens of milliseconds against a nearby Portal, seconds against
 * a distant one. lwIP's timer does not run while that call is outstanding, so
 * blocking there does not merely delay one connection, it stalls every other
 * connection's retransmission. The id is therefore handed out at once and the
 * dial happens elsewhere; a dial that fails aborts the pcb afterwards, which
 * the device sees as a connection that was accepted and then reset. That is a
 * real difference from a connection refused outright, and it is the honest
 * report: by the time we know, we had already said yes.
 *
 * **A flow to a synthetic address leaves as a name.** The DNS layer answers
 * lookups from a pool of addresses that route nowhere real, so a connection
 * arriving at one of them still knows which host it was for. That mapping is
 * held for the whole life of the flow and given back exactly once afterwards —
 * see [FakeIpResolver.Resolution.retained], and note that the flow which fails
 * to open owes the same release as the one that succeeds.
 *
 * **Receive window credit is returned only after the bytes leave.**
 * `nativeTcpRecved` is what reopens the device's TCP window. Calling it when
 * the bytes arrive rather than when they are handed to the Portal turns the
 * tunnel into an unbounded queue: the device would keep sending as fast as it
 * can produce, memory would grow to whatever a fast producer and a slow link
 * differ by, and the loss would land as a kill rather than as back-pressure.
 */
class NowhereFlowHandler(
    private val session: NowhereSession,
    private val fakeIp: FakeIpPool,
    /**
     * The resolvers the device had before the tunnel took its DNS over.
     *
     * Read off the underlying network rather than chosen here. A query this
     * client cannot answer still has to be answered by somebody, and picking
     * a public resolver on the user's behalf is a policy decision a tunnel has
     * no business making — their network already made it.
     *
     * Empty is a real state (a network that advertised none), and it is why
     * SERVFAIL exists below: with nowhere to forward to, saying so beats a
     * silence the device reads as a broken network.
     */
    private val upstreamResolvers: List<ByteArray> = emptyList(),
    /**
     * Where each flow goes.
     *
     * Consulted once, here, and not in the DNS interceptor — see [Router] for
     * why a name still gets a synthetic address whichever way it is routed.
     */
    private val router: Router = Router({ listOf(RoutingRules.EMPTY) }, { RoutingMode.Everything }),
    /** Opens the connections that do not go through the Portal. */
    private val direct: DirectDialer = DirectDialer(protect = { false }),
    private val pump: () -> TunPump?,
) : TunPump.FlowHandler {
    private companion object {
        const val TAG = "NowhereFlow"

        /** Read buffer for the Portal→device direction. One TCP window's worth is wasteful; one MTU is chatty. */
        const val DOWNSTREAM_BUFFER = 16 * 1024

        /** How long to wait for the device to free send buffer before looking again. */
        const val WRITE_WAIT_MILLIS = 5_000L

        /** Where a name lookup goes, whichever resolver the device happens to have. */
        const val DNS_PORT = 53

        /**
         * How often the screen gets a new reading.
         *
         * One second: fast enough that a transfer visibly starts and stops,
         * slow enough that the figure can be read. Shorter intervals make the
         * rate jitter with whatever the scheduler was doing, which reads as an
         * unstable connection rather than as a stable measurement.
         */
        const val SAMPLE_INTERVAL_MILLIS = 1_000L
    }

    /**
     * One accepted connection.
     *
     * [pcb] is lwIP's pointer. It is valid until `onTcpErr` arrives, after
     * which touching it is a use-after-free — so [gone] is set there and every
     * lwIP call checks it.
     */
    private class Connection(
        val id: Long,
        val pcb: Long,
        val target: Target,
        /** The synthetic address this flow is holding, or null if it holds none. */
        val heldAddress: ByteArray?,
    ) {
        /** One release per hold, however the flow ends. */
        val released = AtomicBoolean(false)

        /** Bytes from the device, awaiting a flow to write them to. */
        val outbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)

        /**
         * Signalled when the device acknowledges data, which is the only thing
         * that frees lwIP's send buffer. Conflated: the downstream pump cares
         * that room appeared, never how many times.
         */
        val acknowledged = Channel<Unit>(capacity = Channel.CONFLATED)

        @Volatile var flow: Flow? = null

        /**
         * Whether this flow left the device without touching the Portal.
         *
         * Set once, before anything is dialled, and read by the byte counting
         * afterwards — a direct byte is not a tunnelled byte.
         */
        @Volatile var direct: Boolean = false

        @Volatile var gone = false
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Bytes, counted where they actually move.
     *
     * At the pumps rather than at the socket: what the home screen is showing
     * is the traffic this tunnel carried, and TLS record framing is not that.
     * A monotonic clock, because a rate derived from wall-clock time is wrong
     * whenever the device adjusts it.
     */
    private val meter = TrafficMeter { android.os.SystemClock.elapsedRealtime() }

    /**
     * Publishes a reading a second.
     *
     * One sampler, because sampling is what advances the interval a rate is
     * measured over — two of them would each see half the traffic and both
     * would be wrong.
     */
    private val sampler =
        scope.launch {
            while (isActive) {
                TunnelController.reportTraffic(meter.sample(activeFlows = session.liveFlowCount))
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }

    /**
     * Answers name lookups locally, so that a name reaches the Portal.
     *
     * Runs on the lwIP thread, which is safe here in a way that dialling is
     * not: this is parsing and a map lookup over a datagram-sized buffer, and
     * it finishes in microseconds rather than in round trips.
     */
    private val dns = DnsInterceptor(fakeIp)

    /** Datagrams, carried as UDP over stream. Everything DNS cannot answer lands here. */
    private val udp = UdpRelay(session, fakeIp, meter, pump, scope)
    private val nextId = AtomicLong(1)
    private val connections = ConcurrentHashMap<Long, Connection>()

    override fun onTcpOpen(
        destination: ByteArray,
        port: Int,
        isIpv6: Boolean,
        pcb: Long,
    ): Long {
        val resolved = FakeIpResolver.resolve(fakeIp, destination, port)
        val target =
            when (val decoded = resolved.target) {
                is DecodeResult.Ok -> decoded.value
                is DecodeResult.Invalid -> {
                    // A destination lwIP handed us that the protocol will not
                    // encode. Port 0 is the realistic case. Refusing here is
                    // better than accepting and resetting.
                    Log.w(TAG, "refusing a connection lwIP accepted: ${decoded.reason.detail}")
                    return 0L
                }
            }

        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "opening a flow to $target")
        val connection =
            Connection(
                id = nextId.getAndIncrement(),
                pcb = pcb,
                target = target,
                heldAddress = if (resolved.retained) destination.copyOf() else null,
            )
        connections[connection.id] = connection
        connection.job = scope.launch { serve(connection) }
        return connection.id
    }

    override fun onTcpPayload(
        id: Long,
        data: ByteArray?,
    ) {
        val connection = connections[id] ?: return
        if (data == null) {
            // The device half-closed. Closing the outbound channel lets the
            // upstream pump finish what it has and then stop.
            connection.outbound.close()
            return
        }
        connection.outbound.trySend(data)
    }

    override fun onTcpAcknowledged(
        id: Long,
        length: Int,
    ) {
        connections[id]?.acknowledged?.trySend(Unit)
    }

    override fun onTcpClosed(
        id: Long,
        error: Int,
    ) {
        val connection = connections.remove(id) ?: return
        // The pcb is already freed. Nothing below may touch it.
        connection.gone = true
        releaseFakeIp(connection)
        connection.outbound.close()
        connection.job?.cancel()
        runCatching { connection.flow?.close() }
    }

    override fun onUdpDatagram(
        source: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
        destinationPort: Int,
        isIpv6: Boolean,
        data: ByteArray,
    ) {
        if (destinationPort == DNS_PORT) {
            when (val outcome = dns.handle(data)) {
                is DnsInterceptor.Outcome.Answer -> {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "answered ${outcome.name} locally")
                    // Back to the device from the resolver it asked, so the
                    // answer matches the socket the query left on.
                    answerLocally(source, sourcePort, destination, destinationPort, isIpv6, outcome.message)
                    return
                }

                is DnsInterceptor.Outcome.Relay -> {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "relaying a query: ${outcome.why}")
                    relayQuery(source, sourcePort, destination, destinationPort, isIpv6, data)
                    return
                }
            }
        }
        udp.offer(source, sourcePort, destination, destinationPort, isIpv6, data)
    }

    /**
     * Forwards a query the interceptor declined to a resolver that exists.
     *
     * The device addressed it to the resolver this tunnel announced, which is an
     * address inside the TUN's own subnet and reaches nothing beyond it. The
     * query is therefore dialled at one of [upstreamResolvers] while the reply
     * still returns from the announced address — [UdpRelay.offer] keeps those
     * two apart.
     */
    private fun relayQuery(
        source: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
        destinationPort: Int,
        isIpv6: Boolean,
        data: ByteArray,
    ) {
        val resolver = upstreamResolvers.firstOrNull { it.size == destination.size }
        if (resolver == null) {
            answerLocally(source, sourcePort, destination, destinationPort, isIpv6, serverFailure(data))
            return
        }
        udp.offer(source, sourcePort, destination, destinationPort, isIpv6, data, dialAddress = resolver)
    }

    /** SERVFAIL for a query that can be neither answered nor forwarded. */
    private fun serverFailure(query: ByteArray): ByteArray? =
        when (val parsed = DnsMessage.parseQuestion(query)) {
            is DecodeResult.Ok -> DnsMessage.serverFailure(query, parsed.value)
            is DecodeResult.Invalid -> null
        }

    /** Writes [message] back to the device from the address it wrote to. */
    private fun answerLocally(
        source: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
        destinationPort: Int,
        isIpv6: Boolean,
        message: ByteArray?,
    ) {
        if (message == null) return
        pump()?.writeToDevice {
            NativeBridge.nativeUdpSendto(
                destination,
                destinationPort,
                source,
                sourcePort,
                isIpv6,
                message,
                message.size,
            )
        }
    }

    /**
     * Gives back the synthetic address this flow was holding.
     *
     * Exactly once, from whichever of the three teardown paths gets there
     * first. An address released twice would come free while another flow was
     * still using it; one never released would pin the entry for the life of
     * the tunnel, and neither says anything in a log.
     */
    private fun releaseFakeIp(connection: Connection) {
        val address = connection.heldAddress ?: return
        if (connection.released.compareAndSet(false, true)) fakeIp.release(address)
    }

    /**
     * Counts bytes on the side of the tunnel they were actually on.
     *
     * A direct byte never reached the Portal, so adding it to the tunnel's
     * figures would make the home screen's throughput a sum of two unrelated
     * measurements — the same defect as one number for both directions, which
     * this project already refused once.
     */
    private fun record(
        connection: Connection,
        upstream: Int = 0,
        downstream: Int = 0,
    ) {
        if (connection.direct) {
            meter.recordDirect(upstream + downstream)
            return
        }
        if (upstream > 0) meter.recordUpstream(upstream)
        if (downstream > 0) meter.recordDownstream(downstream)
    }

    private suspend fun serve(connection: Connection) {
        // The device's first bytes usually arrive before the Portal answers.
        // Sending them in the opening write costs one round trip less, which
        // for a TLS ClientHello is the difference the protocol's firstPayload
        // parameter exists for.
        val first = connection.outbound.tryReceive().getOrNull() ?: ByteArray(0)

        val action = router.decide(connection.target)
        if (action == RouteAction.Reject) {
            // A decision, not a failure. The device sees a reset, which is what
            // it would see from a destination that refused it — there is no way
            // to say "a rule forbade this" in TCP, and the reason is in the log
            // rather than invented on the wire.
            Log.i(TAG, "a rule rejects ${connection.target}")
            abort(connection)
            return
        }
        connection.direct = action == RouteAction.Direct

        val opened =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (connection.direct) {
                        direct.connect(connection.target)
                    } else {
                        session.openFlow(connection.target, firstPayload = first)
                    }
                }
            }.getOrElse { error ->
                Log.w(TAG, "dial failed for ${connection.target}: ${error.message}")
                abort(connection)
                return
            }

        val flow =
            when (opened) {
                is DecodeResult.Ok -> opened.value
                is DecodeResult.Invalid -> {
                    val who = if (connection.direct) "the destination" else "the Portal"
                    Log.w(TAG, "$who refused ${connection.target}: ${opened.reason.detail}")
                    abort(connection)
                    return
                }
            }

        connection.flow = flow
        if (first.isNotEmpty()) {
            if (connection.direct) {
                // A direct flow has no opening write to ride on, so the first
                // bytes are sent here instead of being carried by the dial.
                withContext(Dispatchers.IO) {
                    flow.write(first)
                    flow.flush()
                }
            }
            // Carried in the opening write rather than by the pump, so the pump
            // never sees it and it would otherwise go uncounted.
            record(connection, upstream = first.size)
            credit(connection, first.size)
        }

        val upstream = scope.launch { pumpUpstream(connection, flow) }
        val downstream = scope.launch { pumpDownstream(connection, flow) }
        upstream.join()
        downstream.join()
        finish(connection)
    }

    /** Device → Portal. */
    private suspend fun pumpUpstream(
        connection: Connection,
        flow: Flow,
    ) {
        try {
            for (chunk in connection.outbound) {
                withContext(Dispatchers.IO) {
                    flow.write(chunk)
                    flow.flush()
                }
                record(connection, upstream = chunk.size)
                // Only now: the bytes are on the wire, so the window may reopen.
                credit(connection, chunk.size)
            }
        } catch (error: Exception) {
            Log.w(TAG, "upstream pump ended: ${error.message}")
        }
    }

    /** Portal → device. */
    private suspend fun pumpDownstream(
        connection: Connection,
        flow: Flow,
    ) {
        val buffer = ByteArray(DOWNSTREAM_BUFFER)
        try {
            while (!connection.gone) {
                val read = withContext(Dispatchers.IO) { flow.read(buffer) }
                if (read < 0) break
                if (read == 0) continue
                record(connection, downstream = read)
                if (!deliver(connection, buffer, read)) break
            }
        } catch (error: Exception) {
            Log.w(TAG, "downstream pump ended: ${error.message}")
        }
    }

    /**
     * Hands [length] bytes to lwIP for the device, waiting when there is no room.
     *
     * **`nativeTcpWrite` can refuse.** lwIP's send buffer is finite and only
     * empties when the device acknowledges; a write past it returns `ERR_MEM`
     * and queues nothing. The first version of this class ignored the return
     * value entirely and wrote whole 16 KB chunks unconditionally, which on a
     * 20 MB download delivered 8.8% of the file at 15 KB/s — every refused
     * write was a silent hole, and what the device saw was a stream that lost
     * most of itself and spent the rest of its time recovering.
     *
     * The loop is therefore: ask how much room there is, write no more than
     * that, and when the answer is none, wait for the device to acknowledge
     * something. That wait is the back-pressure; without it the tunnel would
     * simply be discarding data at a different layer.
     *
     * @return false if the connection went away.
     */
    private suspend fun deliver(
        connection: Connection,
        buffer: ByteArray,
        length: Int,
    ): Boolean {
        var offset = 0
        val pump = pump() ?: return false
        while (offset < length) {
            if (connection.gone) return false
            val written =
                pump.onStackThread {
                    if (connection.gone) {
                        -1
                    } else {
                        val room = NativeBridge.nativeTcpSndbuf(connection.pcb)
                        val take = minOf(room, length - offset)
                        if (take <= 0) {
                            0
                        } else if (NativeBridge.nativeTcpWrite(connection.pcb, buffer, offset, take) != 0) {
                            // Refused after all — the buffer figure was a
                            // snapshot and something else took the room.
                            0
                        } else {
                            NativeBridge.nativeTcpOutput(connection.pcb)
                            take
                        }
                    }
                } ?: return false

            if (written < 0) return false
            if (written == 0) {
                // No room. Only the device acknowledging frees any, so wait for
                // that rather than spinning. The timeout is a safety net: a
                // device that has stopped acknowledging is a dead connection,
                // and lwIP will say so through onTcpErr.
                withTimeoutOrNull(WRITE_WAIT_MILLIS) { connection.acknowledged.receive() }
            } else {
                offset += written
            }
        }
        return true
    }

    /** Reopens the device's receive window for bytes that have left. */
    private fun credit(
        connection: Connection,
        length: Int,
    ) {
        pump()?.writeToDevice {
            if (!connection.gone) NativeBridge.nativeTcpRecved(connection.pcb, length)
        }
    }

    /** The connection was accepted and then could not be served. */
    private fun abort(connection: Connection) {
        connections.remove(connection.id)
        releaseFakeIp(connection)
        pump()?.writeToDevice {
            if (!connection.gone) NativeBridge.nativeTcpAbort(connection.pcb)
        }
    }

    /** Both directions finished. */
    private fun finish(connection: Connection) {
        connections.remove(connection.id)
        releaseFakeIp(connection)
        runCatching { connection.flow?.close() }
        pump()?.writeToDevice {
            if (!connection.gone) NativeBridge.nativeTcpClose(connection.pcb)
        }
    }

    fun shutdown() {
        sampler.cancel()
        udp.shutdown()
        connections.values.forEach {
            releaseFakeIp(it)
            runCatching { it.flow?.close() }
        }
        connections.clear()
        scope.cancel()
    }
}
