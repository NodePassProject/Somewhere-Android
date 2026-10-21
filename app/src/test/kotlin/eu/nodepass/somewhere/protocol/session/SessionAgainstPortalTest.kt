// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.net.DialReason
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The whole L1 path, against a live Portal and a live target.
 *
 * This is the test that says the client works: a real TLS handshake, a real
 * exporter, a real AuthFrame, a real flow, and bytes that actually reach a
 * service on the other side and come back. Everything below it has been unit
 * tested against fixtures and fakes; this is the first thing that can fail for a
 * reason no fixture predicted.
 *
 * It is also NW-Q-04's first half: the Rust implementation is the oracle, and the
 * Portal accepting our bytes is the comparison.
 *
 * Requires a Portal (`conformance/scripts/portal-for-tests.sh`). Skipped without
 * one, so an environment that cannot run it reports honestly.
 */
class SessionAgainstPortalTest {
    private val portalEnv: String? = System.getenv("NOWHERE_E2E_PORTAL")
    private val keyText: String = System.getenv("NOWHERE_E2E_KEY") ?: "0123456789abcdef0123456789abcdef"

    private fun portal(): Pair<String, Int> {
        assumeTrue(
            "NOWHERE_E2E_PORTAL is not set — start one with conformance/scripts/portal-for-tests.sh",
            portalEnv != null && System.getenv("NOWHERE_E2E_PIN") != null,
        )
        val (host, port) = portalEnv!!.split(":")
        return host to port.toInt()
    }

    private val pin: String? = System.getenv("NOWHERE_E2E_PIN")

    /** A node for the test Portal: TLS, Morph on, pinned to the certificate it logged. */
    private fun node(
        host: String,
        port: Int,
        key: String = keyText,
    ): NowhereUrl = (NowhereUrl.parse("nowhere://$key@$host:$port?pin=$pin") as DecodeResult.Ok).value

    /** Connects as the real client does, through the real dialer. */
    private fun session(
        host: String,
        port: Int,
    ): NowhereSession {
        val node = node(host, port)
        return NowhereSession(node.sharedKey, {
            when (val transport = NowhereDialer().connect(node)) {
                is DecodeResult.Ok -> transport.value
                is DecodeResult.Invalid -> error(transport.reason.detail)
            }
        })
    }

    /** A one-shot echo server, so there is something real on the far side. */
    private fun echoServer(): Pair<Int, Thread> {
        val server = ServerSocket(0)
        val worker =
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val buffer = ByteArray(4096)
                        val count = client.getInputStream().read(buffer)
                        if (count > 0) {
                            client.getOutputStream().write(buffer, 0, count)
                            client.getOutputStream().flush()
                        }
                    }
                }
                server.close()
            }
        return server.localPort to worker
    }

    @Test
    fun aFlowCarriesBytesToARealTargetAndBack() {
        val (host, port) = portal()
        val (echoPort, _) = echoServer()
        val target =
            (
                Target.ofIpv4(byteArrayOf(127, 0, 0, 1), echoPort) as DecodeResult.Ok
            ).value

        session(host, port).use { session ->
            val opened = session.openFlow(target)
            val flow =
                (opened as? DecodeResult.Ok)?.value
                    ?: error("openFlow failed: ${opened.reasonOrNull()?.detail}")

            assertEquals("the Portal accepted the flow", SetupResult.Ready, flow.setupResult)

            val payload = "somewhere round trip".encodeToByteArray()
            flow.write(payload)
            flow.flush()

            val received = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (received.size() < payload.size) {
                val count = flow.read(buffer)
                if (count <= 0) break
                received.write(buffer, 0, count)
            }
            assertEquals(
                "the payload must survive the round trip byte for byte",
                String(payload),
                String(received.toByteArray()),
            )
            flow.close()
        }
    }

    @Test
    fun theFirstPayloadReachesTheTarget() {
        // A payload in hand when the flow opens, proven end to end rather than
        // by inspecting what was sent: the echo server only ever sees bytes
        // that made it through the Portal. This is the case that found the
        // payload riding the opening write, which a v3 Portal refuses.
        val (host, port) = portal()
        val (echoPort, _) = echoServer()
        val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), echoPort) as DecodeResult.Ok).value
        val payload = "carried in the opening write".encodeToByteArray()

        session(host, port).use { session ->
            val flow = (session.openFlow(target, firstPayload = payload) as DecodeResult.Ok).value
            val buffer = ByteArray(1024)
            val received = ByteArrayOutputStream()
            while (received.size() < payload.size) {
                val count = flow.read(buffer)
                if (count <= 0) break
                received.write(buffer, 0, count)
            }
            assertEquals(String(payload), String(received.toByteArray()))
            flow.close()
        }
    }

    @Test
    fun aWrongKeyFailsBeforeAnythingIsAuthenticated() {
        // The failure mode that matters, and the one only a live Portal reveals.
        //
        // v1 met a rejected AuthFrame with silence, and the read timeout was the
        // only thing between a wrong key and a hang. v3 keys Morph with the same
        // shared key, so a wrong key masks TLS with the wrong keystream: the
        // Portal reads the ClientHello as noise and the handshake never
        // completes. Nothing about the AuthFrame is ever tested — the failure
        // is earlier, and it is the dialer's.
        val (host, port) = portal()
        val wrong = node(host, port, key = "ffffffffffffffffffffffffffffffff")
        val result = NowhereDialer().connect(wrong)
        assertEquals(DialReason.HandshakeFailed, result.reasonOrNull())
    }

    @Test
    fun anUnreachableTargetIsReportedAsDialFailedNotAsSuccess() {
        // Port 9 with nothing on it. The Portal answers DIAL_FAILED, and the
        // distinction from every other rejection has to survive all the way up.
        val (host, port) = portal()
        val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 9) as DecodeResult.Ok).value

        session(host, port).use { session ->
            val result = session.openFlow(target)
            val reason = result.reasonOrNull()
            assertTrue("expected a rejection, got ${reason?.detail}", reason is LaneReason.Rejected)
            assertEquals(SetupResult.DialFailed, (reason as LaneReason.Rejected).result)
        }
    }

    @Test
    fun concurrentFlowsInOneSessionEachGetTheirOwnConnection() {
        val (host, port) = portal()
        val (echoPort, _) = echoServer()
        val (secondEchoPort, _) = echoServer()

        session(host, port).use { session ->
            val first =
                (
                    session.openFlow(
                        (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), echoPort) as DecodeResult.Ok).value,
                    ) as DecodeResult.Ok
                ).value
            val second =
                (
                    session.openFlow(
                        (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), secondEchoPort) as DecodeResult.Ok).value,
                    ) as DecodeResult.Ok
                ).value

            assertNotEquals("flows must not share an id", first.id, second.id)
            assertEquals(2, session.liveFlowCount)
            first.close()
            second.close()
            assertEquals(0, session.liveFlowCount)
        }
    }
}
