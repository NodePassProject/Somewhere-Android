// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.session.TransportKind
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The refusals — every way a carrier says no rather than failing later.
 *
 * Each of these is a path a working client takes rarely and a broken one takes
 * immediately, so each is exactly the kind of code that is never executed until
 * it matters.
 */
class MuxCarrierRefusalTest {
    private val key = (SharedKey.of("refusal-key") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(10, 0, 0, 1), 443) as DecodeResult.Ok).value

    private var portal: FakeMuxPortal? = null
    private var carrier: MuxCarrier? = null

    @After
    fun tearDown() {
        runCatching { carrier?.close() }
        runCatching { portal?.stop() }
    }

    private fun connected(): Pair<MuxCarrier, FakeMuxPortal> {
        val (clientSide, portalSide) = LoopbackTransport.pair()
        val fake = FakeMuxPortal(portalSide, { SetupResult.Ready }).also { it.start() }
        val built = MuxCarrier(clientSide, key, SessionId.random())
        assertTrue(built.start() is DecodeResult.Ok)
        carrier = built
        portal = fake
        return built to fake
    }

    @Test
    fun aCarrierStartsOnceAndSaysSoIfAskedTwice() {
        val (built, _) = connected()
        val again = built.start()
        assertEquals(MuxCarrierReason.AlreadyStarted, (again as DecodeResult.Invalid).reason)
    }

    @Test
    fun aClosedTransportIsRefusedBeforeAnythingIsWritten() {
        val (clientSide, _) = LoopbackTransport.pair()
        clientSide.close()
        val built = MuxCarrier(clientSide, key, SessionId.random())
        assertEquals(MuxCarrierReason.TransportClosed, (built.start() as DecodeResult.Invalid).reason)
    }

    @Test
    fun muxNeverWrapsQuic() {
        // `docs/protocol.md` section 1. A carrier built over a QUIC transport
        // is a construction error rather than something to attempt: QUIC has
        // its own stream multiplexing and Mux frames would be a second layer of
        // it on top.
        val built = MuxCarrier(QuicShapedTransport(), key, SessionId.random())
        assertEquals(MuxCarrierReason.QuicCannotCarryMux, (built.start() as DecodeResult.Invalid).reason)
    }

    @Test
    fun aTransportThatFailsOnTheOpeningWriteIsReported() {
        val built = MuxCarrier(FailingTransport(), key, SessionId.random())
        val reason = (built.start() as DecodeResult.Invalid).reason
        assertTrue("expected a transport failure, got $reason", reason is MuxCarrierReason.TransportFailed)
    }

    @Test
    fun aFlowIdAlreadyOpenOnThisCarrierIsRefused() {
        val (built, _) = connected()
        assertTrue(built.open(target, FlowKind.Tcp, 1u) is DecodeResult.Ok)
        val again = built.open(target, FlowKind.Tcp, 1u)
        val reason = (again as DecodeResult.Invalid).reason
        assertTrue("expected the id to be in use, got $reason", reason is MuxCarrierReason.FlowIdInUse)
        assertEquals("and the second attempt must not have taken a slot", 1, built.activeFlowCount)
    }

    @Test
    fun writingToAHalfClosedFlowIsRefusedRatherThanIgnored() {
        val (built, _) = connected()
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        flow.close()
        val failure = runCatching { flow.write("too late".toByteArray()) }.exceptionOrNull()
        assertTrue("a write after FIN must fail, not vanish", failure is IOException)
    }

    @Test
    fun aPortalThatSaysNothingIsReportedAsSuch() {
        // The Mux equivalent of a dedicated lane's silent Portal: the carrier
        // came up, the SYN went out, and no setup byte ever came back.
        val (clientSide, portalSide) = LoopbackTransport.pair()
        // A peer that reads and never answers.
        val silent =
            Thread {
                val sink = ByteArray(4096)
                runCatching { while (portalSide.read(sink) >= 0) Unit }
            }.apply {
                isDaemon = true
                start()
            }

        val built = MuxCarrier(clientSide, key, SessionId.random())
        carrier = built
        assertTrue(built.start() is DecodeResult.Ok)
        built.close()
        silent.interrupt()

        val reason = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Invalid).reason
        assertEquals(MuxCarrierReason.ClosedByCaller, reason)
    }

    @Test
    fun aRejectedFlowIsReleasedRatherThanReset() {
        // The rejection is the teardown. The Portal answers and forgets the
        // stream in the same breath, and section 3 makes a frame for a flow it
        // does not know a *carrier* error — so a RST here does not tidy up one
        // flow, it closes the connection and fails every other flow sharing it.
        //
        // Found on a device and not by any test that opens one flow at a time.
        // Android's Private DNS probes port 853 the moment a tunnel comes up,
        // the Portal answered DIAL_FAILED, this client reset the stream, and
        // half a second later four unrelated fetches on the same carrier
        // reported "the Portal closed the Mux carrier". One to four of sixteen
        // concurrent fetches came back empty, intermittently, depending on
        // which shard the probe happened to land on.
        val (clientSide, portalSide) = LoopbackTransport.pair()
        val fake = FakeMuxPortal(portalSide, { SetupResult.DialFailed }).also { it.start() }
        val built = MuxCarrier(clientSide, key, SessionId.random())
        assertTrue(built.start() is DecodeResult.Ok)
        carrier = built
        portal = fake

        val refused = built.open(target, FlowKind.Tcp, 1u)
        assertEquals(
            MuxCarrierReason.Rejected(SetupResult.DialFailed),
            (refused as DecodeResult.Invalid).reason,
        )

        // Give the writer thread a chance to send anything it was going to.
        Thread.sleep(300)
        val reset = fake.frames().filter { it.isReset }
        assertTrue("a rejected flow must not be reset, but $reset went out", reset.isEmpty())

        // And the carrier is still usable, which is the point of not resetting.
        assertTrue("the carrier died with the flow it refused", built.isOpen)
    }

    @Test
    fun everyRefusalSaysSomethingDifferent() {
        // The seven rejections lesson, applied here: a caller that cannot tell
        // "the carrier is full" from "the Portal refused you" cannot act on
        // either.
        val reasons =
            listOf(
                MuxCarrierReason.AlreadyStarted,
                MuxCarrierReason.TransportClosed,
                MuxCarrierReason.QuicCannotCarryMux,
                MuxCarrierReason.ClosedByCaller,
                MuxCarrierReason.PeerClosed,
                MuxCarrierReason.NoSetupByte,
                MuxCarrierReason.TransportFailed("SocketException"),
                MuxCarrierReason.StreamLimit(256),
                MuxCarrierReason.FlowIdInUse(4u),
                MuxCarrierReason.StreamReset(4u),
                MuxCarrierReason.PeerOpenedAStream(4u),
                MuxCarrierReason.Rejected(SetupResult.DialFailed),
                MuxShardReason.SetClosed,
            )
        val details = reasons.map { it.detail }
        assertEquals("every reason must read differently", details.size, details.distinct().size)
        details.forEach {
            assertTrue("a reason must be a sentence, not a symbol: '$it'", it.length > 12 && ' ' in it)
        }
    }

    /** A transport that claims to be QUIC, to reach the one construction error. */
    private class QuicShapedTransport : Transport {
        override val exporter = ByteArray(32)
        override val transportKind = TransportKind.Quic
        override val isOpen = true

        override fun write(bytes: ByteArray) = Unit

        override fun flush() = Unit

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int = -1

        override fun close() = Unit
    }

    /** A transport whose first write fails, as a reset connection does. */
    private class FailingTransport : Transport {
        override val exporter = ByteArray(32)
        override val transportKind = TransportKind.TlsTcp
        override val isOpen = true

        override fun write(bytes: ByteArray): Unit = throw IOException("simulated reset")

        override fun flush() = Unit

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int = -1

        override fun close() = Unit
    }
}
