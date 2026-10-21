// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowStart
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `docs/protocol.md` § Connection establishment, over TLS: one flow per connection, one opening write. */
class DedicatedTlsLaneTest {
    private val key = (SharedKey.of("0123456789abcdef0123456789abcdef") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    private fun lane(
        peer: ByteArray = byteArrayOf(0),
        transport: FakeTransport = FakeTransport(peerBytes = peer),
    ) = transport to DedicatedTlsLane(transport, key)

    @Test
    fun theOpeningIsOneWriteAndThePayloadWaitsForReady() {
        // AuthFrame || FlowStart || Target in one write, so three records do
        // not leave with client timing between them. The payload is not in it:
        // "target payload waits for READY", and a Portal that reads a byte
        // early ends the flow.
        val (transport, lane) = lane()
        val payload = "hello".encodeToByteArray()
        val opened = lane.open(target, FlowKind.Tcp, logId = 1u, firstPayload = payload)
        assertTrue("open should succeed: ${opened.reasonOrNull()?.detail}", opened is DecodeResult.Ok)

        val written = transport.writtenBytes()
        val expectedAuth = Authentication.encodeFrame(key, AuthTransport.TlsTcp, transport.exporter)
        assertEquals("frame starts with the 16-byte AuthFrame", expectedAuth.toHex(), written.copyOf(16).toHex())

        assertEquals("then one FlowStart byte", FlowKind.Tcp, FlowStart.decode(written[16]).kind)

        val decodedTarget = Target.decode(written, offset = 17)
        assertEquals(target, (decodedTarget as DecodeResult.Ok).value.target)

        val payloadStart = 17 + decodedTarget.value.consumed
        assertEquals("nothing but the opening before READY", payloadStart, transport.writtenBeforeFirstRead)
        assertEquals("the payload follows READY", "hello", String(written.copyOfRange(payloadStart, written.size)))
        assertEquals("one flush for the opening, one for the payload", 2, transport.flushCount)
    }

    @Test
    fun aRejectedFlowNeverSendsItsPayload() {
        val (transport, lane) = lane(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte()))
        lane.open(target, FlowKind.Tcp, logId = 1u, firstPayload = "hello".encodeToByteArray())
        assertEquals("only the opening was written", transport.writtenBeforeFirstRead, transport.writtenBytes().size)
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
        // The lane must not flatten the rejections into one failure.
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
        val lane = DedicatedTlsLane(transport, key)
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
        val lane = DedicatedTlsLane(transport, key)
        assertEquals(LaneReason.TransportClosed, lane.open(target, FlowKind.Tcp, 1u).reasonOrNull())
        assertEquals("nothing may be written to a closed transport", 0, transport.writtenBytes().size)
    }

    @Test
    fun aUdpFlowSaysSoInItsFlowStart() {
        val (transport, lane) = lane()
        lane.open(target, FlowKind.Udp, 1u)
        assertEquals(FlowKind.Udp, FlowStart.decode(transport.writtenBytes()[16]).kind)
    }

    @Test
    fun theFlowReadsAndWritesThroughTheTransport() {
        val transport = FakeTransport(peerBytes = byteArrayOf(0) + "response".encodeToByteArray())
        val lane = DedicatedTlsLane(transport, key)
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
        val lane = DedicatedTlsLane(transport, key)
        val flow = (lane.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        flow.close()
        assertTrue(!transport.isOpen)
    }

    @Test
    fun theHandshakeDeadlineIsRecorded() {
        assertEquals("NOW_HANDSHAKE_TIMEOUT", 5, DedicatedTlsLane.HANDSHAKE_DEADLINE_SECONDS)
    }

    @Test
    fun theSetupDeadlineIsLiftedOnceTheFlowIsOpen() {
        // Two opposite requirements, one connection.
        //
        // Before READY the read timeout is the only thing between a wrong
        // shared key and a hang: upstream answers a rejected AuthFrame with
        // silence rather than a close. After READY the same deadline is a
        // defect — it closes any connection that goes quiet, which is what an
        // idle SSH session, a websocket and a long poll all do by design.
        //
        // Observed on a device as `downstream pump ended: Read timed out` on
        // flows that were working perfectly.
        val (transport, lane) = lane()
        assertTrue(
            "no timeout should be touched before the flow opens",
            transport.readTimeouts.isEmpty(),
        )

        val opened = lane.open(target, FlowKind.Tcp, logId = 1u)
        assertTrue("open should succeed", opened is DecodeResult.Ok)
        assertEquals(
            "the lane must clear the read deadline exactly once, after setup",
            listOf(0),
            transport.readTimeouts,
        )
    }

    @Test
    fun aRejectedOpenLeavesTheDeadlineInPlace() {
        // The mirror of the rule above: a lane that never reached READY has no
        // business relaxing anything, and the connection it holds is about to
        // be closed anyway.
        val (transport, lane) = lane(peer = byteArrayOf(SetupResult.FlowLimit.byte.toByte()))
        val opened = lane.open(target, FlowKind.Tcp, logId = 1u)
        assertTrue("open should be rejected", opened is DecodeResult.Invalid)
        assertTrue(
            "a rejected open must not clear the deadline: ${transport.readTimeouts}",
            transport.readTimeouts.isEmpty(),
        )
    }
}
