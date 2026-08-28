// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowOrigin
import eu.nodepass.somewhere.protocol.frame.FlowRejected
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NW-P-01 and NW-P-19 over QUIC: one AuthFrame per connection, on the first
 * stream, and never again.
 *
 * The carrier is tested over fake streams rather than a real connection for the
 * reason `FakeTransport` exists at all: the failure that matters most is a
 * Portal that neither answers nor closes, and that cannot be demanded on cue
 * from a real one.
 */
class QuicCarrierTest {
    private val key = (SharedKey.of("secret") as DecodeResult.Ok).value
    private val session = SessionId.of(ByteArray(16) { it.toByte() })
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    /** Streams handed out in order, each a fake with its own scripted peer. */
    private class Streams(
        private vararg val transports: FakeTransport,
    ) : QuicCarrier.StreamFactory {
        var opened = 0
            private set

        override fun open(): Transport = transports[opened++]
    }

    private fun quicStream(peer: ByteArray = byteArrayOf(0)) = FakeTransport(transportKind = TransportKind.Quic, peerBytes = peer)

    @Test
    fun theFirstStreamCarriesTheAuthFrameAndTheSecondCarriesNone() {
        // The one thing that differs from a dedicated lane. A lane is a
        // connection, so it authenticates every time; a QUIC connection
        // outlives its flows, and a second AuthFrame is a protocol error rather
        // than a harmless repeat.
        val first = quicStream()
        val second = quicStream()
        val carrier = QuicCarrier(Streams(first, second), key, session)

        assertTrue(carrier.openFlow(target, FlowKind.Tcp, 1u) is DecodeResult.Ok)
        assertTrue(carrier.openFlow(target, FlowKind.Tcp, 2u) is DecodeResult.Ok)

        val expectedAuth =
            Authentication.encodeFrame(key, AuthTransport.Quic, first.exporter, session.toByteArray())
        assertEquals(
            "the first stream opens with the AuthFrame",
            expectedAuth.toHex(),
            first.writtenBytes().copyOf(Authentication.FRAME_LENGTH).toHex(),
        )

        // The second stream's first five bytes are a FlowHeader, not the first
        // half of a session id — which is what the assertion is really about.
        val header = FlowHeader.decode(second.writtenBytes().copyOf(5), FlowOrigin.Client)
        assertTrue("the second stream starts with a FlowHeader", header is DecodeResult.Ok)
        assertEquals(2u, (header as DecodeResult.Ok).value.flowId)
    }

    @Test
    fun theTagIsBoundToQuicRatherThanToTls() {
        // The transport byte exists so a tag captured on one carrier cannot be
        // replayed on the other. Same key, same exporter, same session id.
        val stream = quicStream()
        QuicCarrier(Streams(stream), key, session).openFlow(target, FlowKind.Tcp, 1u)

        val written = stream.writtenBytes().copyOf(Authentication.FRAME_LENGTH)
        val asTls = Authentication.encodeFrame(key, AuthTransport.TlsTcp, stream.exporter, session.toByteArray())
        assertNotEquals("a QUIC tag must not equal the TLS tag", asTls.toHex(), written.toHex())
    }

    @Test
    fun bothDirectionsOfTheHeaderNameQuic() {
        val stream = quicStream()
        QuicCarrier(Streams(stream), key, session).openFlow(target, FlowKind.Tcp, 7u)

        val header =
            FlowHeader.decode(
                stream.writtenBytes().copyOfRange(Authentication.FRAME_LENGTH, Authentication.FRAME_LENGTH + 5),
                FlowOrigin.Client,
            )
        val value = (header as DecodeResult.Ok).value
        assertEquals(FlowCarrier.Quic, value.up)
        assertEquals(FlowCarrier.Quic, value.down)
    }

    @Test
    fun aRejectionReachesTheCallerAsFlowRejected() {
        // The L2 defect, asserted rather than waited for. `dial_failed` reached
        // the caller as a named result over one carrier and as an
        // unclassifiable string over another, and the seven explanations the
        // app renders are matched on this interface.
        val stream = quicStream(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte()))
        val opened = QuicCarrier(Streams(stream), key, session).openFlow(target, FlowKind.Tcp, 1u)

        val reason = (opened as DecodeResult.Invalid).reason
        assertTrue("a QUIC rejection must be a FlowRejected", reason is FlowRejected)
        assertEquals(SetupResult.DialFailed, (reason as FlowRejected).result)
    }

    @Test
    fun silenceIsNoAnswerRatherThanAnException() {
        // A rejected AuthFrame is met with silence, not a close: a prompt close
        // would itself be a signal. So the read timeout is the only thing
        // between a wrong key and a hang, and a timeout has to mean the same
        // thing as an end of stream.
        val stream = FakeTransport(transportKind = TransportKind.Quic, silentPeer = true)
        val opened = QuicCarrier(Streams(stream), key, session).openFlow(target, FlowKind.Tcp, 1u)

        assertTrue(opened is DecodeResult.Invalid)
        assertEquals(QuicCarrierReason.NoSetupByte, (opened as DecodeResult.Invalid).reason)
    }

    @Test
    fun theDeadlineComesOffOnlyWhenAFlowIsOpen() {
        val ready = quicStream()
        QuicCarrier(Streams(ready), key, session).openFlow(target, FlowKind.Tcp, 1u)
        assertEquals("the deadline is lifted after READY", listOf(0), ready.readTimeouts)

        val rejected = quicStream(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte()))
        QuicCarrier(Streams(rejected), key, session).openFlow(target, FlowKind.Tcp, 1u)
        assertTrue("a rejected flow never lifts it", rejected.readTimeouts.isEmpty())
    }

    @Test
    fun aRejectedFirstFlowStillCountsAsHavingAuthenticated() {
        // Authentication has no response frame, so success is not observable.
        // What is observable is that this connection already offered one, and
        // offering a second is what the specification forbids — whatever the
        // first flow's own outcome was.
        val first = quicStream(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte()))
        val second = quicStream()
        val carrier = QuicCarrier(Streams(first, second), key, session)

        assertTrue(carrier.openFlow(target, FlowKind.Tcp, 1u) is DecodeResult.Invalid)
        assertTrue(carrier.hasAuthenticated)

        carrier.openFlow(target, FlowKind.Tcp, 2u)
        val header = FlowHeader.decode(second.writtenBytes().copyOf(5), FlowOrigin.Client)
        assertTrue("no second AuthFrame after a rejected first flow", header is DecodeResult.Ok)
    }

    @Test
    fun theOpeningIsOneWriteAndOneFlush() {
        val stream = quicStream()
        QuicCarrier(Streams(stream), key, session)
            .openFlow(target, FlowKind.Tcp, 1u, firstPayload = "hello".encodeToByteArray())

        assertEquals("exactly one flush", 1, stream.flushCount)
        val written = stream.writtenBytes()
        val decodedTarget = Target.decode(written, offset = Authentication.FRAME_LENGTH + 5)
        val payloadStart = Authentication.FRAME_LENGTH + 5 + (decodedTarget as DecodeResult.Ok).value.consumed
        assertEquals("hello", String(written.copyOfRange(payloadStart, written.size)))
    }

    @Test
    fun aClosedCarrierOpensNothing() {
        val carrier = QuicCarrier(Streams(quicStream()), key, session)
        carrier.close()
        val opened = carrier.openFlow(target, FlowKind.Tcp, 1u)
        assertEquals(QuicCarrierReason.CarrierClosed, (opened as DecodeResult.Invalid).reason)
        assertFalse(carrier.hasAuthenticated)
    }
}
