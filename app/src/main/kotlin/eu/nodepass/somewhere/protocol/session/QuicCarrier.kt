// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRejected
import eu.nodepass.somewhere.protocol.frame.FlowStart
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.quic.QuicFlowId
import eu.nodepass.somewhere.protocol.target.Target

/** Why a flow could not be opened on a QUIC carrier. */
sealed interface QuicCarrierReason : DecodeReason {
    data object CarrierClosed : QuicCarrierReason {
        override val detail: String = "the QUIC connection is closed"
    }

    data object NoSetupByte : QuicCarrierReason {
        override val detail: String = "the Portal did not answer the flow"
    }

    data class Rejected(
        override val result: SetupResult,
    ) : QuicCarrierReason,
        FlowRejected {
        override val detail: String = result.name
    }

    data class HeaderInvalid(
        val reason: DecodeReason,
    ) : QuicCarrierReason {
        override val detail: String = reason.detail
    }

    /**
     * The stream this flow was given cannot carry one.
     *
     * In practice the 2^30 flow ids a connection derives have run out, and the
     * specification's answer is a new connection rather than a recovery on
     * this one.
     */
    data class StreamUnusable(
        val reason: DecodeReason,
    ) : QuicCarrierReason {
        override val detail: String = reason.detail
    }

    /**
     * A UDP flow was asked for and this connection has no DATAGRAM extension.
     *
     * Refused rather than carried over the control stream: the specification puts UDP
     * in DATAGRAMs, and a Portal is not listening for length-prefixed packets
     * on a QUIC stream.
     */
    data object NoDatagrams : QuicCarrierReason {
        override val detail: String = "this QUIC connection carries no datagrams, so UDP cannot be relayed over it"
    }
}

/**
 * A QUIC connection carrying many flows, one stream each.
 *
 * ## What is different from a dedicated TLS lane, and it is one thing
 *
 * **Authentication happens once per connection, on the first client-initiated
 * bidirectional stream.** A TLS lane authenticates on every connection because
 * a lane *is* a connection; here a connection outlives its flows, so a second
 * AuthFrame on a later stream is a protocol error rather than a redundancy.
 * Everything else — the single opening write, the setup byte, the deadline that
 * comes off once the flow is open — is the lane's behaviour unchanged, because
 * it is the protocol's behaviour rather than the carrier's.
 *
 * Success is not observable. Authentication has no response frame — a Portal
 * that answered differently on failure would be an oracle for active probing —
 * so the first flow's READY is the only evidence, and a wrong key is silence.
 * The AuthFrame is pipelined onto that first flow rather than sent on a
 * stream of its own, which the specification permits and upstream's own
 * Vector does.
 *
 * ## Rejections have one shape
 *
 * [QuicCarrierReason.Rejected] implements [FlowRejected] like the lane's. That
 * interface exists because the same rejection reached the caller as a named
 * `DIAL_FAILED` over one carrier and as an unclassifiable string over another,
 * and the explanations the app renders are matched on exactly this — so a
 * carrier growing its own shape would degrade all of them to a generic
 * failure, on screen, with every gate green.
 */
class QuicCarrier(
    private val streams: StreamFactory,
    private val sharedKey: SharedKey,
    /**
     * The connection's DATAGRAM side, when it has one.
     *
     * Null means UDP flows are refused rather than carried over the stream:
     * the specification puts them in DATAGRAMs, and a client that quietly used the
     * TLS carrier's length-prefixed framing instead would be interoperating
     * with a Portal that is not listening for it.
     */
    private val datagrams: Datagrams? = null,
) : AutoCloseable {
    /** Opens one client-initiated bidirectional stream as a byte transport. */
    fun interface StreamFactory {
        fun open(): StreamTransport
    }

    /** The unreliable half of a QUIC connection, as this carrier needs it. */
    interface Datagrams {
        fun send(bytes: ByteArray)

        fun receive(timeoutMillis: Long): ByteArray?

        /** The largest DATA frame one datagram can carry, or 0 if unsupported. */
        fun maxDatagram(): Int
    }

    private var closed = false
    private val open = mutableListOf<Transport>()

    /** UDP carriage over DATAGRAMs, when this connection has a datagram side. */
    private val lane: DatagramLane? = datagrams?.let { DatagramLane(it) }

    fun openFlow(
        target: Target,
        kind: FlowKind,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        if (closed) return invalid(QuicCarrierReason.CarrierClosed)
        if (kind == FlowKind.Udp && (datagrams == null || datagrams.maxDatagram() <= 0)) {
            return invalid(QuicCarrierReason.NoDatagrams)
        }

        val transport = streams.open()
        open += transport

        // The id is the stream's, derived rather than chosen: both ends compute
        // it from the stream number and neither sends it. Over TCP it is only
        // a name for logs; over UDP it is what every DATAGRAM is addressed by.
        val flowId =
            when (val derived = QuicFlowId.forClientStream(transport.streamId)) {
                is DecodeResult.Invalid -> {
                    transport.close()
                    return invalid(QuicCarrierReason.StreamUnusable(derived.reason))
                }
                is DecodeResult.Ok -> derived.value
            }

        // The AuthFrame leads client bidirectional Stream 0 and no other: "the
        // authentication Stream is client bidirectional Stream ID 0". Keyed on
        // the stream id rather than on remembering, because a connection that
        // died and was rebuilt starts again at Stream 0 and has never
        // authenticated — a carrier that remembered having done it would send
        // that first flow bare, and a Portal does not admit a bare flow.
        // Only one stream is credited before authentication, so no later
        // stream can be opened ahead of this one.
        val authFrame =
            if (transport.streamId == AUTHENTICATION_STREAM) {
                Authentication.encodeFrame(
                    sharedKey = sharedKey,
                    transport = AuthTransport.Quic,
                    exporter = transport.exporter,
                )
            } else {
                ByteArray(0)
            }

        // No payload in the opening write, for either kind. "Target payload
        // waits for READY", and a Portal that reads a byte early ends the flow.
        // A UDP flow's payload never rides the stream at all: after READY the
        // control stream "carries no business bytes" and packets travel as
        // DATAGRAMs.
        val opening = authFrame + byteArrayOf(FlowStart(kind).encode()) + target.encode()
        transport.write(opening)
        transport.flush()

        val setup = readSetupByte(transport) ?: return invalid(QuicCarrierReason.NoSetupByte)
        val result =
            when (val decoded = SetupResult.decode(setup)) {
                is DecodeResult.Invalid -> return invalid(QuicCarrierReason.HeaderInvalid(decoded.reason))
                is DecodeResult.Ok -> decoded.value
            }
        if (result.isRejection) return invalid(QuicCarrierReason.Rejected(result))

        // Setup is over, so the deadline that protected it comes off. Before
        // READY it bounds a Portal that is slow or gone; afterwards it would
        // close every idle SSH session, websocket and long poll.
        transport.setReadTimeout(0)

        if (kind == FlowKind.Udp) {
            // No DATA before READY. The lane refuses payload until this
            // is called, and it is called here — after a Portal has answered a
            // flow — rather than when the connection came up, so a datagram
            // arriving before any flow exists is discarded rather than queued
            // for one that may never open.
            lane!!.markReady(flowId)
            val flow = PacketFlowOverDatagram(flowId, target, result, transport)
            if (firstPayload.isNotEmpty()) flow.sendPacket(firstPayload)
            return flow.ok()
        }

        if (firstPayload.isNotEmpty()) {
            transport.write(firstPayload)
            transport.flush()
        }
        return StreamFlow(flowId, target, kind, result, transport).ok()
    }

    /**
     * Reads the Portal's setup byte, or null if none arrives.
     *
     * A rejected AuthFrame produces silence rather than a close, so a timeout
     * and an end of stream mean the same thing here: no answer came, most
     * likely authentication.
     */
    private fun readSetupByte(transport: Transport): Byte? {
        val buffer = ByteArray(1)
        return try {
            if (transport.read(buffer, 0, 1) == 1) buffer[0] else null
        } catch (_: java.io.IOException) {
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun invalid(reason: DecodeReason): DecodeResult<Flow> = DecodeResult.Invalid(reason)

    private fun Flow.ok(): DecodeResult<Flow> = DecodeResult.Ok(this)

    private companion object {
        const val READ_MILLIS = 15_000L
        const val AUTHENTICATION_STREAM = 0L
    }

    /** One UDP flow: a control stream that opened it, and DATAGRAMs after. */
    private inner class PacketFlowOverDatagram(
        override val id: UInt,
        override val target: Target,
        override val setupResult: SetupResult,
        private val control: Transport,
    ) : PacketFlow {
        override val kind: FlowKind = FlowKind.Udp

        override val isOpen: Boolean get() = control.isOpen

        override fun sendPacket(payload: ByteArray) = lane!!.send(id, payload)

        override fun receivePacket(timeoutMillis: Long): ByteArray? = lane!!.receive(id, timeoutMillis)

        // The byte-stream half of Flow routes to the packet methods rather than
        // being half-implemented: a caller reaching for `write` here would
        // otherwise frame a packet twice.
        override fun write(bytes: ByteArray) = sendPacket(bytes)

        override fun flush() = Unit

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val packet = receivePacket(READ_MILLIS) ?: return -1
            val take = minOf(length, packet.size)
            packet.copyInto(into, offset, 0, take)
            return take
        }

        override fun close() {
            lane?.close(id)
            control.close()
        }
    }

    /** The flow view over one stream. */
    private class StreamFlow(
        override val id: UInt,
        override val target: Target,
        override val kind: FlowKind,
        override val setupResult: SetupResult,
        private val transport: Transport,
    ) : Flow {
        override val isOpen: Boolean get() = transport.isOpen

        override fun write(bytes: ByteArray) = transport.write(bytes)

        override fun flush() = transport.flush()

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int = transport.read(into, offset, length)

        override fun close() = transport.close()
    }
}
