// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRejected
import eu.nodepass.somewhere.protocol.frame.FlowRole
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target

/** Why a split flow could not be opened. */
sealed interface SplitReason : DecodeReason {
    data object CarrierClosed : SplitReason {
        override val detail: String = "the session is closed"
    }

    data object NoSetupByte : SplitReason {
        override val detail: String = "the Portal did not answer the downlink"
    }

    data class Rejected(
        override val result: SetupResult,
    ) : SplitReason,
        FlowRejected {
        override val detail: String = result.name
    }

    data class HeaderInvalid(
        val reason: DecodeReason,
    ) : SplitReason {
        override val detail: String = reason.detail
    }
}

/**
 * A flow whose two directions travel on different carriers.
 *
 * ## What the protocol asks for
 *
 * When `up` and `down` name different carriers the flow is **two
 * client-initiated lanes**: OPEN on the uplink carrying the Target, ATTACH on
 * the downlink carrying none, paired by the Portal on `(session_id, flow_id)`.
 * Their kind, carrier selection and hop count must agree, and disagreeing is a
 * named rejection rather than a mystery.
 *
 * **The result arrives only on ATTACH.** That is not a detail of ordering: the
 * uplink is a lane the client writes and never reads, so there is nowhere else
 * to put it. It also means an OPEN-side problem cannot be reported until the
 * downlink exists, which is why a Portal holds it.
 *
 * ## Why authentication differs between the two sides
 *
 * A TLS lane *is* a connection, so every one carries its own AuthFrame. A QUIC
 * connection outlives its lanes, so it carries one and never again. The rule is
 * read off the transport rather than configured, because a carrier that had to
 * be told which it was could be told wrongly.
 *
 * Both sides use **this session's** id. It is the pairing scope, so a lane
 * carrying any other id is a different session wearing this one's name, and the
 * Portal would pair it with nothing.
 */
class SplitCarrier(
    private val uplink: LaneFactory,
    private val downlink: LaneFactory,
    private val sharedKey: SharedKey,
    private val sessionId: SessionId,
) : AutoCloseable {
    /** Opens one lane on one carrier. */
    fun interface LaneFactory {
        fun open(): Transport
    }

    private var closed = false
    private val open = mutableListOf<Transport>()

    /** How many lanes this carrier is holding, across both directions. */
    val laneCount: Int get() = open.count { it.isOpen }

    /**
     * Which carriers have already offered an AuthFrame.
     *
     * Keyed by kind rather than by lane: that is exactly the distinction, since
     * a TLS lane is a connection and a QUIC connection is not a lane.
     */
    private val authenticated = mutableSetOf<TransportKind>()

    fun openFlow(
        target: Target,
        kind: FlowKind,
        flowId: UInt,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        if (closed) return invalid(SplitReason.CarrierClosed)

        val up = uplink.open().also { open += it }
        val down = downlink.open().also { open += it }

        val upCarrier = up.transportKind.asFlowCarrier()
        val downCarrier = down.transportKind.asFlowCarrier()

        val openHeader =
            when (val built = FlowHeader.forClient(FlowRole.Open, kind, upCarrier, downCarrier, flowId)) {
                is DecodeResult.Invalid -> return invalid(SplitReason.HeaderInvalid(built.reason))
                is DecodeResult.Ok -> built.value
            }
        val attachHeader =
            when (val built = FlowHeader.forClient(FlowRole.Attach, kind, upCarrier, downCarrier, flowId)) {
                is DecodeResult.Invalid -> return invalid(SplitReason.HeaderInvalid(built.reason))
                is DecodeResult.Ok -> built.value
            }

        // OPEN first. The Portal pairs on (session_id, flow_id) and holds
        // whichever half arrives first, so the order is not forced — but the
        // half carrying the Target is the one that can start the dial, and
        // sending it first is the difference between a pairing wait and a
        // dialling wait.
        up.write(authFrameFor(up) + openHeader.encode() + target.encode() + firstPayload)
        up.flush()

        down.write(authFrameFor(down) + attachHeader.encode())
        down.flush()

        val setup = readSetupByte(down) ?: return invalid(SplitReason.NoSetupByte)
        val result =
            when (val decoded = SetupResult.decode(setup)) {
                is DecodeResult.Invalid -> return invalid(SplitReason.HeaderInvalid(decoded.reason))
                is DecodeResult.Ok -> decoded.value
            }
        if (result.isRejection) return invalid(SplitReason.Rejected(result))

        // Setup is over on the half that answers. The uplink never had a
        // deadline of its own to lift: nothing is read from it.
        down.setReadTimeout(0)

        return SplitFlow(flowId, target, kind, result, up, down).ok()
    }

    /**
     * The AuthFrame this lane must carry, or nothing.
     *
     * Marked offered when it is **written**, not when it succeeds:
     * authentication has no response frame, so success is not observable, and
     * what the specification forbids is offering a second one.
     */
    private fun authFrameFor(transport: Transport): ByteArray {
        val kind = transport.transportKind
        val everyLane = kind == TransportKind.TlsTcp
        if (!everyLane && !authenticated.add(kind)) return ByteArray(0)
        if (everyLane) authenticated.add(kind)
        return Authentication.encodeFrame(
            sharedKey = sharedKey,
            transport =
                when (kind) {
                    TransportKind.TlsTcp -> AuthTransport.TlsTcp
                    TransportKind.Quic -> AuthTransport.Quic
                },
            exporter = transport.exporter,
            sessionId = sessionId.toByteArray(),
        )
    }

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

    private fun TransportKind.asFlowCarrier(): FlowCarrier =
        when (this) {
            TransportKind.TlsTcp -> FlowCarrier.TlsTcp
            TransportKind.Quic -> FlowCarrier.Quic
        }

    private fun invalid(reason: DecodeReason): DecodeResult<Flow> = DecodeResult.Invalid(reason)

    private fun Flow.ok(): DecodeResult<Flow> = DecodeResult.Ok(this)

    /**
     * One flow, two lanes.
     *
     * Writes go up and reads come down, and neither lane is ever used the other
     * way. That asymmetry is the whole point of a split flow, and stating it as
     * two fields rather than one transport is what makes it impossible to read
     * from the half that will never answer.
     */
    private class SplitFlow(
        override val id: UInt,
        override val target: Target,
        override val kind: FlowKind,
        override val setupResult: SetupResult,
        private val up: Transport,
        private val down: Transport,
    ) : Flow {
        override val isOpen: Boolean get() = up.isOpen && down.isOpen

        override fun write(bytes: ByteArray) = up.write(bytes)

        override fun flush() = up.flush()

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int = down.read(into, offset, length)

        override fun close() {
            runCatching { up.close() }
            down.close()
        }
    }
}
