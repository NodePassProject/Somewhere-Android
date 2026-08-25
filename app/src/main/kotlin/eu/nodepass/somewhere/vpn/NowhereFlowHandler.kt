// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.util.Log
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
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
 * **Receive window credit is returned only after the bytes leave.**
 * `nativeTcpRecved` is what reopens the device's TCP window. Calling it when
 * the bytes arrive rather than when they are handed to the Portal turns the
 * tunnel into an unbounded queue: the device would keep sending as fast as it
 * can produce, memory would grow to whatever a fast producer and a slow link
 * differ by, and the loss would land as a kill rather than as back-pressure.
 */
class NowhereFlowHandler(
    private val session: NowhereSession,
    private val pump: () -> TunPump?,
) : TunPump.FlowHandler {
    private companion object {
        const val TAG = "NowhereFlow"

        /** Read buffer for the Portal→device direction. One TCP window's worth is wasteful; one MTU is chatty. */
        const val DOWNSTREAM_BUFFER = 16 * 1024

        /** How long to wait for the device to free send buffer before looking again. */
        const val WRITE_WAIT_MILLIS = 5_000L
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
    ) {
        /** Bytes from the device, awaiting a flow to write them to. */
        val outbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)

        /**
         * Signalled when the device acknowledges data, which is the only thing
         * that frees lwIP's send buffer. Conflated: the downstream pump cares
         * that room appeared, never how many times.
         */
        val acknowledged = Channel<Unit>(capacity = Channel.CONFLATED)

        @Volatile var flow: Flow? = null

        @Volatile var gone = false
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val connections = ConcurrentHashMap<Long, Connection>()

    override fun onTcpOpen(
        destination: ByteArray,
        port: Int,
        isIpv6: Boolean,
        pcb: Long,
    ): Long {
        val target =
            when (val decoded = if (isIpv6) Target.ofIpv6(destination, port) else Target.ofIpv4(destination, port)) {
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
        val connection = Connection(nextId.getAndIncrement(), pcb, target)
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
        // UDP over stream is section 8 of the protocol and its codec is
        // implemented and vector-checked, but nothing drives it yet. Dropping
        // is stated rather than silent: a datagram that vanishes with no
        // explanation is the hardest kind of failure to diagnose from a
        // device, and DNS is the first thing that will land here.
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "dropping a UDP datagram to port $destinationPort: UoT is not wired yet")
        }
    }

    private suspend fun serve(connection: Connection) {
        // The device's first bytes usually arrive before the Portal answers.
        // Sending them in the opening write costs one round trip less, which
        // for a TLS ClientHello is the difference the protocol's firstPayload
        // parameter exists for.
        val first = connection.outbound.tryReceive().getOrNull() ?: ByteArray(0)

        val opened =
            withContext(Dispatchers.IO) {
                runCatching { session.openFlow(connection.target, firstPayload = first) }
            }.getOrElse { error ->
                Log.w(TAG, "dial failed for ${connection.target}: ${error.message}")
                abort(connection)
                return
            }

        val flow =
            when (opened) {
                is DecodeResult.Ok -> opened.value
                is DecodeResult.Invalid -> {
                    Log.w(TAG, "the Portal refused ${connection.target}: ${opened.reason.detail}")
                    abort(connection)
                    return
                }
            }

        connection.flow = flow
        if (first.isNotEmpty()) credit(connection, first.size)

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
        pump()?.writeToDevice {
            if (!connection.gone) NativeBridge.nativeTcpAbort(connection.pcb)
        }
    }

    /** Both directions finished. */
    private fun finish(connection: Connection) {
        connections.remove(connection.id)
        runCatching { connection.flow?.close() }
        pump()?.writeToDevice {
            if (!connection.gone) NativeBridge.nativeTcpClose(connection.pcb)
        }
    }

    fun shutdown() {
        connections.values.forEach { runCatching { it.flow?.close() } }
        connections.clear()
        scope.cancel()
    }
}
