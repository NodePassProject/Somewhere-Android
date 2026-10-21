// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRejected
import eu.nodepass.somewhere.protocol.frame.FlowStart
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.quic.DatagramFrame
import eu.nodepass.somewhere.protocol.quic.QuicDatagram
import eu.nodepass.somewhere.protocol.quic.QuicFlowId
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/protocol.md` § Vector establishment, over QUIC: the AuthFrame leads
 * client bidirectional Stream 0 and no other, and every flow's id is its
 * stream's.
 *
 * The carrier is tested over fake streams rather than a real connection for the
 * reason `FakeTransport` exists at all: the failure that matters most is a
 * Portal that neither answers nor closes, and that cannot be demanded on cue
 * from a real one.
 */
class QuicCarrierTest {
    private val key = (SharedKey.of("0123456789abcdef0123456789abcdef") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    /** A fake stream with the id a real connection would give it. */
    private class FakeStream(
        override val streamId: Long,
        private val fake: FakeTransport,
    ) : StreamTransport,
        Transport by fake {
        fun writtenBytes(): ByteArray = fake.writtenBytes()

        val writtenBeforeFirstRead: Int? get() = fake.writtenBeforeFirstRead

        val readTimeouts: List<Int> get() = fake.readTimeouts
    }

    /** Streams handed out in order, numbered as a client's bidirectional streams are: 0, 4, 8, … */
    private class Streams(
        vararg peers: FakeTransport,
        firstId: Long = 0,
    ) : QuicCarrier.StreamFactory {
        val streams = peers.mapIndexed { index, peer -> FakeStream(firstId + 4L * index, peer) }
        private var opened = 0

        override fun open(): StreamTransport = streams[opened++]
    }

    private fun quicStream(peer: ByteArray = byteArrayOf(0)) = FakeTransport(transportKind = TransportKind.Quic, peerBytes = peer)

    @Test
    fun streamZeroCarriesTheAuthFrameAndTheNextCarriesNone() {
        // The one thing that differs from a dedicated lane. A lane is a
        // connection, so it authenticates every time; a QUIC connection
        // outlives its flows, and "authentication remains connection-wide for
        // later Streams".
        val streams = Streams(quicStream(), quicStream())
        val carrier = QuicCarrier(streams, key)

        assertTrue(carrier.openFlow(target, FlowKind.Tcp) is DecodeResult.Ok)
        assertTrue(carrier.openFlow(target, FlowKind.Tcp) is DecodeResult.Ok)

        val (first, second) = streams.streams
        val expectedAuth = Authentication.encodeFrame(key, AuthTransport.Quic, first.exporter)
        assertEquals(
            "Stream 0 opens with the AuthFrame",
            expectedAuth.toHex(),
            first.writtenBytes().copyOf(Authentication.FRAME_LENGTH).toHex(),
        )
        assertEquals("then FlowStart", FlowKind.Tcp, FlowStart.decode(first.writtenBytes()[Authentication.FRAME_LENGTH]).kind)

        // Stream 4 opens with FlowStart + Target and nothing in front of them.
        assertEquals(FlowKind.Tcp, FlowStart.decode(second.writtenBytes()[0]).kind)
        assertEquals(target, (Target.decode(second.writtenBytes(), offset = 1) as DecodeResult.Ok).value.target)
    }

    @Test
    fun eachFlowIsNamedByItsStream() {
        val streams = Streams(quicStream(), quicStream(), quicStream())
        val carrier = QuicCarrier(streams, key)
        val ids = List(3) { (carrier.openFlow(target, FlowKind.Tcp) as DecodeResult.Ok).value.id }
        assertEquals("streams 0, 4, 8 derive 1, 2, 3", listOf(1u, 2u, 3u), ids)
    }

    @Test
    fun theTagIsBoundToQuicRatherThanToTls() {
        // The transport byte exists so a tag captured on one carrier cannot be
        // replayed on the other. Same key, same exporter.
        val streams = Streams(quicStream())
        QuicCarrier(streams, key).openFlow(target, FlowKind.Tcp)

        val stream = streams.streams.single()
        val written = stream.writtenBytes().copyOf(Authentication.FRAME_LENGTH)
        val asTls = Authentication.encodeFrame(key, AuthTransport.TlsTcp, stream.exporter)
        assertNotEquals("a QUIC tag must not equal the TLS tag", asTls.toHex(), written.toHex())
    }

    @Test
    fun aRejectionReachesTheCallerAsFlowRejected() {
        // `dial_failed` once reached the caller as a named result over one
        // carrier and as an unclassifiable string over another, and the
        // explanations the app renders are matched on this interface.
        val opened =
            QuicCarrier(Streams(quicStream(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte()))), key)
                .openFlow(target, FlowKind.Tcp)

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
        val silent = FakeTransport(transportKind = TransportKind.Quic, silentPeer = true)
        val opened = QuicCarrier(Streams(silent), key).openFlow(target, FlowKind.Tcp)
        assertEquals(QuicCarrierReason.NoSetupByte, (opened as DecodeResult.Invalid).reason)
    }

    @Test
    fun theDeadlineComesOffOnlyWhenAFlowIsOpen() {
        val ready = Streams(quicStream())
        QuicCarrier(ready, key).openFlow(target, FlowKind.Tcp)
        assertEquals("the deadline is lifted after READY", listOf(0), ready.streams.single().readTimeouts)

        val rejected = Streams(quicStream(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte())))
        QuicCarrier(rejected, key).openFlow(target, FlowKind.Tcp)
        assertTrue(
            "a rejected flow never lifts it",
            rejected.streams
                .single()
                .readTimeouts
                .isEmpty(),
        )
    }

    @Test
    fun aRejectedFirstFlowDoesNotCauseASecondAuthFrame() {
        // A rejection is per Flow; authentication is per connection, and it
        // happened on Stream 0 whatever that flow's own outcome was.
        val streams = Streams(quicStream(peer = byteArrayOf(SetupResult.DialFailed.byte.toByte())), quicStream())
        val carrier = QuicCarrier(streams, key)

        assertTrue(carrier.openFlow(target, FlowKind.Tcp) is DecodeResult.Invalid)
        carrier.openFlow(target, FlowKind.Tcp)
        assertEquals("Stream 4 opens with FlowStart", 0, streams.streams[1].writtenBytes()[0].toInt())
    }

    @Test
    fun theOpeningIsOneWriteAndThePayloadWaitsForReady() {
        val streams = Streams(quicStream())
        QuicCarrier(streams, key).openFlow(target, FlowKind.Tcp, firstPayload = "hello".encodeToByteArray())

        val stream = streams.streams.single()
        val written = stream.writtenBytes()
        val start = Authentication.FRAME_LENGTH + FlowStart.LENGTH
        val decodedTarget = Target.decode(written, offset = start)
        val payloadStart = start + (decodedTarget as DecodeResult.Ok).value.consumed
        assertEquals("nothing but the opening before READY", payloadStart, stream.writtenBeforeFirstRead)
        assertEquals("hello", String(written.copyOfRange(payloadStart, written.size)))
    }

    @Test
    fun aStreamBeyondTheIdSpaceIsRefusedWithoutAWrite() {
        // At exhaustion the specification says to stop admitting flows and
        // replace the connection; there is no id to give this one.
        val streams = Streams(quicStream(), firstId = QuicFlowId.LAST_CLIENT_STREAM + 4)
        val opened = QuicCarrier(streams, key).openFlow(target, FlowKind.Tcp)
        assertTrue((opened as DecodeResult.Invalid).reason is QuicCarrierReason.StreamUnusable)
        assertEquals(
            0,
            streams.streams
                .single()
                .writtenBytes()
                .size,
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
    fun aUdpFlowIsRefusedWhenTheConnectionCarriesNoDatagrams() {
        // The specification puts UDP in DATAGRAMs and forbids carrier fallback,
        // "including for missing DATAGRAM support".
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams = null)
        val opened = carrier.openFlow(target, FlowKind.Udp)
        assertEquals(QuicCarrierReason.NoDatagrams, (opened as DecodeResult.Invalid).reason)
    }

    @Test
    fun aUdpFlowsOpeningWriteCarriesNoPayload() {
        // The payload does not ride the control stream: it goes as a DATAGRAM
        // once the Portal has answered. "Nonempty post-READY control data is
        // invalid for that Flow."
        val streams = Streams(quicStream(), quicStream(), firstId = 0)
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(streams, key, datagrams)
        carrier.openFlow(target, FlowKind.Tcp) // Stream 0, so the UDP flow lands on Stream 4
        val payload = "hello".encodeToByteArray()

        val opened = carrier.openFlow(target, FlowKind.Udp, firstPayload = payload)
        assertTrue("the flow should open: ${opened.reasonOrNull()?.detail}", opened is DecodeResult.Ok)
        assertEquals("Stream 4 derives flow id 2", 2u, opened.valueOrNull()!!.id)

        val written = streams.streams[1].writtenBytes()
        assertEquals(FlowKind.Udp, FlowStart.decode(written[0]).kind)
        val decodedTarget = Target.decode(written, offset = 1)
        assertEquals("the control stream carried payload", 1 + (decodedTarget as DecodeResult.Ok).value.consumed, written.size)

        assertEquals("the first packet did not go as a datagram", 1, datagrams.sent.size)
        assertArrayEquals(
            "the datagram is not a DATA frame for this flow",
            QuicDatagram.Data(2u, payload).encode(),
            datagrams.sent.single(),
        )
    }

    @Test
    fun aPacketTooLargeForOneDatagramIsFragmentedWithOnePacketId() {
        val datagrams = FakeDatagrams(max = 100)
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value as PacketFlow

        flow.sendPacket(ByteArray(250) { it.toByte() })

        assertTrue("a large packet was not fragmented", datagrams.sent.size > 1)
        val decoded =
            datagrams.sent.map { (DatagramFrame.decode(it) as DecodeResult.Ok).value as DatagramFrame.Fragment }
        assertEquals("fragments of one packet must share a packet id", 1, decoded.map { it.packetId }.toSet().size)
        assertEquals("every fragment declares the same count", 1, decoded.map { it.count }.toSet().size)
        assertEquals("the indices are not 0..count-1", decoded.indices.toList(), decoded.map { it.index })
        assertEquals("the declared total length is wrong", 250, decoded.first().totalLength)
        assertTrue("no fragment may exceed the datagram limit", datagrams.sent.all { it.size <= 100 })
    }

    @Test
    fun closingAUdpFlowTellsTheFarEnd() {
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value

        flow.close()

        assertArrayEquals(
            "closing a UDP flow must send a CLOSE frame",
            QuicDatagram.Close(flow.id).encode(),
            datagrams.sent.last(),
        )
    }

    @Test
    fun aPacketForThisFlowIsDelivered() {
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value as PacketFlow

        datagrams.incoming += QuicDatagram.Data(flow.id, "pong".encodeToByteArray()).encode()
        assertEquals("pong", String(flow.receivePacket(1_000)!!))
    }

    @Test
    fun aPacketForAnotherFlowIsNotDeliveredToThisOne() {
        // One connection's datagrams carry every flow, and the demultiplexer
        // is what keeps them apart. An id with no READY flow is dropped.
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value as PacketFlow

        datagrams.incoming += QuicDatagram.Data(flow.id + 1u, "stray".encodeToByteArray()).encode()
        datagrams.incoming += byteArrayOf(1, 2, 3) // not a datagram at all
        datagrams.incoming += QuicDatagram.Data(flow.id, "mine".encodeToByteArray()).encode()
        assertEquals("mine", String(flow.receivePacket(1_000)!!))
    }

    @Test
    fun aFragmentedPacketIsReassembledBeforeDelivery() {
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value as PacketFlow
        val payload = ByteArray(300) { it.toByte() }

        (QuicDatagram.frames(flow.id, 7u, payload, 128) as DecodeResult.Ok).value.reversed().forEach {
            datagrams.incoming += it.encode()
        }
        assertArrayEquals(payload, flow.receivePacket(1_000))
    }

    @Test
    fun aCloseFromThePortalEndsReceiving() {
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value as PacketFlow

        datagrams.incoming += QuicDatagram.Close(flow.id).encode()
        assertEquals(null, flow.receivePacket(1_000))
    }

    @Test
    fun theByteApiOfAPacketFlowMovesWholePackets() {
        // write and read route to the packet methods rather than half-working:
        // a caller reaching for `write` would otherwise frame a packet twice.
        val datagrams = FakeDatagrams()
        val carrier = QuicCarrier(Streams(quicStream()), key, datagrams)
        val flow = (carrier.openFlow(target, FlowKind.Udp) as DecodeResult.Ok).value

        flow.write("ping".encodeToByteArray())
        flow.flush()
        assertArrayEquals(QuicDatagram.Data(flow.id, "ping".encodeToByteArray()).encode(), datagrams.sent.last())

        datagrams.incoming += QuicDatagram.Data(flow.id, "pong".encodeToByteArray()).encode()
        val buffer = ByteArray(16)
        val read = flow.read(buffer, 0, buffer.size)
        assertEquals("pong", String(buffer, 0, read))
        assertTrue(flow.isOpen)
        assertEquals(FlowKind.Udp, flow.kind)
    }

    @Test
    fun aTcpFlowReadsAndWritesItsStream() {
        val streams = Streams(quicStream(peer = byteArrayOf(0) + "reply".encodeToByteArray()))
        val flow = (QuicCarrier(streams, key).openFlow(target, FlowKind.Tcp) as DecodeResult.Ok).value
        flow.write("ask".encodeToByteArray())
        flow.flush()
        val buffer = ByteArray(16)
        assertEquals("reply", String(buffer, 0, flow.read(buffer, 0, buffer.size)))
        assertTrue(flow.isOpen)
        flow.close()
        assertTrue(!flow.isOpen)
    }

    @Test
    fun aRebuiltConnectionAuthenticatesAgain() {
        // A rebuilt connection starts again at Stream 0, and Stream 0 is where
        // the AuthFrame goes. A carrier that remembered having authenticated
        // would send that first flow bare, and a Portal answers a bare flow
        // with silence: a tunnel that survives a network change and then
        // carries nothing.
        val oldConnection = Streams(quicStream(), quicStream())
        val rebuilt = Streams(quicStream())
        var current = oldConnection
        val carrier = QuicCarrier({ current.open() }, key)

        carrier.openFlow(target, FlowKind.Tcp)
        carrier.openFlow(target, FlowKind.Tcp)
        current = rebuilt
        carrier.openFlow(target, FlowKind.Tcp)

        val stream = rebuilt.streams.single()
        assertEquals(
            "the first flow on a rebuilt connection must carry an AuthFrame",
            Authentication.encodeFrame(key, AuthTransport.Quic, stream.exporter).toHex(),
            stream.writtenBytes().copyOf(Authentication.FRAME_LENGTH).toHex(),
        )
    }

    @Test
    fun aClosedCarrierOpensNothing() {
        val carrier = QuicCarrier(Streams(quicStream()), key)
        carrier.close()
        val opened = carrier.openFlow(target, FlowKind.Tcp)
        assertEquals(QuicCarrierReason.CarrierClosed, (opened as DecodeResult.Invalid).reason)
    }
}
