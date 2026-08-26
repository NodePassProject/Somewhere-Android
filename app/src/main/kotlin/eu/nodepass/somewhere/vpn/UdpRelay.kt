// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.util.Log
import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.dns.FakeIpResolver
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.UdpOverTcp
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Datagrams from the device, carried as UDP over stream (NW-P-07).
 *
 * DNS is why this had to exist before anything else, and it is now the part
 * DNS mostly does not use: address lookups are answered on the device by
 * [eu.nodepass.somewhere.dns.DnsInterceptor], and what arrives here is
 * everything else — the query types that need a real resolver, and the
 * ordinary UDP that QUIC and HTTP/3 are made of.
 *
 * What has not changed is why it must be cheap. A tunnel that carries TCP and
 * drops UDP looks, from the device, exactly like a network that is broken in
 * an unrelated way.
 *
 * ## One flow per four-tuple, and why that is not a choice
 *
 * A Nowhere flow carries a single [Target] fixed at open time, so one flow
 * cannot serve two destinations. UDP has no connection to reuse either: the
 * device picks a fresh source port per query. So `(source, source port,
 * destination, destination port)` is the finest key that is also the coarsest
 * correct one.
 *
 * At L1 each flow is its own TLS connection, which makes a burst of DNS
 * expensive — that is what L2's mux is for. Until then the cost is bounded
 * here rather than left to grow: [MAX_FLOWS] concurrent relays, and anything
 * beyond is dropped with a word in the log rather than silently.
 *
 * ## Reaping
 *
 * A UDP flow has no close to wait for. Each relay is closed once it has been
 * idle for [IDLE_MILLIS], which for DNS is long after the answer arrived and
 * short enough that a burst of queries does not accumulate.
 */
class UdpRelay(
    private val session: NowhereSession,
    private val fakeIp: FakeIpPool,
    /** Shared with the TCP path: one session's throughput is one figure. */
    private val meter: TrafficMeter,
    private val pump: () -> TunPump?,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "UdpRelay"

        /**
         * Concurrent relays. Upstream's own default is 256 logical UDP flows
         * per session; this sits below it so the client runs out first and
         * says so, rather than being refused by the Portal with a rejection
         * the user cannot act on.
         */
        const val MAX_FLOWS = 128

        /** How long a relay may sit unused before it is closed. */
        const val IDLE_MILLIS = 30_000L

        /** Reading a datagram bigger than this means the peer is not speaking UoT. */
        const val MAX_DATAGRAM = UdpOverTcp.PACKET_MAX
    }

    /** The four-tuple that identifies one relay. */
    private data class Key(
        val source: List<Byte>,
        val sourcePort: Int,
        val destination: List<Byte>,
        val destinationPort: Int,
    )

    private class Relay(
        val key: Key,
        val isIpv6: Boolean,
        /** The synthetic address this relay is holding, or null if it holds none. */
        val heldAddress: ByteArray?,
    ) {
        val outbound = Channel<ByteArray>(capacity = Channel.BUFFERED)

        val released = AtomicBoolean(false)

        @Volatile var flow: Flow? = null

        @Volatile var lastUsed = 0L
        var job: Job? = null
    }

    private val relays = ConcurrentHashMap<Key, Relay>()

    /** Monotonic, because wall-clock time can step backwards and idle time cannot. */
    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    /**
     * Takes one datagram from the device.
     *
     * [dialAddress] separates *where this goes* from *what the device believes
     * it is talking to*. They are the same address for ordinary traffic and are
     * not for DNS: the tunnel announces a resolver inside its own subnet, so a
     * query the interceptor cannot answer arrives addressed to an address that
     * exists nowhere but here. It is dialled at a real resolver instead — while
     * the reply still has to reach the device **from** the announced one, or the
     * device discards it as an answer from a stranger.
     */
    fun offer(
        source: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
        destinationPort: Int,
        isIpv6: Boolean,
        data: ByteArray,
        dialAddress: ByteArray = destination,
    ) {
        val key = Key(source.toList(), sourcePort, destination.toList(), destinationPort)
        val existing = relays[key]
        if (existing != null) {
            existing.lastUsed = now()
            existing.outbound.trySend(data)
            return
        }

        if (relays.size >= MAX_FLOWS) {
            // Said rather than silently dropped: at L1 every relay is a TLS
            // connection, and the shape of this limit is exactly what makes
            // the case for mux at L2.
            Log.w(TAG, "$MAX_FLOWS relays already open; dropping a datagram to port $destinationPort")
            return
        }

        // A datagram to a synthetic address carries a name too: QUIC and
        // HTTP/3 reach a host this way, and an address out of the fake range
        // is one the Portal could not route even if it wanted to.
        val resolved = FakeIpResolver.resolve(fakeIp, dialAddress, destinationPort)
        val target =
            when (val decoded = resolved.target) {
                is DecodeResult.Ok -> decoded.value
                is DecodeResult.Invalid -> {
                    Log.w(TAG, "not a target: ${decoded.reason.detail}")
                    return
                }
            }

        val relay = Relay(key, isIpv6, if (resolved.retained) dialAddress.copyOf() else null)
        relay.lastUsed = now()
        relay.outbound.trySend(data)
        if (relays.putIfAbsent(key, relay) != null) {
            // Another datagram for the same tuple raced us here. Theirs won;
            // hand ours over rather than opening a second flow for it.
            relays[key]?.outbound?.trySend(data)
            return
        }
        relay.job = scope.launch { serve(relay, target) }
    }

    private suspend fun serve(
        relay: Relay,
        target: Target,
    ) {
        val first = relay.outbound.tryReceive().getOrNull() ?: ByteArray(0)
        val framed =
            when (val encoded = UdpOverTcp.encode(first)) {
                is DecodeResult.Ok -> encoded.value
                is DecodeResult.Invalid -> {
                    Log.w(TAG, "cannot frame a datagram: ${encoded.reason.detail}")
                    close(relay)
                    return
                }
            }

        val opened =
            withContext(Dispatchers.IO) {
                runCatching { session.openFlow(target, FlowKind.Udp, framed) }
            }.getOrElse { error ->
                Log.w(TAG, "dial failed for $target: ${error.message}")
                close(relay)
                return
            }

        val flow =
            when (opened) {
                is DecodeResult.Ok -> opened.value
                is DecodeResult.Invalid -> {
                    Log.w(TAG, "the Portal refused $target: ${opened.reason.detail}")
                    close(relay)
                    return
                }
            }

        relay.flow = flow
        // The opening write carried this one; the pump never sees it.
        meter.recordUpstream(framed.size)
        val up = scope.launch { pumpUp(relay, flow) }
        val down = scope.launch { pumpDown(relay, flow) }
        val reaper = scope.launch { reap(relay) }
        up.join()
        down.join()
        reaper.cancel()
        close(relay)
    }

    /** Device → Portal, one length-prefixed packet at a time. */
    private suspend fun pumpUp(
        relay: Relay,
        flow: Flow,
    ) {
        try {
            for (payload in relay.outbound) {
                val framed =
                    when (val encoded = UdpOverTcp.encode(payload)) {
                        is DecodeResult.Ok -> encoded.value
                        is DecodeResult.Invalid -> {
                            // One oversized datagram is not a reason to tear
                            // down a relay that is otherwise carrying traffic.
                            Log.w(TAG, "skipping a datagram: ${encoded.reason.detail}")
                            continue
                        }
                    }
                withContext(Dispatchers.IO) {
                    flow.write(framed)
                    flow.flush()
                }
                meter.recordUpstream(framed.size)
                relay.lastUsed = now()
            }
        } catch (error: Exception) {
            Log.w(TAG, "upstream relay ended: ${error.message}")
        }
    }

    /** Portal → device. */
    private suspend fun pumpDown(
        relay: Relay,
        flow: Flow,
    ) {
        val prefix = ByteArray(UdpOverTcp.LENGTH_PREFIX_SIZE)
        try {
            while (true) {
                if (!readFully(flow, prefix, prefix.size)) break
                val length = ((prefix[0].toInt() and 0xFF) shl 8) or (prefix[1].toInt() and 0xFF)
                if (length > MAX_DATAGRAM) {
                    Log.w(TAG, "a declared datagram of $length bytes cannot be UoT; closing the relay")
                    break
                }
                // A zero length is a legal empty datagram, not a terminator.
                val payload = ByteArray(length)
                if (length > 0 && !readFully(flow, payload, length)) break

                meter.recordDownstream(prefix.size + payload.size)
                relay.lastUsed = now()
                // Back to the device from the address it wrote to.
                pump()?.writeToDevice {
                    NativeBridge.nativeUdpSendto(
                        relay.key.destination.toByteArray(),
                        relay.key.destinationPort,
                        relay.key.source.toByteArray(),
                        relay.key.sourcePort,
                        relay.isIpv6,
                        payload,
                        payload.size,
                    )
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "downstream relay ended: ${error.message}")
        }
    }

    /**
     * Fills [length] bytes or reports the stream ended.
     *
     * A single `read` is free to return less than asked for, and a UoT length
     * prefix split across two TLS records is ordinary rather than exceptional
     * — treating a short read as the whole packet would desynchronise the
     * stream and every datagram after it would be garbage.
     */
    private suspend fun readFully(
        flow: Flow,
        into: ByteArray,
        length: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            var filled = 0
            while (filled < length) {
                val read = flow.read(into, filled, length - filled)
                if (read < 0) return@withContext false
                filled += read
            }
            true
        }

    private suspend fun reap(relay: Relay) {
        while (true) {
            kotlinx.coroutines.delay(IDLE_MILLIS / 2)
            if (now() - relay.lastUsed >= IDLE_MILLIS) {
                runCatching { relay.flow?.close() }
                return
            }
        }
    }

    private fun close(relay: Relay) {
        relays.remove(relay.key, relay)
        relay.outbound.close()
        releaseFakeIp(relay)
        runCatching { relay.flow?.close() }
    }

    /** One release per hold, however the relay ends. */
    private fun releaseFakeIp(relay: Relay) {
        val address = relay.heldAddress ?: return
        if (relay.released.compareAndSet(false, true)) fakeIp.release(address)
    }

    fun shutdown() {
        relays.values.forEach { relay ->
            relay.outbound.close()
            releaseFakeIp(relay)
            runCatching { relay.flow?.close() }
        }
        relays.clear()
    }
}
