// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A quiet connection stays open, and the interval it stays open with is derived.
 *
 * ## Why the arithmetic is not enough
 *
 * `KeepAliveTest` proves the number is inside the timeout. It cannot prove the
 * PING is actually sent, that ngtcp2 was told, or that the Portal counts it as
 * activity — which is three assumptions between a correct interval and a tunnel
 * that survives being idle.
 *
 * Run against a Portal with `NOW_UDP_IDLE_TIMEOUT=20s`. The default is two
 * minutes, and a case that waited that out would spend most of a suite's time
 * asleep to prove one thing.
 */
@RunWith(AndroidJUnit4::class)
class QuicKeepAliveTest {
    @Test
    fun theIntervalIsDerivedFromWhatTheTwoEndsNegotiated() {
        withConnection { connection ->
            val idle = connection.idleTimeoutNanos().nanoseconds
            assertTrue("no idle timeout was negotiated at all", idle.inWholeSeconds > 0)

            val interval = connection.keepAliveInterval
            assertNotNull("no keep-alive was set after the handshake", interval)
            assertTrue(
                "a $interval keep-alive does not stay inside a $idle timeout",
                KeepAlive.staysInside(interval!!, idle),
            )
        }
    }

    /**
     * The claim itself: an idle **authenticated** connection outlives the
     * timeout and still opens a flow.
     *
     * The word that had to be added is "authenticated", and the first version
     * of this case did not have it. It opened a connection, slept, and then
     * asked for a flow — and the Portal had already closed it, with
     * `authentication deadline elapsed` in its log. **A QUIC connection carries
     * its AuthFrame on its first flow**, so a connection that has not opened
     * one has not authenticated, and upstream's five-second handshake deadline
     * ends it long before any idle timeout is in question. Keep-alive is not
     * what keeps an unauthenticated connection alive; nothing is.
     *
     * The flow at the end is what makes this a measurement rather than a wait.
     * A connection that had quietly died would look identical from here — QUIC
     * does not announce an idle close — and only asking it to do something
     * distinguishes the two.
     */
    @Test
    fun anIdleAuthenticatedConnectionOutlivesTheIdleTimeoutAndStillOpensAFlow() {
        withConnection { connection ->
            val idle = connection.idleTimeoutNanos().nanoseconds
            val session =
                NowhereSession(
                    sharedKey = key(),
                    connect = { error("a QUIC session must not dial TLS") },
                    quicStreams =
                        QuicCarrier.StreamFactory {
                            QuicStreamTransport(connection, connection.openStream())
                        },
                    quicDatagrams = connection,
                )
            session.use {
                val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), ORIGIN_PORT) as DecodeResult.Ok).value

                // Authenticate, by doing the only thing that authenticates.
                val first = session.openFlow(target, FlowKind.Tcp)
                assertTrue("the session never authenticated: ${first.reasonOrNull()?.detail}", first is DecodeResult.Ok)
                (first as DecodeResult.Ok).value.close()

                Thread.sleep(IDLE_WAIT_MILLIS)

                val second = session.openFlow(target, FlowKind.Tcp)
                assertTrue(
                    "the connection did not survive ${IDLE_WAIT_MILLIS}ms of quiet " +
                        "(idle timeout $idle, keep-alive ${connection.keepAliveInterval}): " +
                        "${second.reasonOrNull()?.detail}",
                    second is DecodeResult.Ok,
                )
                (second as DecodeResult.Ok).value.close()
            }
        }
    }

    private fun withConnection(body: (QuicConnection) -> Unit) {
        val portal = E2eEnvironment.requirePortal()
        QuicConnection
            .open(
                remote = InetSocketAddress(portal.substringBeforeLast(':'), portal.substringAfterLast(':').toInt()),
                alpn = "now/1",
                serverName = null,
                protect = { true },
            ).use { connection ->
                connection.completeHandshake()
                body(connection)
            }
    }

    private fun key() =
        (
            eu.nodepass.somewhere.protocol.auth.SharedKey
                .of(E2eEnvironment.sharedKey) as DecodeResult.Ok
        ).value

    private companion object {
        const val ORIGIN_PORT = 28091

        /**
         * Past upstream's five-second handshake deadline and past any
         * plausible per-connection idle, without making a suite wait out the
         * two-minute default for the sake of one claim.
         */
        const val IDLE_WAIT_MILLIS = 45_000L
    }
}
