// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.session.QuicCarrierReason
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session authenticates over QUIC against a live Portal, and the Portal says
 * so by answering a flow.
 *
 * ## Why the proof is a flow rather than the AuthFrame
 *
 * Authentication has **no response frame**, by design: a Portal that answered
 * differently on failure would be an oracle for active probing. A wrong tag
 * ends the connection without a word. So "the tag
 * verified" is not directly observable — the first observable consequence is
 * the Portal answering a flow at all, and that answer is the `SetupResult` byte.
 *
 * `READY` therefore proves the whole chain: Morph masked every datagram the
 * way the Portal unmasks it, the RFC 5705 exporter came out of the right
 * connection, the tag was computed over transport byte `0x02` with the Vector
 * role key, the FlowStart parsed, and the Target was dialled.
 */
@RunWith(AndroidJUnit4::class)
class QuicAuthenticationTest {
    @Test
    fun aFlowOverQuicIsAnsweredReady() {
        withCarrier { carrier ->
            val opened = carrier.openFlow(target(), FlowKind.Tcp)
            assertTrue(
                "the Portal did not answer READY: ${opened.reasonOrNull()?.detail}",
                opened is DecodeResult.Ok,
            )
            assertEquals(SetupResult.Ready, (opened as DecodeResult.Ok).value.setupResult)
        }
    }

    /**
     * The rule that makes this a *connection* rather than a lane: the second
     * flow rides a second stream with no AuthFrame in front of it, and the
     * Portal still answers.
     */
    @Test
    fun aSecondFlowOpensWithoutASecondAuthFrame() {
        withCarrier { carrier ->
            val first = carrier.openFlow(target(), FlowKind.Tcp)
            assertTrue("the first flow failed: ${first.reasonOrNull()?.detail}", first is DecodeResult.Ok)

            val second = carrier.openFlow(target(), FlowKind.Tcp)
            assertTrue(
                "a second flow without a second AuthFrame was refused: ${second.reasonOrNull()?.detail}",
                second is DecodeResult.Ok,
            )
            assertEquals(SetupResult.Ready, (second as DecodeResult.Ok).value.setupResult)
        }
    }

    /**
     * A wrong shared key reaches no answer at all.
     *
     * This is the control, and it is the half that makes READY mean something.
     * Without it, a Portal that answered everything would look identical to one
     * that verified the tag.
     *
     * Where it fails depends on Morph, and both are right. With Morph the mask
     * is keyed by the shared key, so the handshake itself is noise and never
     * completes. Without it the handshake completes, the AuthFrame is refused,
     * and the Portal closes the connection: no setup byte.
     */
    @Test
    fun aWrongSharedKeyIsNotAnswered() {
        val wrong = "ffffffffffffffffffffffffffffffff"
        if (E2eEnvironment.morph) {
            val failure = runCatching { withCarrier(sharedKey = wrong) { } }.exceptionOrNull()
            assertNotNull("a wrong key completed a Morph handshake", failure)
            return
        }
        withCarrier(sharedKey = wrong) { carrier ->
            val opened = carrier.openFlow(target(), FlowKind.Tcp)
            // Asserted on the reason rather than on its prose: the detail is
            // user-facing text that may be reworded.
            assertEquals(QuicCarrierReason.NoSetupByte, opened.reasonOrNull())
        }
    }

    private fun target(): Target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), ORIGIN_PORT) as DecodeResult.Ok).value

    private fun withCarrier(
        sharedKey: String = E2eEnvironment.sharedKey,
        body: (QuicCarrier) -> Unit,
    ) {
        val node = E2eEnvironment.node(net = "udp", key = sharedKey)
        val connection = E2eEnvironment.openQuic(node)
        connection.use {
            connection.completeHandshake()
            val carrier =
                QuicCarrier(
                    streams = { QuicStreamTransport(connection, connection.openStream()) },
                    sharedKey = node.sharedKey,
                )
            assertNotNull(carrier)
            carrier.use(body)
        }
    }

    private companion object {
        /**
         * The origin the Portal dials, as the **Portal** sees it. The Portal
         * runs beside the origin on the build host, so this is loopback there —
         * not the address the device would use, which is a different machine's
         * idea of the same service.
         */
        const val ORIGIN_PORT = 28091
    }
}
