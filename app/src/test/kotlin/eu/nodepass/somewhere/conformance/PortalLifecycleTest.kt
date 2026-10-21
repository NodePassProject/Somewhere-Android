// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.Transport
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.tls.ServerTrust
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * The matrix rows that need a Portal to *stop* being there.
 *
 * Both are about time and process lifecycle rather than about bytes, which is
 * why neither could be reached by a fixture: one needs a Portal to give up on
 * a silent connection, the other needs the Portal to die and come back. So this
 * class starts and stops its own Portals.
 *
 * They run on a **supplied** certificate (`tls=2`), generated here with
 * `openssl` for `127.0.0.1` and used as the only trust anchor. A generated one
 * (`tls=1`) is new on every start, so a pin taken before a restart would refuse
 * the Portal after it — correctly, and not what the restart case is about. It
 * also means these are the live cases for verification by name, which the
 * pinned cases elsewhere do not reach.
 *
 * Skipped unless `NOWHERE_BIN` points at a built binary — these are slow and
 * they belong to `conformance/scripts/portal-lifecycle.sh`, not to the unit
 * gate that runs on every commit.
 */
class PortalLifecycleTest {
    private val binary: File? = System.getenv("NOWHERE_BIN")?.let(::File)?.takeIf { it.canExecute() }
    private val key = "abcdefabcdefabcdefabcdefabcdef01"
    private val certificateDir: File by lazy { makeCertificate() }
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
            ProcessBuilder(
                requireBinary().absolutePath,
                "portal://$key@127.0.0.1:$port?tls=2&crt=${File(certificateDir, "crt.pem")}&key=${File(certificateDir, "key.pem")}",
            ).redirectErrorStream(true)
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

    /** A self-signed certificate naming 127.0.0.1, or the test is skipped without openssl. */
    private fun makeCertificate(): File {
        val dir =
            File.createTempFile("portal-cert-", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }
        val made =
            runCatching {
                ProcessBuilder(
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "ec",
                    "-pkeyopt",
                    "ec_paramgen_curve:P-256",
                    "-nodes",
                    "-keyout",
                    File(dir, "key.pem").path,
                    "-out",
                    File(dir, "crt.pem").path,
                    "-days",
                    "2",
                    "-subj",
                    "/CN=127.0.0.1",
                    "-addext",
                    "subjectAltName=IP:127.0.0.1",
                ).redirectErrorStream(true).start().waitFor()
            }.getOrDefault(-1)
        assumeTrue("openssl is needed to make the Portal's certificate", made == 0)
        return dir
    }

    /** Trusts exactly the certificate made above, through the same trust-manager path the platform uses. */
    private val trust: ServerTrust by lazy {
        val anchor =
            File(certificateDir, "crt.pem").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("portal", anchor)
            }
        val manager =
            TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(store) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
        ServerTrust { chain, _ -> manager.checkServerTrusted(chain, "UNKNOWN") }
    }

    private fun node(port: Int): NowhereUrl = (NowhereUrl.parse("nowhere://$key@127.0.0.1:$port") as DecodeResult.Ok).value

    private fun transport(
        port: Int,
        readTimeoutMillis: Int,
    ): Transport =
        when (val dialed = NowhereDialer(readTimeoutMillis = readTimeoutMillis, trust = trust).connect(node(port))) {
            is DecodeResult.Ok -> dialed.value
            is DecodeResult.Invalid -> throw AssertionError("could not dial the Portal: ${dialed.reason.detail}")
        }

    private fun session(port: Int): NowhereSession = NowhereSession(node(port).sharedKey, { transport(port, 10_000) })

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
        // "Vector TLS requires a Flow because it is a dedicated connection."
        // A connection that sends its AuthFrame and no FlowStart is holding a
        // Portal resource for nothing, and the Portal must give it up.
        //
        // DedicatedTlsLane writes the FlowStart in the same call as the frame,
        // so nothing this client does can spend that budget — which is why the
        // number is observed here rather than assumed. Anything that later
        // separates the two writes needs to know how long it really is.
        val port = freePort()
        startPortal(port)

        // Longer than the deadline, so a Portal that never reclaims is observed
        // as a read timeout rather than as this test hanging.
        val transport = transport(port, RECLAIM_CEILING_MILLIS.toInt())
        closers += { runCatching { transport.close() } }

        val frame =
            Authentication.encodeFrame(
                sharedKey = (SharedKey.of(key) as DecodeResult.Ok).value,
                transport = AuthTransport.TlsTcp,
                exporter = transport.exporter,
            )
        transport.write(frame)
        transport.flush()

        val startedAt = System.currentTimeMillis()
        val answer = ByteArray(1)
        val read = runCatching { transport.read(answer) }
        val elapsed = System.currentTimeMillis() - startedAt

        // Measured against v3.0.1: the Portal waits out its handshake deadline
        // for the flow request, answers INVALID_REQUEST, and shuts the stream.
        // An answer rather than silence, which is why this asserts the byte.
        assertEquals(
            "the Portal should answer a missing flow request (read returned ${read.getOrNull()}, " +
                "${read.exceptionOrNull()?.javaClass?.simpleName})",
            1,
            read.getOrNull(),
        )
        assertEquals(SetupResult.InvalidRequest, SetupResult.decode(answer[0]).valueOrNull())
        assertEquals("and then end the stream", -1, runCatching { transport.read(ByteArray(1)) }.getOrNull())
        assertTrue(
            "answered after ${elapsed}ms, outside the ${RECLAIM_FLOOR_MILLIS}..${RECLAIM_CEILING_MILLIS}ms window",
            elapsed in RECLAIM_FLOOR_MILLIS..RECLAIM_CEILING_MILLIS,
        )
        println("the Portal reclaimed an authenticated connection with no flow after ${elapsed}ms, answering INVALID_REQUEST")
    }

    @Test
    fun aNewFlowSucceedsAfterThePortalHasRestarted() {
        // The Portal going away and coming back is ordinary — a restart, a
        // deploy, a network that dropped. Over TLS every flow is its own
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
        /**
         * The window the reclaim has to land in: around the five-second
         * handshake deadline (`NOW_HANDSHAKE_TIMEOUT`), which is what upstream
         * applies to the flow request. Measured at 5.08 s.
         *
         * Wide, and deliberately so: the assertion is that the deadline exists
         * and is of this order, not that upstream's timer is precise to the
         * second. A window narrow enough to be interesting would only measure
         * how loaded the machine is.
         */
        const val RECLAIM_FLOOR_MILLIS = 3_000L
        const val RECLAIM_CEILING_MILLIS = 15_000L
    }
}
