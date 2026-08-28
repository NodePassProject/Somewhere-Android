// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import eu.nodepass.somewhere.protocol.frame.FlowHeader
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRole
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.session.SplitCarrier
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/**
 * Split flows, and the three rejections that only they can provoke.
 *
 * ## What was unreachable before this
 *
 * `PAIR_TIMEOUT`, `METADATA_CONFLICT` and `SESSION_REPLACED` are three of the
 * seven `SetupResult` values, and the L1 coverage map has said "unreachable at
 * this layer" about them since it was written. They are unreachable because
 * each is a Portal's answer to something only two lanes can express: a half
 * that never arrived, two halves that disagree, and a session claimed twice.
 * Their *messages* have been tested since L1. Their *provocation* needs this.
 *
 * The Portal these run against is configured with a two-second pairing timeout
 * (`NOW_FLOW_PAIR_TIMEOUT=2s`); the default is fifteen, which is a long time to
 * hold a test suite waiting for a deadline to pass.
 */
@RunWith(AndroidJUnit4::class)
class QuicSplitFlowTest {
    @Test
    fun aSplitFlowCarriesPayloadWithTheUplinkOnQuic() {
        withSplitSession(up = NextHopCarrier.Udp, down = NextHopCarrier.Tcp) { session ->
            assertEquals("the echo did not survive the split flow", ECHO, roundTrip(session))
        }
    }

    @Test
    fun aSplitFlowCarriesPayloadWithTheUplinkOnTls() {
        withSplitSession(up = NextHopCarrier.Tcp, down = NextHopCarrier.Udp) { session ->
            assertEquals("the echo did not survive the split flow", ECHO, roundTrip(session))
        }
    }

    /**
     * `PAIR_TIMEOUT`: the matching half arrived too late.
     *
     * Provoked by sending OPEN, waiting past the Portal's pairing deadline, and
     * only then sending ATTACH. The result cannot be delivered before ATTACH
     * exists — the uplink is a lane the client writes and never reads — so the
     * rejection is what ATTACH receives rather than something OPEN is told.
     */
    @Test
    fun pairTimeoutIsProvokedByAnAttachThatArrivesTooLate() {
        assertEquals(
            SetupResult.PairTimeout,
            rejectionFrom { lanes ->
                lanes.sendOpen()
                Thread.sleep(PAIR_WAIT)
                lanes.sendAttach()
            },
        )
    }

    /** `METADATA_CONFLICT`: the two halves disagree about what the flow is. */
    @Test
    fun metadataConflictIsProvokedByHalvesThatDisagreeAboutKind() {
        assertEquals(
            SetupResult.MetadataConflict,
            rejectionFrom { lanes ->
                lanes.sendOpen(kind = FlowKind.Tcp)
                lanes.sendAttach(kind = FlowKind.Udp)
            },
        )
    }

    /**
     * `SESSION_REPLACED`: the session state this flow was waiting on is gone.
     *
     * The first shape tried was to replace the session and then open a fresh
     * flow on the older carrier. That does not work, and the reason is worth
     * keeping: **this Portal implements replacement by tearing the older
     * carrier down**, so the older connection is draining before it can ask
     * anything and the answer is `ERR_DRAINING` rather than a setup byte.
     *
     * The shape that does work is the one the specification describes: OPEN
     * arrives, the session it belongs to is replaced, and then ATTACH arrives
     * and is told what became of it. Section 6: *"An OPEN-side rejection is
     * retained long enough to return the same result when ATTACH arrives."*
     * The downlink here is a TLS lane, which outlives the QUIC carrier it was
     * paired with — that independence is what makes the byte observable at all.
     */
    @Test
    fun sessionReplacedIsReturnedToAnAttachWhoseSessionWasTakenOver() {
        val portal = E2eEnvironment.requirePortal()
        val url = node(portal, NextHopCarrier.Udp, NextHopCarrier.Tcp)
        val sessionId = SessionId.random()
        val dialer = NowhereDialer(protect = { true })

        connection(portal, url).use { first ->
            first.completeHandshake()

            // OPEN, on a carrier that is about to be replaced. The downlink is
            // a separate TLS connection and survives what happens to the QUIC
            // one.
            val up = QuicStreamTransport(first, first.openStream())
            val down = (dialer.connect(url) as DecodeResult.Ok).value
            val orphaned = Lanes(up, down, sessionId, flowId = 21u)
            orphaned.sendOpen()

            connection(portal, url).use { second ->
                second.completeHandshake()
                val takeoverUp = QuicStreamTransport(second, second.openStream())
                val takeoverDown = (dialer.connect(url) as DecodeResult.Ok).value
                val takeover = Lanes(takeoverUp, takeoverDown, sessionId, flowId = 22u)
                takeover.sendOpen()
                takeover.sendAttach()
                assertNotNull("the replacing session never authenticated", takeover.setupByte())

                // Now the orphaned half asks what became of its flow.
                orphaned.sendAttach()
                val setup = orphaned.setupByte()
                assertNotNull("the orphaned ATTACH was answered with silence", setup)
                assertEquals(
                    SetupResult.SessionReplaced,
                    (SetupResult.decode(setup!!) as DecodeResult.Ok).value,
                )
                runCatching { takeoverUp.close() }
                runCatching { takeoverDown.close() }
            }
            runCatching { up.close() }
            runCatching { down.close() }
        }
    }

    /**
     * Row 7: the result arrives on the downlink and nowhere else.
     *
     * Not an ordering detail. The uplink is a lane the client writes and never
     * reads, so a setup byte placed there would be a byte nobody collects — and
     * a client that read one would be reading the first byte of payload.
     */
    @Test
    fun onlyTheDownlinkReceivesTheResult() {
        val portal = E2eEnvironment.requirePortal()
        val url = node(portal, NextHopCarrier.Udp, NextHopCarrier.Tcp)
        connection(portal, url).use { quic ->
            quic.completeHandshake()
            val dialer = NowhereDialer(protect = { true })
            val up = QuicStreamTransport(quic, quic.openStream())
            val down = (dialer.connect(url) as DecodeResult.Ok).value
            val lanes = Lanes(up, down, SessionId.random(), flowId = 9u)
            try {
                lanes.sendOpen()
                lanes.sendAttach()
                val setup = lanes.setupByte()
                assertNotNull("the downlink was not answered", setup)
                assertEquals(SetupResult.Ready, (SetupResult.decode(setup!!) as DecodeResult.Ok).value)

                // The uplink carries the request onward and answers nothing.
                up.setReadTimeout(UPLINK_SILENCE_MILLIS)
                val buffer = ByteArray(1)
                val read = runCatching { up.read(buffer, 0, 1) }.getOrDefault(-1)
                assertTrue("the uplink answered $read byte(s); only the downlink may", read <= 0)
            } finally {
                runCatching { up.close() }
                runCatching { down.close() }
            }
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    /** The two lanes of one split flow, driven by hand. */
    private inner class Lanes(
        val up: Transport,
        val down: Transport,
        val sessionId: SessionId,
        val flowId: UInt,
    ) {
        fun sendOpen(kind: FlowKind = FlowKind.Tcp) {
            val header =
                (
                    FlowHeader.forClient(FlowRole.Open, kind, FlowCarrier.Quic, FlowCarrier.TlsTcp, flowId)
                        as DecodeResult.Ok
                ).value
            up.write(auth(up) + header.encode() + echoTarget().encode())
            up.flush()
        }

        fun sendAttach(kind: FlowKind = FlowKind.Tcp) {
            val header =
                (
                    FlowHeader.forClient(FlowRole.Attach, kind, FlowCarrier.Quic, FlowCarrier.TlsTcp, flowId)
                        as DecodeResult.Ok
                ).value
            down.write(auth(down) + header.encode())
            down.flush()
        }

        fun setupByte(): Byte? {
            val buffer = ByteArray(1)
            return try {
                if (down.read(buffer, 0, 1) == 1) buffer[0] else null
            } catch (_: java.io.IOException) {
                null
            }
        }

        private fun auth(transport: Transport): ByteArray =
            Authentication.encodeFrame(
                sharedKey = key(),
                transport =
                    if (transport.transportKind == eu.nodepass.somewhere.protocol.session.TransportKind.Quic) {
                        AuthTransport.Quic
                    } else {
                        AuthTransport.TlsTcp
                    },
                exporter = transport.exporter,
                sessionId = sessionId.toByteArray(),
            )
    }

    private fun rejectionFrom(drive: (Lanes) -> Unit): SetupResult {
        val portal = E2eEnvironment.requirePortal()
        val url = node(portal, NextHopCarrier.Udp, NextHopCarrier.Tcp)
        connection(portal, url).use { quic ->
            quic.completeHandshake()
            val dialer = NowhereDialer(protect = { true })
            val up = QuicStreamTransport(quic, quic.openStream())
            val down = (dialer.connect(url) as DecodeResult.Ok).value
            val lanes = Lanes(up, down, SessionId.random(), flowId = 1u)
            try {
                drive(lanes)
                val setup = lanes.setupByte() ?: throw AssertionError("the Portal answered nothing")
                return (SetupResult.decode(setup) as DecodeResult.Ok).value
            } finally {
                runCatching { up.close() }
                runCatching { down.close() }
            }
        }
    }

    private fun roundTrip(session: NowhereSession): String {
        val request = "GET /small.bin HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val opened = session.openFlow(echoTarget(), FlowKind.Tcp, firstPayload = request)
        val flow =
            (opened as? DecodeResult.Ok)?.value
                ?: throw AssertionError("the split flow failed: ${opened.reasonOrNull()?.detail}")
        flow.use {
            val buffer = ByteArray(4096)
            val read = flow.read(buffer)
            assertTrue("nothing came back on the downlink", read > 0)
            return String(buffer, 0, minOf(read, ECHO.length), Charsets.ISO_8859_1)
        }
    }

    private fun withSplitSession(
        up: NextHopCarrier,
        down: NextHopCarrier,
        body: (NowhereSession) -> Unit,
    ) {
        val portal = E2eEnvironment.requirePortal()
        val url = node(portal, up, down)
        connection(portal, url).use { quic ->
            quic.completeHandshake()
            val dialer = NowhereDialer(protect = { true })

            fun lane(carrier: NextHopCarrier): SplitCarrier.LaneFactory =
                when (carrier) {
                    NextHopCarrier.Udp -> SplitCarrier.LaneFactory { QuicStreamTransport(quic, quic.openStream()) }
                    NextHopCarrier.Tcp ->
                        SplitCarrier.LaneFactory { (dialer.connect(url) as DecodeResult.Ok).value }
                }
            NowhereSession(
                sharedKey = url.sharedKey,
                connect = { error("a split session dials through its lane factories") },
                splitUplink = lane(up),
                splitDownlink = lane(down),
            ).use(body)
        }
    }

    private fun connection(
        portal: String,
        url: NowhereUrl,
    ): QuicConnection =
        QuicConnection.open(
            remote = InetSocketAddress(portal.substringBeforeLast(':'), portal.substringAfterLast(':').toInt()),
            alpn = url.alpn,
            serverName = null,
            protect = { true },
        )

    private fun node(
        portal: String,
        up: NextHopCarrier,
        down: NextHopCarrier,
    ): NowhereUrl {
        val text = "nowhere://${E2eEnvironment.sharedKey}@$portal?up=${up.token}&down=${down.token}"
        return when (val parsed = NowhereUrl.parse(text)) {
            is DecodeResult.Ok -> parsed.value
            is DecodeResult.Invalid -> throw AssertionError(parsed.reason.detail)
        }
    }

    private fun key() =
        (
            eu.nodepass.somewhere.protocol.auth.SharedKey
                .of(E2eEnvironment.sharedKey) as DecodeResult.Ok
        ).value

    private fun echoTarget(): Target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), ORIGIN_PORT) as DecodeResult.Ok).value

    private companion object {
        const val ORIGIN_PORT = 28091

        /**
         * The origin answers HTTP/1.1 whatever the request says, so this is
         * the reply's own version rather than the one asked for.
         */
        const val ECHO = "HTTP/1.1 200"

        /** Past the Portal's `NOW_FLOW_PAIR_TIMEOUT=2s`, with room to spare. */
        const val PAIR_WAIT = 4_000L

        /** Long enough to mean silence, short enough not to stall a suite. */
        const val UPLINK_SILENCE_MILLIS = 2_000
    }
}
