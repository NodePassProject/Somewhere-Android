// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowOrigin
import eu.nodepass.somewhere.protocol.frame.FlowRole
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NW-P-10 and NW-P-11: one flow per lane, one opening write, no reuse. */
class DedicatedTlsLaneTest {
    private val key = (SharedKey.of("secret") as DecodeResult.Ok).value
    private val session = SessionId.of(ByteArray(16) { it.toByte() })
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    private fun lane(
        peer: ByteArray = byteArrayOf(0),
        transport: FakeTransport = FakeTransport(peerBytes = peer),
    ) = transport to DedicatedTlsLane(transport, key, session)

    @Test
    fun theOpeningWriteIsOneWriteInTheSpecifiedOrder() {
        // NW-P-10 permits AuthFrame || FlowHeader || Target || payload in a
        // single write, and this takes it — four separate writes would produce
        // four distinguishable packets with client timing between them, on every
        // connection.
        val (transport, lane) = lane()
        val payload = "hello".encodeToByteArray()
        val opened = lane.open(target, FlowKind.Tcp, flowId = 1u, firstPayload = payload)
        assertTrue("open should succeed: ${opened.reasonOrNull()?.detail}", opened is DecodeResult.Ok)

        val written = transport.writtenBytes()
        val expectedAuth =
            Authentication.encodeFrame(key, AuthTransport.TlsTcp, transport.exporter, session.toByteArray())
        assertEquals("frame starts with the AuthFrame", expectedAuth.toHex(), written.copyOf(32).toHex())

        val header = FlowHeader.decode(written.copyOfRange(32, 37), FlowOrigin.Client)
        assertEquals(FlowRole.Duplex, (header as DecodeResult.Ok).value.role)
        assertEquals(1u, header.value.flowId)

        val decodedTarget = Target.decode(written, offset = 37)
        assertEquals(target, (decodedTarget as DecodeResult.Ok).value.target)

        val payloadStart = 37 + decodedTarget.value.consumed
        assertEquals("the payload rides the same write", "hello", String(written.copyOfRange(payloadStart, written.size)))
        assertEquals("exactly one flush", 1, transport.flushCount)
    }

    @Test
    fun aReadySetupByteYieldsAnOpenFlow() {
        val (_, lane) = lane(peer = byteArrayOf(SetupResult.Ready.byte.toByte()))
        val flow = (lane.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        assertEquals(SetupResult.Ready, flow.setupResult)
        assertEquals(1u, flow.id)
        assertTrue(flow.isOpen)
    }

    @Test
    fun everyRejectionReachesTheCallerAsItself() {
        // NW-P-06 again, at this layer: the lane must not flatten seven
        // rejections into one failure.
        SetupResult.entries.filter { it.isRejection }.forEach { rejection ->
            val (_, lane) = lane(peer = byteArrayOf(rejection.byte.toByte()))
            val result = lane.open(target, FlowKind.Tcp, 1u)
            val reason = result.reasonOrNull()
            assertTrue("$rejection should surface as a rejection", reason is LaneReason.Rejected)
            assertEquals(rejection, (reason as LaneReason.Rejected).result)
        }
    }

    @Test
    fun aPortalThatClosesWithoutAnsweringReadsAsAuthenticationFailure() {
        // The important one. Authentication has no response frame — a Portal
        // that rejects the tag closes with nothing written, so silence is what
        // failure looks like, and calling it a network error would send the user
        // to debug the wrong thing.
        val (_, lane) = lane(peer = ByteArray(0))
        val result = lane.open(target, FlowKind.Tcp, 1u)
        assertEquals(LaneReason.NoSetupByte, result.reasonOrNull())
        assertTrue(
            "the message should point at authentication",
            result.reasonOrNull()!!.detail.contains("authentication"),
        )
    }

    @Test
    fun aSilentPortalIsTreatedTheSameAsOneThatClosed() {
        // The real behaviour, learned from a live Portal: a rejected AuthFrame
        // gets no answer AND no close, so the read times out. Both outcomes mean
        // the same thing to a caller, and neither may escape as an exception.
        val transport = FakeTransport(silentPeer = true)
        val lane = DedicatedTlsLane(transport, key, session)
        val result = lane.open(target, FlowKind.Tcp, 1u)
        assertEquals(LaneReason.NoSetupByte, result.reasonOrNull())
    }

    @Test
    fun aLaneCarriesExactlyOneFlow() {
        val (_, lane) = lane()
        assertTrue(lane.hasCapacity)
        lane.open(target, FlowKind.Tcp, 1u)
        assertTrue("a used lane has no capacity", !lane.hasCapacity)
        assertEquals(LaneReason.AlreadyUsed, lane.open(target, FlowKind.Tcp, 2u).reasonOrNull())
    }

    @Test
    fun aClosedTransportIsRefusedBeforeAnythingIsWritten() {
        val transport = FakeTransport(peerBytes = byteArrayOf(0))
        transport.close()
        val lane = DedicatedTlsLane(transport, key, session)
        assertEquals(LaneReason.TransportClosed, lane.open(target, FlowKind.Tcp, 1u).reasonOrNull())
        assertEquals("nothing may be written to a closed transport", 0, transport.writtenBytes().size)
    }

    @Test
    fun aZeroFlowIdIsRefusedByTheHeaderRules() {
        val (_, lane) = lane()
        val reason = lane.open(target, FlowKind.Tcp, 0u).reasonOrNull()
        assertTrue("flow 0 must not reach the wire", reason is LaneReason.HeaderInvalid)
    }

    @Test
    fun theFlowReadsAndWritesThroughTheTransport() {
        val transport = FakeTransport(peerBytes = byteArrayOf(0) + "response".encodeToByteArray())
        val lane = DedicatedTlsLane(transport, key, session)
        val flow = (lane.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value

        flow.write("request".encodeToByteArray())
        flow.flush()
        assertTrue(transport.writtenBytes().toHex().endsWith("request".encodeToByteArray().toHex()))

        val buffer = ByteArray(64)
        val count = flow.read(buffer)
        assertEquals("response", String(buffer, 0, count))
    }

    @Test
    fun closingTheFlowClosesTheTransport() {
        // At L1 the lane's whole transport belongs to the flow, so the two die
        // together. That stops being true at L2, which is why callers hold flows.
        val transport = FakeTransport(peerBytes = byteArrayOf(0))
        val lane = DedicatedTlsLane(transport, key, session)
        val flow = (lane.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        flow.close()
        assertTrue(!transport.isOpen)
    }

    @Test
    fun theBootstrapDeadlineIsRecorded() {
        assertEquals("NW-P-11", 40, DedicatedTlsLane.BOOTSTRAP_DEADLINE_SECONDS)
    }
}
