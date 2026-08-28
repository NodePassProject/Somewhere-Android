// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowOrigin
import eu.nodepass.somewhere.protocol.frame.FlowRejected
import eu.nodepass.somewhere.protocol.frame.FlowRole
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.frame.UdpOverTcp
import eu.nodepass.somewhere.protocol.quic.QuicDatagram
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape of a split flow, checked without a Portal.
 *
 * The device cases prove a real Portal accepts these lanes and answers them.
 * These prove what is *written*, which a Portal's acceptance does not: a Portal
 * that tolerated a wrong hop count or a Target on the ATTACH half would leave
 * the client shipping a frame the specification forbids.
 */
class SplitCarrierTest {
    private val key = (SharedKey.of("secret") as DecodeResult.Ok).value
    private val session = SessionId.of(ByteArray(16) { it.toByte() })
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    private fun lane(
        kind: TransportKind,
        peer: ByteArray = ByteArray(0),
    ) = FakeTransport(
        exporter = ByteArray(32) { (it + kind.ordinal).toByte() },
        transportKind = kind,
        peerBytes = peer,
    )

    private fun carrier(
        up: FakeTransport,
        down: FakeTransport,
    ) = SplitCarrier({ up }, { down }, key, session)

    @Test
    fun theUplinkCarriesOpenWithTheTargetAndTheDownlinkCarriesAttachWithout() {
        val up = lane(TransportKind.Quic)
        val down = lane(TransportKind.TlsTcp, peer = byteArrayOf(0))

        val opened = carrier(up, down).openFlow(target, FlowKind.Tcp, 5u)
        assertTrue("the flow should open: ${opened.reasonOrNull()?.detail}", opened is DecodeResult.Ok)

        val openHeader =
            FlowHeader.decode(
                up.writtenBytes().copyOfRange(Authentication.FRAME_LENGTH, Authentication.FRAME_LENGTH + 5),
                FlowOrigin.Client,
            )
        assertEquals(FlowRole.Open, (openHeader as DecodeResult.Ok).value.role)
        assertEquals("the uplink must name the uplink carrier", FlowCarrier.Quic, openHeader.value.up)
        assertEquals(FlowCarrier.TlsTcp, openHeader.value.down)

        val attachHeader =
            FlowHeader.decode(
                down.writtenBytes().copyOfRange(Authentication.FRAME_LENGTH, Authentication.FRAME_LENGTH + 5),
                FlowOrigin.Client,
            )
        assertEquals(FlowRole.Attach, (attachHeader as DecodeResult.Ok).value.role)
        assertEquals("both halves must agree about the carriers", FlowCarrier.Quic, attachHeader.value.up)
        assertEquals(FlowCarrier.TlsTcp, attachHeader.value.down)
        assertEquals("both halves must carry one flow id", openHeader.value.flowId, attachHeader.value.flowId)

        // ATTACH carries no Target: the header is the last thing on it.
        assertEquals(
            "ATTACH wrote something after its header",
            Authentication.FRAME_LENGTH + 5,
            down.writtenBytes().size,
        )
    }

    @Test
    fun eachHalfIsTaggedForItsOwnCarrier() {
        // A tag computed for one carrier must not verify on the other — that is
        // what the transport byte is for, and a split flow is the one place a
        // client writes both on the same session.
        val up = lane(TransportKind.Quic)
        val down = lane(TransportKind.TlsTcp, peer = byteArrayOf(0))
        carrier(up, down).openFlow(target, FlowKind.Tcp, 1u)

        assertArrayEquals(
            Authentication.encodeFrame(key, AuthTransport.Quic, up.exporter, session.toByteArray()),
            up.writtenBytes().copyOf(Authentication.FRAME_LENGTH),
        )
        assertArrayEquals(
            Authentication.encodeFrame(key, AuthTransport.TlsTcp, down.exporter, session.toByteArray()),
            down.writtenBytes().copyOf(Authentication.FRAME_LENGTH),
        )
        assertNotEquals(
            "the two halves must not share a tag",
            up.writtenBytes().copyOf(Authentication.FRAME_LENGTH).toList(),
            down.writtenBytes().copyOf(Authentication.FRAME_LENGTH).toList(),
        )
    }

    @Test
    fun theResultIsReadFromTheDownlinkAndTheUplinkIsNeverRead() {
        // The uplink's scripted peer would answer a rejection if anything read
        // it. Nothing does, so the flow opens.
        val up = lane(TransportKind.Quic, peer = byteArrayOf(SetupResult.DialFailed.byte.toByte()))
        val down = lane(TransportKind.TlsTcp, peer = byteArrayOf(0))

        val opened = carrier(up, down).openFlow(target, FlowKind.Tcp, 1u)
        assertTrue("the uplink's byte was read as the result", opened is DecodeResult.Ok)
    }

    @Test
    fun aRejectionOnTheDownlinkReachesTheCallerAsFlowRejected() {
        val up = lane(TransportKind.Quic)
        val down = lane(TransportKind.TlsTcp, peer = byteArrayOf(SetupResult.PairTimeout.byte.toByte()))

        val opened = carrier(up, down).openFlow(target, FlowKind.Tcp, 1u)
        val reason = (opened as DecodeResult.Invalid).reason
        assertTrue("a split rejection must be a FlowRejected", reason is FlowRejected)
        assertEquals(SetupResult.PairTimeout, (reason as FlowRejected).result)
    }

    @Test
    fun silenceOnTheDownlinkIsNoAnswerRatherThanAnException() {
        val up = lane(TransportKind.Quic)
        val down =
            FakeTransport(transportKind = TransportKind.TlsTcp, silentPeer = true)
        val opened = SplitCarrier({ up }, { down }, key, session).openFlow(target, FlowKind.Tcp, 1u)
        assertEquals(SplitReason.NoSetupByte, (opened as DecodeResult.Invalid).reason)
    }

    @Test
    fun aQuicCarrierAuthenticatesOnceAndATlsLaneEveryTime() {
        // The one asymmetry between the two sides, and the reason it is read off
        // the transport rather than configured: a TLS lane *is* a connection, a
        // QUIC connection is not a lane.
        val ups = listOf(lane(TransportKind.Quic), lane(TransportKind.Quic))
        val downs =
            listOf(
                lane(TransportKind.TlsTcp, peer = byteArrayOf(0)),
                lane(TransportKind.TlsTcp, peer = byteArrayOf(0)),
            )
        var upIndex = 0
        var downIndex = 0
        val split = SplitCarrier({ ups[upIndex++] }, { downs[downIndex++] }, key, session)

        split.openFlow(target, FlowKind.Tcp, 1u)
        split.openFlow(target, FlowKind.Tcp, 2u)

        val secondUp = FlowHeader.decode(ups[1].writtenBytes().copyOf(5), FlowOrigin.Client)
        assertTrue("a second QUIC lane must not repeat the AuthFrame", secondUp is DecodeResult.Ok)

        assertArrayEquals(
            "every TLS lane carries its own AuthFrame",
            Authentication.encodeFrame(key, AuthTransport.TlsTcp, downs[1].exporter, session.toByteArray()),
            downs[1].writtenBytes().copyOf(Authentication.FRAME_LENGTH),
        )
    }

    /** A fake DATAGRAM side: what was sent, and what to hand back. */
    private class FakeDatagrams(
        private val max: Int = 1200,
    ) : QuicCarrier.Datagrams {
        val sent: MutableList<ByteArray> = mutableListOf()
        val incoming: ArrayDeque<ByteArray> = ArrayDeque()

        override fun send(bytes: ByteArray) {
            sent += bytes
        }

        override fun receive(timeoutMillis: Long): ByteArray? = incoming.removeFirstOrNull()

        override fun maxDatagram(): Int = max
    }

    @Test
    fun aUdpFlowFramesTheUplinkForQuicAndReadsTheDownlinkAsAStream() {
        // The one place in this client where a single flow uses two framings,
        // because the protocol lets one flow use two carriers.
        val packet = ByteArray(64) { it.toByte() }
        val up = lane(TransportKind.Quic)
        val down =
            FakeTransport(
                transportKind = TransportKind.TlsTcp,
                peerBytes = byteArrayOf(0) + (UdpOverTcp.encode(packet) as DecodeResult.Ok).value,
            )
        val datagrams = FakeDatagrams()

        val opened =
            SplitCarrier({ up }, { down }, key, session, datagrams)
                .openFlow(target, FlowKind.Udp, 3u, firstPayload = packet)
        val flow = (opened as DecodeResult.Ok).value as PacketFlow

        assertArrayEquals(
            "the uplink is QUIC, so the packet is a DATAGRAM",
            QuicDatagram.Data(3u, packet).encode(),
            datagrams.sent.single(),
        )
        assertArrayEquals(
            "the downlink is TLS, so the packet is length-prefixed",
            packet,
            flow.receivePacket(1_000),
        )
    }

    @Test
    fun aUdpFlowFramesTheUplinkAsAStreamAndReadsTheDownlinkForQuic() {
        val packet = ByteArray(48) { (it * 3).toByte() }
        val up = lane(TransportKind.TlsTcp)
        val down = lane(TransportKind.Quic, peer = byteArrayOf(0))
        val datagrams = FakeDatagrams()
        datagrams.incoming.add(QuicDatagram.Data(4u, packet).encode())

        val opened =
            SplitCarrier({ up }, { down }, key, session, datagrams)
                .openFlow(target, FlowKind.Udp, 4u, firstPayload = packet)
        val flow = (opened as DecodeResult.Ok).value as PacketFlow

        val framed = (UdpOverTcp.encode(packet) as DecodeResult.Ok).value
        val written = up.writtenBytes()
        assertArrayEquals(
            "the uplink is TLS, so the packet is length-prefixed after the header",
            framed,
            written.copyOfRange(written.size - framed.size, written.size),
        )
        assertTrue("no datagram should have been sent", datagrams.sent.isEmpty())
        assertArrayEquals("the downlink is QUIC, so the packet is a DATAGRAM", packet, flow.receivePacket(1_000))
    }

    @Test
    fun aUdpSplitFlowIsRefusedWhenTheQuicHalfCarriesNoDatagrams() {
        val opened =
            SplitCarrier({ lane(TransportKind.Quic) }, { lane(TransportKind.TlsTcp, byteArrayOf(0)) }, key, session)
                .openFlow(target, FlowKind.Udp, 1u)
        assertEquals(SplitReason.NoDatagrams, (opened as DecodeResult.Invalid).reason)
    }

    @Test
    fun aUdpFlowsOpeningWriteCarriesNoPayload() {
        // The first packet does not ride the opening write in either framing:
        // bytes after the Target would be read as a second one.
        val up = lane(TransportKind.Quic)
        val down = lane(TransportKind.TlsTcp, peer = byteArrayOf(0))
        SplitCarrier({ up }, { down }, key, session, FakeDatagrams())
            .openFlow(target, FlowKind.Udp, 6u, firstPayload = ByteArray(16))

        val decodedTarget = Target.decode(up.writtenBytes(), offset = Authentication.FRAME_LENGTH + 5)
        assertEquals(
            "the opening write carried payload",
            Authentication.FRAME_LENGTH + 5 + (decodedTarget as DecodeResult.Ok).value.consumed,
            up.writtenBytes().size,
        )
    }

    @Test
    fun aClosedCarrierOpensNothing() {
        val split = carrier(lane(TransportKind.Quic), lane(TransportKind.TlsTcp))
        split.close()
        assertEquals(
            SplitReason.CarrierClosed,
            (split.openFlow(target, FlowKind.Tcp, 1u) as DecodeResult.Invalid).reason,
        )
    }
}
