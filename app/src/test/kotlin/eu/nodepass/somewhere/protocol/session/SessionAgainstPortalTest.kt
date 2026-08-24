// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.tls.ConscryptExporter
import eu.nodepass.somewhere.protocol.tls.TlsTransport
import org.conscrypt.Conscrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
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
    private val keyText: String = System.getenv("NOWHERE_E2E_KEY") ?: "conformance-smoke-key"

    private fun portal(): Pair<String, Int> {
        assumeTrue(
            "NOWHERE_E2E_PORTAL is not set — start one with conformance/scripts/portal-for-tests.sh",
            portalEnv != null,
        )
        val (host, port) = portalEnv!!.split(":")
        return host to port.toInt()
    }

    private object TrustEverything : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Connects and handshakes, as the real client will. */
    private fun tlsSocket(
        host: String,
        port: Int,
    ): SSLSocket {
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        context.init(null, arrayOf(TrustEverything), SecureRandom())
        val raw = Socket().apply { connect(InetSocketAddress(host, port), 5_000) }
        return (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
            soTimeout = 5_000
            Conscrypt.setApplicationProtocols(this, arrayOf("now/1"))
            startHandshake()
        }
    }

    private fun session(
        host: String,
        port: Int,
    ): NowhereSession {
        val key = (SharedKey.of(keyText) as DecodeResult.Ok).value
        return NowhereSession(key, {
            val socket = tlsSocket(host, port)
            (TlsTransport.over(socket, ConscryptExporter()) as DecodeResult.Ok).value
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
    fun theFirstPayloadInTheOpeningWriteReachesTheTarget() {
        // NW-P-10's single write, proven end to end rather than by inspecting
        // what we sent: the echo server only ever sees bytes that made it
        // through the Portal.
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
    fun aWrongKeyIsRefusedWithoutAnAnswer() {
        // The failure mode that matters, and the one only a live Portal reveals.
        //
        // A rejected AuthFrame is met with SILENCE, not a close: the connection
        // is left open and ignored. That is a stronger anti-probing posture than
        // closing, since a prompt close is itself a signal — but it means the
        // read timeout is the only thing standing between a wrong key and a
        // hang. This test would have passed against a fake that returned EOF,
        // and the real client would have hung forever.
        val (host, port) = portal()
        val wrongKey = (SharedKey.of("definitely-not-the-key") as DecodeResult.Ok).value
        val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 9) as DecodeResult.Ok).value

        NowhereSession(wrongKey, {
            (TlsTransport.over(tlsSocket(host, port), ConscryptExporter()) as DecodeResult.Ok).value
        }).use { session ->
            val result = session.openFlow(target)
            assertTrue("a wrong key must not open a flow", result is DecodeResult.Invalid)
            assertEquals(LaneReason.NoSetupByte, result.reasonOrNull())
        }
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
