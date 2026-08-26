// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.mux

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Flow control on a live carrier. NW-P-15, NW-P-16.
 *
 * The unit tests prove one window's arithmetic. These prove the two windows
 * are wired to the frames — which is the part that is wrong in practice, since
 * a perfectly correct window that nothing consults produces a client that
 * ignores back-pressure and a Portal that stops reading.
 */
class MuxCarrierCreditTest {
    private val key = (SharedKey.of("mux-credit-key") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(10, 0, 0, 1), 443) as DecodeResult.Ok).value

    private var carrier: MuxCarrier? = null
    private var portal: FakeMuxPortal? = null

    @After
    fun tearDown() {
        runCatching { carrier?.close() }
        runCatching { portal?.stop() }
    }

    private fun connect(
        onReady: (FakeMuxPortal) -> Unit = {},
        onPayload: (FakeMuxPortal, UInt, ByteArray) -> Unit = { p, id, bytes -> p.sendStream(id, bytes) },
    ): Pair<MuxCarrier, FakeMuxPortal> {
        val (clientSide, portalSide) = LoopbackTransport.pair()
        val fake = FakeMuxPortal(portalSide, { SetupResult.Ready }, onReady, onPayload).also { it.start() }
        val built = MuxCarrier(clientSide, key, SessionId.random())
        assertTrue(built.start() is DecodeResult.Ok)
        carrier = built
        portal = fake
        return built to fake
    }

    private fun await(
        what: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun noStreamFrameCarriesMoreThanThirtyTwoKilobytes() {
        // NW-P-16. A single write far larger than the frame maximum must come
        // out as several frames, none over the limit, and reassemble exactly.
        val (built, fake) = connect(onPayload = { _, _, _ -> })
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value

        val payload = ByteArray(200_000) { (it * 31 + 7).toByte() }
        flow.write(payload)
        await("the Portal to receive it all") { (fake.payloadByFlow[1u]?.size ?: 0) >= payload.size }

        val frames = fake.frames().filter { it.kind == MuxKind.Stream && it.flowId == 1u }
        assertTrue("a 200 KB write must not be one frame", frames.size > 1)
        frames.forEach {
            assertTrue("a frame carried ${it.value} bytes", it.value <= MuxHeader.MAX_STREAM_PAYLOAD)
        }
        assertEquals(
            "the payload must reassemble byte for byte",
            digest(payload),
            digest(fake.payloadByFlow[1u]!!),
        )
    }

    @Test
    fun onlyTheOpeningFrameCarriesSyn() {
        val (built, fake) = connect(onPayload = { _, _, _ -> })
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        flow.write(ByteArray(100_000))
        await("every frame") { (fake.payloadByFlow[1u]?.size ?: 0) >= 100_000 }

        val syns = fake.frames().count { it.kind == MuxKind.Stream && it.flowId == 1u && it.isSyn }
        assertEquals("SYN creates the stream once and once only", 1, syns)
    }

    @Test
    fun aWindowWithFlowZeroReplenishesTheConnection() {
        // The one frame whose flow id is meaningfully zero, and the reason
        // MuxHeader.isConnectionLevel exists.
        val (built, fake) = connect(onPayload = { _, _, _ -> })
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value

        // More than either window, so the write cannot finish until credit is
        // returned. Sized rather than counted exactly, because the opening
        // frame has already spent a little of both on the FlowHeader and the
        // Target — an exact figure here would be this test knowing the wire
        // format, which is what the codec's own tests are for.
        val done = AtomicBoolean(false)
        thread {
            flow.write(ByteArray(MuxHeader.DEFAULT_CONNECTION_CREDIT + 50_000))
            done.set(true)
        }

        await("the peer to take about a window's worth") {
            (fake.payloadByFlow[1u]?.size ?: 0) >= MuxHeader.DEFAULT_CONNECTION_CREDIT - 1_000
        }
        Thread.sleep(200)
        assertTrue("a write past both windows must not complete", !done.get())

        // Comfortably more than what is left to write: the opening frame
        // already spent a little of both windows on the FlowHeader and Target,
        // so returning exactly the shortfall would leave it a few bytes short.
        fake.sendWindow(1u, 60_000)
        Thread.sleep(300)
        assertTrue("stream credit alone is not enough; the connection window is empty too", !done.get())

        fake.sendWindow(0u, 60_000)
        await("the write to complete once flow 0 replenishes the connection") { done.get() }
    }

    @Test
    fun creditBeyondTheWindowClosesTheCarrier() {
        // One byte is enough: nothing has been spent, so the window is already
        // full and any return at all is more than was ever advertised. The
        // first version sent a whole window's worth, which does not fit the u16
        // `value` field — it arrived truncated to zero and was rejected as a
        // zero-credit WINDOW instead, which is a different rule.
        val (built, _) = connect(onReady = { it.sendWindow(0u, 1) })
        await("the carrier to close") { !built.isOpen }
        val reason = (built.open(target, FlowKind.Tcp, 9u) as DecodeResult.Invalid).reason
        assertTrue("expected over-credit, got $reason", reason is MuxReason.CreditExceedsWindow)
    }

    @Test
    fun aLateWindowForAClosedStreamIsIgnored() {
        // Ordinary rather than exceptional: a WINDOW that crossed a FIN in
        // flight. The specification says ignore, and closing the carrier here
        // would kill every other flow on it for a frame that means nothing.
        val (built, fake) = connect()
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        flow.close()
        await("the slot to be released") { built.activeFlowCount == 0 }

        repeat(5) { fake.sendWindow(1u, 1_000) }
        Thread.sleep(300)
        assertTrue("the carrier must survive a late window", built.isOpen)
        assertTrue("and must still open new flows", built.open(target, FlowKind.Tcp, 2u) is DecodeResult.Ok)
    }

    @Test
    fun thisSideReturnsCreditAsTheApplicationReads() {
        // The mirror image. If this client never sends WINDOW, the Portal stops
        // after one window and the transfer stalls a long way from the end —
        // which looks exactly like a network that died mid-download.
        val total = MuxHeader.DEFAULT_STREAM_CREDIT + 100_000
        val (built, fake) =
            connect(
                onPayload = { portal, id, _ ->
                    // One big response, in frame-sized pieces.
                    var sent = 0
                    while (sent < total) {
                        val take = minOf(MuxHeader.MAX_STREAM_PAYLOAD, total - sent)
                        portal.sendStream(id, ByteArray(take) { (it and 0xFF).toByte() })
                        sent += take
                    }
                },
            )
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value
        flow.write(byteArrayOf(1))

        var read = 0
        val buffer = ByteArray(64 * 1024)
        val deadline = System.currentTimeMillis() + 20_000
        while (read < total && System.currentTimeMillis() < deadline) {
            val count = flow.read(buffer)
            if (count < 0) break
            read += count
        }
        assertEquals("the whole response has to arrive", total, read)

        val windows = fake.frames().count { it.kind == MuxKind.Window }
        assertTrue("credit must have been returned, or this could not have finished", windows > 0)
        assertTrue(
            "connection-level credit must be returned too, not only per stream",
            fake.frames().any { it.kind == MuxKind.Window && it.flowId == 0u },
        )
    }

    @Test
    fun manyFlowsShareTheConnectionWindowWithoutLosingBytes() {
        // Eight streams at once, each moving more than a frame. The connection
        // window is the contended resource; every stream's bytes must still
        // arrive complete and in order.
        val (built, fake) = connect(onPayload = { _, _, _ -> })
        val payloads = (1..8).associate { index -> index.toUInt() to ByteArray(60_000) { (it + index).toByte() } }

        val writers =
            payloads.map { (id, payload) ->
                thread {
                    val flow = (built.open(target, FlowKind.Tcp, id) as DecodeResult.Ok).value
                    flow.write(payload)
                }
            }
        writers.forEach { it.join(20_000) }

        payloads.forEach { (id, payload) ->
            await("flow $id to arrive complete") { (fake.payloadByFlow[id]?.size ?: 0) >= payload.size }
            assertEquals("flow $id came back changed", digest(payload), digest(fake.payloadByFlow[id]!!))
        }
    }

    @Test
    fun aSlowPeerSlowsTheWriterRatherThanGrowingAQueue() {
        // The queue is bounded at 512 slots. A peer that stops reading must
        // make the writer wait, not make this side buffer without limit —
        // "dropped rather than queued without bound" is for datagrams; stream
        // bytes may not be dropped at all, so back-pressure is the only answer.
        val (built, fake) = connect(onPayload = { _, _, _ -> })
        val flow = (built.open(target, FlowKind.Tcp, 1u) as DecodeResult.Ok).value

        val finished = AtomicBoolean(false)
        val writer =
            thread {
                // Far more than either window: this cannot complete until the
                // peer returns credit, and the peer never will.
                runCatching { flow.write(ByteArray(MuxHeader.DEFAULT_CONNECTION_CREDIT * 3)) }
                finished.set(true)
            }
        Thread.sleep(500)
        assertTrue("a write past the window must not complete", !finished.get())
        assertTrue(
            "and no more than a window may have reached the peer",
            (fake.payloadByFlow[1u]?.size ?: 0) <= MuxHeader.DEFAULT_CONNECTION_CREDIT,
        )

        built.close()
        writer.join(5_000)
        assertTrue("closing the carrier must release the blocked writer", finished.get())
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
