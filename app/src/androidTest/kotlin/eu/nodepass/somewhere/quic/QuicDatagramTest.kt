// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.PacketFlow
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * UDP rides QUIC DATAGRAMs, against a live Portal.
 *
 * ## What is new here, and what was already true
 *
 * Section 9's arithmetic — the frame headers, the fragment plan, the
 * reassembler's bounds — has been checked against the specification's own
 * fixture since before there was a connection to use it on. What was missing
 * was the transport: nothing had ever put one of those frames on a wire.
 *
 * Two things only become real here. `maxDatagram` is a runtime figure ngtcp2
 * reports for the current path rather than a parameter a test chooses, so the
 * fragment plan is now made against a number the network decided. And the
 * echo comes back from a service the **Portal** can reach and this device
 * cannot — 127.0.0.1 is the Portal's loopback, not the device's — so a packet
 * that returns has crossed the carrier and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class QuicDatagramTest {
    @Test
    fun aPacketThatFitsMakesTheRoundTrip() {
        withUdpFlow { flow ->
            val sent = Random(7).nextBytes(512)
            flow.sendPacket(sent)
            val back = flow.receivePacket(WAIT_MILLIS)
            assertNotNull("nothing came back", back)
            assertArrayEquals("the echo differs from what was sent", sent, back)
        }
    }

    /**
     * A packet too large for one datagram is fragmented and reassembled.
     *
     * The size is derived from what the connection actually reports rather than
     * being a constant: a constant that happened to fit would exercise the
     * unfragmented path and pass, which is the failure this case exists to
     * avoid.
     */
    @Test
    fun aPacketTooLargeForOneDatagramIsFragmentedAndComesBackWhole() {
        withUdpFlow { flow, connection ->
            val maxDatagram = connection.maxDatagramSize()
            assertTrue("the peer enabled no datagram extension", maxDatagram > 0)

            val sent = Random(11).nextBytes(maxDatagram * 3)
            flow.sendPacket(sent)
            val back = flow.receivePacket(WAIT_MILLIS)
            assertNotNull("the reassembled packet never arrived", back)
            assertArrayEquals("reassembly did not reproduce the packet", sent, back)
        }
    }

    @Test
    fun severalPacketsKeepTheirBoundaries() {
        // The property a stream carrier has to work for and a datagram carrier
        // has by construction: three packets are three packets, not a byte
        // stream that happens to have been written three times.
        withUdpFlow { flow ->
            val packets = (1..3).map { Random(it).nextBytes(64 * it) }
            packets.forEach { flow.sendPacket(it) }
            val received = packets.indices.map { flow.receivePacket(WAIT_MILLIS) }
            received.forEachIndexed { index, back ->
                assertNotNull("packet $index never arrived", back)
            }
            assertTrue(
                "the packets came back as different bytes",
                received.filterNotNull().toSet().size == received.size,
            )
        }
    }

    private fun withUdpFlow(body: (PacketFlow) -> Unit) = withUdpFlow { flow, _ -> body(flow) }

    private fun withUdpFlow(body: (PacketFlow, QuicConnection) -> Unit) {
        val portal = E2eEnvironment.requirePortal()
        val url =
            when (
                val parsed =
                    NowhereUrl.parse("nowhere://${E2eEnvironment.sharedKey}@$portal?up=udp&down=udp")
            ) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid -> throw AssertionError(parsed.reason.detail)
            }

        QuicConnection
            .open(
                remote = InetSocketAddress(portal.substringBeforeLast(':'), portal.substringAfterLast(':').toInt()),
                alpn = url.alpn,
                serverName = null,
                protect = { true },
            ).use { connection ->
                connection.completeHandshake()
                NowhereSession(
                    sharedKey = url.sharedKey,
                    connect = { error("a QUIC session must not dial TLS") },
                    quicStreams =
                        QuicCarrier.StreamFactory {
                            QuicStreamTransport(connection, connection.openStream())
                        },
                    quicDatagrams = connection,
                ).use { session ->
                    val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), ECHO_PORT) as DecodeResult.Ok).value
                    val opened = session.openFlow(target, FlowKind.Udp)
                    val flow =
                        (opened as? DecodeResult.Ok)?.value
                            ?: throw AssertionError("the UDP flow failed: ${opened.reasonOrNull()?.detail}")
                    assertTrue("a QUIC UDP flow must take packets", flow is PacketFlow)
                    flow.use { body(flow as PacketFlow, connection) }
                }
            }
    }

    private companion object {
        /**
         * A UDP echo on the **Portal's** loopback. Nothing listens on the
         * device's own, so a packet that comes back cannot have taken another
         * route.
         */
        const val ECHO_PORT = 28092
        const val WAIT_MILLIS = 10_000L
    }
}
