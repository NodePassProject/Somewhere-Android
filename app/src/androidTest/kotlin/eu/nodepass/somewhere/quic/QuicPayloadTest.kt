// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Payload crosses a QUIC-carried flow, and it is the same payload TLS carries.
 *
 * ## Why this does not use the tunnel
 *
 * The obvious version of this test fetches through the TUN with an ordinary
 * socket. It cannot work, and for a reason that is correct rather than
 * incidental: **this client is forced out of its own tunnel in every mode**
 * (`AppSelection.ruleFor`), because a VPN inside its own tunnel is a routing
 * loop. Instrumentation runs in the app's process, so its sockets are outside
 * the TUN by construction and always will be.
 *
 * That is not a limitation to route around here — it is the reason
 * `TunnelHarness` now refuses a target the device can reach directly. This test
 * asks the question one layer down, where it can actually be answered: the
 * session dials the Portal itself and the Portal dials the origin.
 *
 * ## The target is the proof
 *
 * `127.0.0.1` is the **Portal's** loopback, not this device's. Nothing listens
 * on the device's own loopback, so a byte that comes back cannot have taken any
 * path except through the Portal. A test that used a directly reachable address
 * would pass with the carrier unplugged.
 */
@RunWith(AndroidJUnit4::class)
class QuicPayloadTest {
    @Test
    fun aTransferOverQuicArrivesIntact() {
        val fetched = overQuic()
        assertEquals("the origin's digest and ours disagree", fetched.declared, fetched.computed)
        assertTrue("nothing came back", fetched.bytes > 0)
    }

    /**
     * The same blob over both carriers produces the same digest.
     *
     * This is the shape L2's differential proved worth having: one case, two
     * carriers, compared. A carrier that quietly corrupted, truncated or
     * reordered would still produce a self-consistent transfer, and only the
     * comparison catches it.
     */
    @Test
    fun theSameBlobOverTlsAndOverQuicProducesTheSameDigest() {
        val quic = overQuic()
        val tls = overTls()
        assertEquals("TLS did not return what it declared", tls.declared, tls.computed)
        assertEquals("the two carriers returned different bytes", tls.computed, quic.computed)
        assertEquals("the two carriers returned different lengths", tls.bytes, quic.bytes)
    }

    /**
     * Sixteen flows at once over one connection.
     *
     * QUIC multiplexes by construction, so this is also the carrier-count
     * measurement: sixteen flows, one connection, where `mux=0` over TLS would
     * have opened sixteen.
     */
    @Test
    fun sixteenConcurrentFlowsShareOneConnection() {
        withQuicSession { session, _ ->
            val start = CountDownLatch(1)
            val done = CountDownLatch(CONCURRENT)
            val failure = AtomicReference<Throwable?>(null)
            val digests = java.util.concurrent.ConcurrentHashMap<Int, String>()

            repeat(CONCURRENT) { index ->
                Thread {
                    try {
                        start.await()
                        digests[index] = fetch(session, SMALL_PATH).computed
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()

            assertTrue("flows did not finish in time", done.await(120, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("a concurrent flow failed", it) }
            assertEquals("not every flow completed", CONCURRENT, digests.size)
            assertEquals(
                "concurrent flows disagreed about the same blob",
                1,
                digests.values.toSet().size,
            )
            assertEquals("QUIC used more than one connection", 1, session.carrierCount)
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private class Fetched(
        val declared: String,
        val computed: String,
        val bytes: Long,
    )

    private fun overQuic(): Fetched {
        var result: Fetched? = null
        withQuicSession { session, _ -> result = fetch(session, BLOB_PATH) }
        return result!!
    }

    private fun overTls(): Fetched {
        val portal = E2eEnvironment.requirePortal()
        val url = tcpNode(portal)
        val dialer = NowhereDialer(protect = { true })
        NowhereSession(
            sharedKey = url.sharedKey,
            connect = {
                when (val transport = dialer.connect(url)) {
                    is DecodeResult.Ok -> transport.value
                    is DecodeResult.Invalid -> error(transport.reason.detail)
                }
            },
        ).use { session ->
            return fetch(session, BLOB_PATH)
        }
    }

    private fun withQuicSession(body: (NowhereSession, QuicConnection) -> Unit) {
        val portal = E2eEnvironment.requirePortal()
        val url = quicNode(portal)
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
                ).use { session -> body(session, connection) }
            }
    }

    /** One HTTP/1.1 GET over one flow, digested as it arrives. */
    private fun fetch(
        session: NowhereSession,
        path: String,
    ): Fetched {
        val request =
            ("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$ORIGIN_PORT\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), ORIGIN_PORT) as DecodeResult.Ok).value

        val opened = session.openFlow(target, firstPayload = request)
        val flow =
            (opened as? DecodeResult.Ok)?.value
                ?: throw AssertionError("openFlow failed: ${opened.reasonOrNull()?.detail}")

        flow.use {
            val head = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var headerEnd = -1

            // Read until the blank line, keeping whatever body came with it.
            while (headerEnd < 0) {
                val count = flow.read(buffer)
                if (count <= 0) throw AssertionError("the flow ended inside the headers")
                head.write(buffer, 0, count)
                headerEnd = head.toByteArray().indexOfHeaderEnd()
            }

            val all = head.toByteArray()
            val headers = String(all, 0, headerEnd, Charsets.ISO_8859_1)
            assertTrue("not a 200: ${headers.lineSequence().first()}", headers.startsWith("HTTP/1.1 200"))
            val declared =
                headers
                    .lineSequence()
                    .firstOrNull { it.startsWith("X-Content-Sha256:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?: throw AssertionError("the origin did not declare a digest")

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val bodyStart = headerEnd + 4
            if (all.size > bodyStart) {
                digest.update(all, bodyStart, all.size - bodyStart)
                total += (all.size - bodyStart).toLong()
            }
            while (true) {
                val count = flow.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                total += count
            }
            return Fetched(declared, digest.digest().joinToString("") { "%02x".format(it) }, total)
        }
    }

    private fun ByteArray.indexOfHeaderEnd(): Int {
        for (index in 0..size - 4) {
            if (this[index] == CR && this[index + 1] == LF && this[index + 2] == CR && this[index + 3] == LF) {
                return index
            }
        }
        return -1
    }

    private fun quicNode(portal: String): NowhereUrl = node(portal, "udp")

    private fun tcpNode(portal: String): NowhereUrl = node(portal, "tcp")

    private fun node(
        portal: String,
        carrier: String,
    ): NowhereUrl {
        val url = "nowhere://${E2eEnvironment.sharedKey}@$portal?up=$carrier&down=$carrier"
        return when (val parsed = NowhereUrl.parse(url)) {
            is DecodeResult.Ok -> parsed.value
            is DecodeResult.Invalid -> throw AssertionError("the test node URL does not parse: ${parsed.reason.detail}")
        }
    }

    private companion object {
        const val ORIGIN_PORT = 28091
        const val BLOB_PATH = "/blob.bin"

        /**
         * The small payload for the concurrency case. Sixteen simultaneous
         * 20 MB fetches saturate an emulator and time out, proving something
         * about the machine rather than about the carrier.
         */
        const val SMALL_PATH = "/small.bin"
        const val CONCURRENT = 16
        const val CR: Byte = 13
        const val LF: Byte = 10
    }
}
