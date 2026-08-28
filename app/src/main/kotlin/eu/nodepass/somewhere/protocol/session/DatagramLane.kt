// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.quic.DatagramFrame
import eu.nodepass.somewhere.protocol.quic.DatagramReassembler
import eu.nodepass.somewhere.protocol.quic.PacketIds
import eu.nodepass.somewhere.protocol.quic.QuicDatagram
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Section 9's UDP carriage, shared by the carriers that can use it.
 *
 * ## Why this is not inside one carrier
 *
 * A duplex QUIC flow sends and receives packets as DATAGRAMs. A **split** flow
 * may do it in one direction only: with `up=udp&down=tcp` the uplink is QUIC and
 * the downlink is a TLS stream carrying length-prefixed packets, and with
 * `up=tcp&down=udp` it is the other way round. So the framing belongs to a
 * *direction*, not to a flow, and putting it inside either carrier would mean
 * writing it twice — which is how a protocol fact grows a second shape.
 *
 * ## One demultiplexer
 *
 * One connection's datagrams carry every flow's traffic, so something has to
 * route them and it has to be one thing: two readers pulling from the
 * connection would each discard what belonged to the other.
 */
internal class DatagramLane(
    private val datagrams: QuicCarrier.Datagrams,
) {
    private val inbound = ConcurrentHashMap<UInt, LinkedBlockingQueue<ByteArray>>()
    private val packetIds = ConcurrentHashMap<UInt, PacketIds>()
    private val reassembler = DatagramReassembler()
    private val demultiplex = Any()

    /** Row 15: payload is refused until a Portal has answered a flow. */
    fun markReady(flowId: UInt) {
        inbound.putIfAbsent(flowId, LinkedBlockingQueue())
        packetIds.putIfAbsent(flowId, PacketIds())
        reassembler.markReady()
    }

    fun forget(flowId: UInt) {
        inbound.remove(flowId)
        packetIds.remove(flowId)
    }

    /** The largest packet one datagram can carry, or 0 when unsupported. */
    fun maxDatagram(): Int = datagrams.maxDatagram()

    /**
     * Sends one packet, fragmenting when the path cannot carry it whole.
     *
     * Lossy by design: a packet that cannot be planned is dropped, exactly as a
     * UDP socket drops one it cannot send. Telling the caller would be adding a
     * guarantee the transport underneath does not have.
     */
    fun send(
        flowId: UInt,
        payload: ByteArray,
    ) {
        val maxDatagram = datagrams.maxDatagram()
        when (val planned = QuicDatagram.plan(payload.size, maxDatagram)) {
            is DecodeResult.Invalid -> Unit
            is DecodeResult.Ok ->
                if (planned.value == 1) {
                    datagrams.send(QuicDatagram.Data(flowId, payload).encode())
                } else {
                    // A fresh packet id per packet, and per replan: a peer
                    // reassembling two layouts under one id would produce a
                    // packet that is neither.
                    val packetId = (packetIds[flowId] ?: PacketIds().also { packetIds[flowId] = it }).allocate()
                    val perFragment = maxDatagram - QuicDatagram.FRAGMENT_HEADER_SIZE
                    for (index in 0 until planned.value) {
                        val from = index * perFragment
                        val to = minOf(from + perFragment, payload.size)
                        datagrams.send(
                            QuicDatagram
                                .Fragment(
                                    flowId = flowId,
                                    packetId = packetId,
                                    index = index,
                                    count = planned.value,
                                    totalLength = payload.size,
                                    payload = payload.copyOfRange(from, to),
                                ).encode(),
                        )
                    }
                }
        }
    }

    /** Waits up to [timeoutMillis] for one packet on [flowId]. */
    fun receive(
        flowId: UInt,
        timeoutMillis: Long,
    ): ByteArray? {
        val queue = inbound[flowId] ?: return null
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (true) {
            queue.poll()?.let { return if (it === CLOSED) null else it }
            if (System.nanoTime() >= deadline) return null
            pump(PUMP_MILLIS)
        }
    }

    fun close(flowId: UInt) {
        runCatching { datagrams.send(QuicDatagram.Close(flowId).encode()) }
        forget(flowId)
    }

    /**
     * Takes one datagram and gives it to whoever it is for.
     *
     * Under a lock across a single receive-and-route, so a flow waiting for its
     * own packet does not hold up one whose packet has already arrived.
     */
    private fun pump(timeoutMillis: Long) {
        synchronized(demultiplex) {
            val raw = datagrams.receive(timeoutMillis) ?: return
            val frame =
                when (val decoded = DatagramFrame.decode(raw)) {
                    is DecodeResult.Ok -> decoded.value
                    // One unreliable packet. Tearing down every flow on the
                    // connection because of it would turn a loss into an outage.
                    is DecodeResult.Invalid -> return
                }
            when (val accepted = reassembler.offer(frame, System.currentTimeMillis())) {
                is DecodeResult.Ok ->
                    when (val value = accepted.value) {
                        is DatagramReassembler.Accepted.Payload -> inbound[value.flowId]?.offer(value.bytes)
                        is DatagramReassembler.Accepted.Closed -> inbound[value.flowId]?.offer(CLOSED)
                        DatagramReassembler.Accepted.Pending -> Unit
                    }
                is DecodeResult.Invalid -> Unit
            }
        }
    }

    private companion object {
        /** "the far end closed this flow", not a payload. */
        val CLOSED = ByteArray(0)
        const val PUMP_MILLIS = 25L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
