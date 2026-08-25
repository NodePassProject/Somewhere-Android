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
import eu.nodepass.somewhere.protocol.frame.FlowRole
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import eu.nodepass.somewhere.protocol.target.Target

sealed interface LaneReason : DecodeReason {
    data object AlreadyUsed : LaneReason {
        override val detail: String = "a dedicated lane carries one flow and has already carried it"
    }

    data object TransportClosed : LaneReason {
        override val detail: String = "the transport closed before the flow was established"
    }

    data object NoSetupByte : LaneReason {
        override val detail: String =
            "the Portal did not answer — authentication most likely failed"
    }

    data class Rejected(
        val result: SetupResult,
    ) : LaneReason {
        override val detail: String = "the Portal refused the flow: ${result.name}"
    }

    data class HeaderInvalid(
        val cause: DecodeReason,
    ) : LaneReason {
        override val detail: String = "could not build the flow header: ${cause.detail}"
    }
}

/**
 * One TLS connection carrying exactly one logical flow. NW-P-10, NW-P-11.
 *
 * The simplest carrier and the one L1 ships. A lane is used once and closed;
 * there is no reuse and no warm pool — the 1.7 `pool` parameter was **removed in
 * 1.8**, and implementing one would be implementing a protocol feature that no
 * longer exists.
 *
 * ## The single write
 *
 * A cold connection may write `AuthFrame ‖ FlowHeader ‖ Target ‖ first payload`
 * in one go, and this does, because the alternative is worse than merely slower:
 * four separate writes produce four distinguishable packets with client-side
 * timing between them, every time, on every connection. One write is one packet
 * shaped like any other TLS record.
 *
 * ## Why failure is quiet
 *
 * Authentication has no response frame. A Portal that rejects the tag closes
 * with nothing written — deliberately, so that failure is not an oracle for
 * active probing. This means a rejected authentication is observed here as
 * *end-of-stream where a setup byte was expected*, which is [LaneReason.NoSetupByte]
 * and is reported as "authentication most likely failed" rather than a network
 * error, because that is what it almost always is.
 */
class DedicatedTlsLane(
    private val transport: Transport,
    private val sharedKey: SharedKey,
    private val sessionId: SessionId,
) : AutoCloseable {
    private var used = false
    private var flow: LaneFlow? = null

    /** A lane carries one flow. Once opened, it cannot open another. */
    val hasCapacity: Boolean get() = !used && transport.isOpen

    /**
     * Authenticates, opens the flow, and reads the Portal's answer.
     *
     * @param firstPayload bytes to append to the opening write. Passing the
     *   caller's first payload here is what makes the single write possible; it
     *   is optional because a caller may not have any yet.
     */
    fun open(
        target: Target,
        kind: FlowKind,
        flowId: UInt,
        firstPayload: ByteArray = ByteArray(0),
    ): DecodeResult<Flow> {
        if (used) return invalid(LaneReason.AlreadyUsed)
        if (!transport.isOpen) return invalid(LaneReason.TransportClosed)
        used = true

        val carrier =
            when (transport.transportKind) {
                TransportKind.TlsTcp -> FlowCarrier.TlsTcp
                TransportKind.Quic -> FlowCarrier.Quic
            }
        val header =
            when (
                val built =
                    FlowHeader.forClient(FlowRole.Duplex, kind, carrier, carrier, flowId)
            ) {
                is DecodeResult.Invalid -> return invalid(LaneReason.HeaderInvalid(built.reason))
                is DecodeResult.Ok -> built.value
            }

        val authTransport =
            when (transport.transportKind) {
                TransportKind.TlsTcp -> AuthTransport.TlsTcp
                TransportKind.Quic -> AuthTransport.Quic
            }
        val authFrame =
            Authentication.encodeFrame(
                sharedKey = sharedKey,
                transport = authTransport,
                exporter = transport.exporter,
                sessionId = sessionId.toByteArray(),
            )

        // One write. See the class comment.
        val opening = authFrame + header.encode() + target.encode() + firstPayload
        transport.write(opening)
        transport.flush()

        val setup = readSetupByte() ?: return invalid(LaneReason.NoSetupByte)
        val result =
            when (val decoded = SetupResult.decode(setup)) {
                is DecodeResult.Invalid -> return invalid(LaneReason.HeaderInvalid(decoded.reason))
                is DecodeResult.Ok -> decoded.value
            }
        if (result.isRejection) return invalid(LaneReason.Rejected(result))

        // Setup is over, so the deadline that protected it comes off.
        //
        // Until READY the timeout is the only thing between a wrong key and a
        // hang, because a Portal answers a rejected AuthFrame with silence
        // rather than a close. Afterwards it is the opposite: a tunnel that
        // dropped a connection because nothing was said for fifteen seconds
        // would break every idle SSH session, every websocket and every long
        // poll — quiet is what most connections do most of the time. Observed
        // as `downstream pump ended: Read timed out` on a device, on flows
        // that were perfectly healthy.
        transport.setReadTimeout(0)

        return LaneFlow(flowId, target, kind, result, transport).also { flow = it }.ok()
    }

    /**
     * Reads the Portal's setup byte, or null if none arrives.
     *
     * **A rejected AuthFrame produces silence, not a close.** Observed against a
     * live Portal: a connection whose tag does not verify is neither answered
     * nor closed — it is left open and ignored until something times it out.
     * That is a stronger anti-probing posture than closing, because a prompt
     * close is itself a distinguishable signal.
     *
     * The consequence for this client is that **the read timeout is the only
     * thing separating a wrong key from a hang**, so a timeout is treated as the
     * same outcome as an end of stream: no setup byte, most likely
     * authentication. The caller sets the timeout on the socket; without one
     * this call would never return.
     */
    private fun readSetupByte(): Byte? {
        val buffer = ByteArray(1)
        return try {
            if (transport.read(buffer, 0, 1) == 1) buffer[0] else null
        } catch (_: java.io.IOException) {
            // Timeout or reset. Both mean the same thing here: no answer came.
            null
        }
    }

    override fun close() {
        flow?.close()
        transport.close()
    }

    /** The flow view over a lane whose whole transport belongs to it. */
    private class LaneFlow(
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

    companion object {
        /**
         * The Portal reclaims a connection whose first FlowHeader byte does not
         * arrive within this long after authentication (NW-P-11).
         *
         * Not enforced here — [open] writes the header in the same call — but
         * recorded so that anything which ever separates the two knows the
         * budget it is spending.
         */
        const val BOOTSTRAP_DEADLINE_SECONDS: Int = 40
    }
}
