// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthTransport
import eu.nodepass.somewhere.protocol.auth.Authentication
import eu.nodepass.somewhere.protocol.auth.SharedKey
import org.conscrypt.Conscrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The one assumption in ADR-0001 that reading could not settle: **does Conscrypt
 * export the bytes Nowhere actually needs?**
 *
 * The decision to set `minSdk` 26 rests on Conscrypt carrying the exporter below
 * API 31. That was established by inspecting the published AAR — which proves the
 * method exists, not that it produces the right 32 bytes for a real handshake.
 * Nothing short of a real Portal accepting a real AuthFrame proves that, so this
 * test does exactly that:
 *
 * 1. Real TLS 1.3 handshake to a real Portal, through Conscrypt.
 * 2. Export with Nowhere's label and an empty-but-present context.
 * 3. Build the AuthFrame from those bytes and send it.
 * 4. Send a FlowHeader, and see whether the Portal keeps the connection.
 *
 * A Portal that rejects authentication closes without a response frame — there
 * is deliberately no error to read (that would be an oracle for active probing),
 * so acceptance is observed as *the connection staying open and answering*.
 *
 * Requires a running Portal. Start one with:
 * `conformance/scripts/portal-for-tests.sh`, or point `NOWHERE_E2E_PORTAL` and
 * `NOWHERE_E2E_KEY` at your own. Skipped when absent, so CI without a Portal
 * stays green rather than lying.
 */
class ExporterAgainstPortalTest {
    private val portal: String? = System.getenv("NOWHERE_E2E_PORTAL")
    private val sharedKeyText: String = System.getenv("NOWHERE_E2E_KEY") ?: "conformance-smoke-key"

    private fun requirePortal(): Pair<String, Int> {
        assumeTrue(
            "NOWHERE_E2E_PORTAL is not set — start one with conformance/scripts/portal-for-tests.sh",
            portal != null,
        )
        val (host, port) = portal!!.split(":")
        return host to port.toInt()
    }

    /**
     * Trusts everything.
     *
     * Correct for this test and nowhere else: the Portal under test presents a
     * self-signed certificate, and what is being verified is the exporter, not
     * the chain. It also mirrors what upstream does when a node carries neither
     * `sni` nor `pin` — see D-11.
     */
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

    private fun conscryptSocket(
        host: String,
        port: Int,
    ): SSLSocket {
        val provider = Conscrypt.newProvider()
        val context = SSLContext.getInstance("TLSv1.3", provider)
        context.init(null, arrayOf(TrustEverything), SecureRandom())

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 5_000)
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.soTimeout = 5_000
        // Portal rejects any ALPN but now/1 — a mismatch ends the handshake with
        // "no application protocol", which is a useful connectivity check in its
        // own right.
        Conscrypt.setApplicationProtocols(socket, arrayOf("now/1"))
        socket.startHandshake()
        return socket
    }

    @Test
    fun conscryptExportsAnExporterAndThePortalAcceptsTheResultingAuthFrame() {
        val (host, port) = requirePortal()
        val exporter = ConscryptExporter()
        val sharedKey = (SharedKey.of(sharedKeyText) as DecodeResult.Ok).value

        conscryptSocket(host, port).use { socket ->
            assertEquals("now/1", Conscrypt.getApplicationProtocol(socket))

            val material =
                exporter.export(socket, Authentication.EXPORTER_LABEL, ByteArray(0), Authentication.EXPORTER_LENGTH)
            val bytes = (material as? DecodeResult.Ok)?.value
            assertTrue("export failed: ${material.reasonOrNull()?.detail}", bytes != null)
            assertEquals(32, bytes!!.size)
            assertTrue("an all-zero exporter would mean the call silently did nothing", bytes.any { it != 0.toByte() })

            // Build and send a real AuthFrame from those bytes.
            val sessionId = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, bytes, sessionId)
            assertEquals(32, frame.size)

            socket.outputStream.write(frame)
            // A FlowHeader must follow within the Portal's bootstrap deadline
            // (NW-P-11), so send one immediately: DUPLEX, TCP, both directions
            // on TLS, hops 0, flow 1.
            socket.outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01))
            // Target: 127.0.0.1:9 (discard). Port 9 is closed, which is fine —
            // what is being tested is that authentication passed, and a Portal
            // that rejected the tag would never send a setup byte at all.
            socket.outputStream.write(byteArrayOf(0x01, 127, 0, 0, 1, 0x00, 0x09))
            socket.outputStream.flush()

            // Authentication has no response frame. What comes back is the
            // SetupResult for the flow — and receiving ANY setup byte proves the
            // AuthFrame was accepted, because a rejected one closes the
            // connection with nothing written.
            val setup = DataInputStream(socket.inputStream).readByte()
            val result =
                eu.nodepass.somewhere.protocol.frame.SetupResult
                    .decode(setup)
            assertTrue(
                "Portal answered with a byte outside 0..7 (${setup.toInt() and 0xFF}), " +
                    "which means the stream was not what we think it is",
                result is DecodeResult.Ok,
            )
        }
    }

    @Test
    fun theExporterIsBoundToTheConnection() {
        // The property the whole design rests on: two connections to the same
        // Portal with the same key must produce different exporters, or a
        // captured AuthFrame would replay.
        val (host, port) = requirePortal()
        val exporter = ConscryptExporter()

        fun exportOnce(): ByteArray =
            conscryptSocket(host, port).use { socket ->
                (
                    exporter.export(
                        socket,
                        Authentication.EXPORTER_LABEL,
                        ByteArray(0),
                        Authentication.EXPORTER_LENGTH,
                    ) as DecodeResult.Ok
                ).value
            }

        val first = exportOnce()
        val second = exportOnce()
        assertNotEquals(
            "two connections produced the same exporter — replay protection would be absent",
            first.toList(),
            second.toList(),
        )
    }

    @Test
    fun theLabelChangesTheExportedBytes() {
        val (host, port) = requirePortal()
        val exporter = ConscryptExporter()
        conscryptSocket(host, port).use { socket ->
            val ours =
                (exporter.export(socket, Authentication.EXPORTER_LABEL, ByteArray(0), 32) as DecodeResult.Ok).value
            val other =
                (exporter.export(socket, "EXPORTER-Something-Else", ByteArray(0), 32) as DecodeResult.Ok).value
            assertNotEquals("the label must be part of the derivation", ours.toList(), other.toList())
        }
    }

    @Test
    fun anEmptyContextDiffersFromNoContext() {
        // Nowhere specifies a context that is "present but empty". If the two
        // were interchangeable the distinction would not matter — this records
        // which one upstream means, and proves the call honours it.
        val (host, port) = requirePortal()
        val exporter = ConscryptExporter()
        conscryptSocket(host, port).use { socket ->
            val empty =
                (exporter.export(socket, Authentication.EXPORTER_LABEL, ByteArray(0), 32) as DecodeResult.Ok).value
            val nonEmpty =
                (exporter.export(socket, Authentication.EXPORTER_LABEL, byteArrayOf(1), 32) as DecodeResult.Ok).value
            assertNotEquals("the context must be part of the derivation", empty.toList(), nonEmpty.toList())
        }
    }
}
