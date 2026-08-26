// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.SessionId
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.tls.ConscryptExporter
import eu.nodepass.somewhere.protocol.tls.TlsTransport
import org.conscrypt.Conscrypt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
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
 * The two L1 matrix rows that need a Portal to *stop* being there.
 *
 * Both are about time and process lifecycle rather than about bytes, which is
 * why neither could be reached by a fixture and why both sat uncovered: one
 * needs forty seconds of nothing happening, the other needs the Portal to die
 * and come back. So this class starts and stops its own Portals.
 *
 * Skipped unless `NOWHERE_BIN` points at a built binary — these are slow and
 * they belong to `conformance/scripts/portal-lifecycle.sh`, not to the unit
 * gate that runs on every commit.
 */
class PortalLifecycleTest {
    private val binary: File? = System.getenv("NOWHERE_BIN")?.let(::File)?.takeIf { it.canExecute() }
    private val key = "portal-lifecycle-key"
    private val portals = mutableListOf<Process>()
    private val closers = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        closers.forEach { runCatching { it() } }
        portals.forEach { runCatching { it.destroyForcibly() } }
    }

    private fun requireBinary(): File {
        assumeTrue(
            "NOWHERE_BIN is not set to a built nowhere binary — run conformance/scripts/portal-lifecycle.sh",
            binary != null,
        )
        return binary!!
    }

    /** A port nothing is using. Bound and released, which is as close as this gets to a guarantee. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startPortal(port: Int): Process {
        // To a file rather than to Redirect.DISCARD: the android.jar these
        // unit tests compile against does not carry that constant, and a log
        // worth reading after a failure costs nothing anyway.
        val log = File.createTempFile("portal-", ".log").apply { deleteOnExit() }
        val process =
            ProcessBuilder(requireBinary().absolutePath, "portal://$key@127.0.0.1:$port?log=info")
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        portals += process
        waitForPort(port, expected = true, what = "the Portal to listen on $port")
        return process
    }

    private fun stopPortal(
        process: Process,
        port: Int,
    ) {
        process.destroyForcibly()
        process.waitFor()
        waitForPort(port, expected = false, what = "the Portal to release $port")
    }

    private fun waitForPort(
        port: Int,
        expected: Boolean,
        what: String,
    ) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val open =
                runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
            if (open == expected) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
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

    private fun tlsSocket(
        port: Int,
        readTimeoutMillis: Int,
    ): SSLSocket {
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        context.init(null, arrayOf(TrustEverything), SecureRandom())
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 5_000) }
        return (context.socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket).apply {
            soTimeout = readTimeoutMillis
            Conscrypt.setApplicationProtocols(this, arrayOf("now/1"))
            startHandshake()
        }
    }

    private fun session(port: Int): NowhereSession {
        val shared = (SharedKey.of(key) as DecodeResult.Ok).value
        return NowhereSession(shared, {
            (TlsTransport.over(tlsSocket(port, 10_000), ConscryptExporter()) as DecodeResult.Ok).value
        })
    }

    /** Answers one request with the bytes it was sent. */
    private fun echoServer(): Int {
        val server = ServerSocket(0)
        closers += { runCatching { server.close() } }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { client ->
                        val buffer = ByteArray(4096)
                        val count = client.getInputStream().read(buffer)
                        if (count > 0) {
                            client.getOutputStream().write(buffer, 0, count)
                            client.getOutputStream().flush()
                        }
                    }
                }.onFailure { return@thread }
            }
        }
        return server.localPort
    }

    private fun loopbackTarget(port: Int): Target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), port) as DecodeResult.Ok).value

    @Test
    fun aConnectionThatAuthenticatesAndThenSaysNothingIsReclaimed() {
        // NW-P-11. The Portal reclaims a connection whose first FlowHeader byte
        // does not arrive within forty seconds of authentication.
        //
        // DedicatedTlsLane writes the header in the same call as the frame, so
        // nothing this client does can spend that budget — which is exactly why
        // the number was only ever recorded as a constant and never observed.
        // Anything that later separates the two writes needs to know the
        // deadline is real and how long it really is.
        val port = freePort()
        startPortal(port)

        // Longer than the deadline, so a Portal that never reclaims is observed
        // as a read timeout rather than as this test hanging.
        val socket = tlsSocket(port, RECLAIM_CEILING_MILLIS.toInt())
        closers += { runCatching { socket.close() } }
        val transport = (TlsTransport.over(socket, ConscryptExporter()) as DecodeResult.Ok).value

        val frame =
            Authentication.encodeFrame(
                sharedKey = (SharedKey.of(key) as DecodeResult.Ok).value,
                transport = AuthTransport.TlsTcp,
                exporter = transport.exporter,
                sessionId = SessionId.random().toByteArray(),
            )
        transport.write(frame)
        transport.flush()

        val startedAt = System.currentTimeMillis()
        val read = runCatching { transport.read(ByteArray(1)) }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(
            "the Portal neither closed nor answered within ${RECLAIM_CEILING_MILLIS / 1000}s " +
                "(read returned ${read.getOrNull()}, ${read.exceptionOrNull()?.javaClass?.simpleName})",
            read.getOrNull() == -1,
        )
        assertTrue(
            "reclaimed after ${elapsed}ms, which is not the ${DEDICATED_LANE_DEADLINE_SECONDS}s the specification states",
            elapsed in RECLAIM_FLOOR_MILLIS..RECLAIM_CEILING_MILLIS,
        )
        println("NW-P-11: the Portal reclaimed a silent connection after ${elapsed}ms")
    }

    @Test
    fun aNewFlowSucceedsAfterThePortalHasRestarted() {
        // The Portal going away and coming back is ordinary — a restart, a
        // deploy, a network that dropped. At L1 every flow is its own
        // connection, so recovery should need nothing from the user; this is
        // what says that out loud, because "should" and "does" have differed
        // before and a session that quietly poisoned itself would look like a
        // tunnel that stopped working for no reason.
        val port = freePort()
        val echo = echoServer()
        var portal = startPortal(port)
        val session = session(port)
        closers += { runCatching { session.close() } }

        assertEquals("hello-before", exchange(session, echo, "hello-before"))

        stopPortal(portal, port)
        portal = startPortal(port)

        assertEquals(
            "the same session had to keep working across the restart, with no reconnect",
            "hello-after",
            exchange(session, echo, "hello-after"),
        )
    }

    private fun exchange(
        session: NowhereSession,
        echoPort: Int,
        message: String,
    ): String {
        val payload = message.toByteArray(Charsets.US_ASCII)
        val opened = session.openFlow(loopbackTarget(echoPort), firstPayload = payload)
        val flow = (opened as? DecodeResult.Ok)?.value ?: throw AssertionError("the flow was refused: $opened")
        return flow.use {
            val buffer = ByteArray(payload.size)
            var filled = 0
            while (filled < buffer.size) {
                val read = it.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.US_ASCII)
        }
    }

    private companion object {
        const val DEDICATED_LANE_DEADLINE_SECONDS = 40

        /**
         * The window the reclaim has to land in.
         *
         * Wide, and deliberately so: the assertion is that the deadline exists
         * and is of this order, not that upstream's timer is precise to the
         * second. A window narrow enough to be interesting would only measure
         * how loaded the machine is.
         */
        const val RECLAIM_FLOOR_MILLIS = 25_000L
        const val RECLAIM_CEILING_MILLIS = 75_000L
    }
}
