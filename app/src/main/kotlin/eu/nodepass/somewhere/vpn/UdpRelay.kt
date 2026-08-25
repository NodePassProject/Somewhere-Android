// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.util.Log
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

/**
 * Datagrams from the device, carried as UDP over stream (NW-P-07).
 *
 * DNS is the reason this exists and the reason it has to be cheap. Nothing
 * resolves without it — a tunnel that carries TCP and drops UDP looks, from
 * the device, exactly like a network with no DNS server, which is to say like
 * a network that is broken in an unrelated way.
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
    ) {
        val outbound = Channel<ByteArray>(capacity = Channel.BUFFERED)

        @Volatile var flow: Flow? = null

        @Volatile var lastUsed = 0L
        var job: Job? = null
    }

    private val relays = ConcurrentHashMap<Key, Relay>()

    /** Monotonic, because wall-clock time can step backwards and idle time cannot. */
    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    fun offer(
        source: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
        destinationPort: Int,
        isIpv6: Boolean,
        data: ByteArray,
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

        val target =
            when (
                val decoded =
                    if (isIpv6) {
                        Target.ofIpv6(destination, destinationPort)
                    } else {
                        Target.ofIpv4(destination, destinationPort)
                    }
            ) {
                is DecodeResult.Ok -> decoded.value
                is DecodeResult.Invalid -> {
                    Log.w(TAG, "not a target: ${decoded.reason.detail}")
                    return
                }
            }

        val relay = Relay(key, isIpv6)
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
        runCatching { relay.flow?.close() }
    }

    fun shutdown() {
        relays.values.forEach { relay ->
            relay.outbound.close()
            runCatching { relay.flow?.close() }
        }
        relays.clear()
    }
}
