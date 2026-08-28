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
 * The AuthFrame is marked sent when it is **written**, not when it succeeds.
 * Authentication has no response frame: a Portal that answered differently on
 * failure would be an oracle for active probing, so success is not observable
 * here at all. What is observable is that this connection has already offered
 * one, and offering a second is the thing the specification forbids.
 *
 * ## Rejections have one shape
 *
 * [QuicCarrierReason.Rejected] implements [FlowRejected] like the lane's and the
 * Mux carrier's. That interface exists because the same rejection reached the
 * caller as a named `DIAL_FAILED` over one carrier and as an unclassifiable
 * string over another, and the seven explanations the app renders are matched
 * on exactly this — so a third carrier growing a third shape would degrade all
 * seven to a generic failure, on screen, with every gate green.
 */
class QuicCarrier(
    private val streams: StreamFactory,
    private val sharedKey: SharedKey,
    private val sessionId: SessionId,
) : AutoCloseable {
    /** Opens one client-initiated bidirectional stream as a byte transport. */
    fun interface StreamFactory {
        fun open(): Transport
    }

    private var authenticated = false
    private var closed = false
    private val open = mutableListOf<Transport>()

    /** Whether this connection has already offered its AuthFrame. */
    val hasAuthenticated: Boolean get() = authenticated

    fun openFlow(
        target: Target,
        kind: FlowKind,
        flowId: UInt,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        if (closed) return invalid(QuicCarrierReason.CarrierClosed)

        val transport = streams.open()
        open += transport

        val header =
            when (
                val built =
                    FlowHeader.forClient(
                        FlowRole.Duplex,
                        kind,
                        FlowCarrier.Quic,
                        FlowCarrier.Quic,
                        flowId,
                    )
            ) {
                is DecodeResult.Invalid -> return invalid(QuicCarrierReason.HeaderInvalid(built.reason))
                is DecodeResult.Ok -> built.value
            }

        // Exactly once per connection, and before anything else on the wire.
        val authFrame =
            if (authenticated) {
                ByteArray(0)
            } else {
                Authentication.encodeFrame(
                    sharedKey = sharedKey,
                    transport = AuthTransport.Quic,
                    exporter = transport.exporter,
                    sessionId = sessionId.toByteArray(),
                )
            }
        authenticated = true

        transport.write(authFrame + header.encode() + target.encode() + firstPayload)
        transport.flush()

        val setup = readSetupByte(transport) ?: return invalid(QuicCarrierReason.NoSetupByte)
        val result =
            when (val decoded = SetupResult.decode(setup)) {
                is DecodeResult.Invalid -> return invalid(QuicCarrierReason.HeaderInvalid(decoded.reason))
                is DecodeResult.Ok -> decoded.value
            }
        if (result.isRejection) return invalid(QuicCarrierReason.Rejected(result))

        // Setup is over, so the deadline that protected it comes off. Before
        // READY it is the only thing between a wrong key and a hang, because a
        // Portal answers a rejected AuthFrame with silence; afterwards it would
        // close every idle SSH session, websocket and long poll.
        transport.setReadTimeout(0)

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
