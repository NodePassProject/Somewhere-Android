// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.frame.UdpOverTcp
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.LaneReason
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.tls.ConscryptExporter
import eu.nodepass.somewhere.protocol.tls.TlsTransport
import org.conscrypt.Conscrypt
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * This implementation's half of the oracle differential. NW-Q-04.
 *
 * It does not assert. It **reports**: each case is run against a live Portal
 * and its observable outcome is written to a file, in the same alphabet the
 * Rust client's half writes. `conformance/scripts/oracle-diff.sh` runs both and
 * compares them, and the comparison is the test.
 *
 * Splitting it that way is the point. A test that asserted its own expectations
 * would be this implementation agreeing with itself — which is exactly what the
 * conformance vectors already do, and exactly what cannot catch a case where
 * both the code and its author's understanding of the specification are wrong
 * in the same direction. The oracle has no such shared ancestry.
 *
 * ## One alphabet, borrowed from upstream
 *
 * The two implementations observe different things: this one reads the setup
 * byte directly, while the Rust client is a SOCKS5 proxy whose caller sees only
 * a reply code. They are made comparable by putting *this* side through
 * upstream's own mapping from an open-flow failure to a SOCKS reply — the table
 * in `src/vector/flow.rs`, `OpenFlowError::socks_reply`.
 *
 * That table lives here, in the conformance harness, and **not in the client**.
 * It is upstream's implementation rather than the specification, this client
 * speaks no SOCKS, and a mapping copied into `protocol/` would be a deployment
 * detail of somebody else's front end. Here it is a translation dictionary for
 * a comparison, which is what it actually is.
 */
class OracleDifferentialTest {
    private val portalEnv: String? = System.getenv("NOWHERE_E2E_PORTAL")
    private val keyText: String = System.getenv("NOWHERE_E2E_KEY") ?: "conformance-smoke-key"
    private val output: String? = System.getenv("ORACLE_DIFF_OUT")

    /** Where each case points. Supplied by the script, never defaulted to something real. */
    private fun endpoint(name: String): Pair<String, Int> {
        val value = System.getenv(name) ?: throw AssertionError("$name is not set; run conformance/scripts/oracle-diff.sh")
        return value.substringBeforeLast(':') to value.substringAfterLast(':').toInt()
    }

    @Test
    fun runEveryCaseAndReportWhatHappened() {
        assumeTrue(
            "NOWHERE_E2E_PORTAL is not set — run conformance/scripts/oracle-diff.sh",
            portalEnv != null && output != null,
        )
        val (host, port) = portalEnv!!.substringBeforeLast(':') to portalEnv.substringAfterLast(':').toInt()
        val (targetHost, targetPort) = endpoint("ORACLE_TARGET")
        val (nameHost, namePort) = endpoint("ORACLE_TARGET_NAME")
        val (udpHost, udpPort) = endpoint("ORACLE_UDP")
        val (closedHost, closedPort) = endpoint("ORACLE_CLOSED")

        val verdicts = LinkedHashMap<String, Verdict>()

        verdicts["tcp_ip_payload"] =
            fetch(host, port, keyText, ipTarget(targetHost, targetPort), targetHost)

        // The same fetch by name. Both implementations send a domain target and
        // the Portal resolves it, so this is remote resolution as the wire sees
        // it — not the device-side half, which needs a real TUN.
        verdicts["tcp_domain_payload"] =
            fetch(host, port, keyText, Target.ofDomain(nameHost, namePort), nameHost)

        // A port with nothing behind it. The Portal answers DIAL_FAILED, which
        // is the one rejection reachable without a second carrier.
        verdicts["dial_failed"] =
            fetch(host, port, keyText, ipTarget(closedHost, closedPort), closedHost)

        // Upstream answers a bad authentication tag with silence rather than a
        // close, deliberately, so that failure is not an oracle for probing.
        verdicts["wrong_key"] =
            fetch(host, port, "not-the-shared-key", ipTarget(targetHost, targetPort), targetHost)

        verdicts["uot_round_trip"] = udpEcho(host, port, keyText, udpHost, udpPort)

        File(output!!).writeText(
            // "-" rather than an empty column: a reader splitting on tabs with
            // a whitespace IFS collapses consecutive ones, and an empty field
            // then shifts every column after it.
            verdicts.entries.joinToString("\n") { (case, verdict) ->
                "$case\t${verdict.reply}\t${verdict.detail.ifEmpty { "-" }}\t${verdict.note.ifEmpty { "-" }}"
            } + "\n",
        )
    }

    /**
     * One case's outcome.
     *
     * [reply] and [detail] are the diffed columns; [note] is this
     * implementation's own description, printed so that a divergence can be
     * read rather than merely counted.
     */
    private data class Verdict(
        val reply: Int,
        val detail: String,
        val note: String,
    )

    private fun ipTarget(
        host: String,
        port: Int,
    ): DecodeResult<Target> = Target.ofIpv4(host.split('.').map { it.toInt().toByte() }.toByteArray(), port)

    private fun fetch(
        portalHost: String,
        portalPort: Int,
        key: String,
        target: DecodeResult<Target>,
        requestHost: String,
    ): Verdict {
        val resolved = (target as? DecodeResult.Ok)?.value ?: return Verdict(REPLY_GENERAL_FAILURE, "", "target rejected locally")
        return withFlow(portalHost, portalPort, key, resolved, FlowKind.Tcp) { flow ->
            val request =
                "GET $BLOB_PATH HTTP/1.1\r\nHost: $requestHost\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            flow.write(request)
            flow.flush()
            val body = readHttpBody(flow)
            Verdict(REPLY_SUCCEEDED, sha256(body), "READY, ${body.size} bytes")
        }
    }

    private fun udpEcho(
        portalHost: String,
        portalPort: Int,
        key: String,
        host: String,
        port: Int,
    ): Verdict {
        val target = (ipTarget(host, port) as DecodeResult.Ok).value
        return withFlow(portalHost, portalPort, key, target, FlowKind.Udp) { flow ->
            val payload = ByteArray(512) { (it * 31 + 7).toByte() }
            val framed = (UdpOverTcp.encode(payload) as DecodeResult.Ok).value
            flow.write(framed)
            flow.flush()

            val prefix = ByteArray(UdpOverTcp.LENGTH_PREFIX_SIZE)
            readFully(flow, prefix)
            val length = ((prefix[0].toInt() and 0xFF) shl 8) or (prefix[1].toInt() and 0xFF)
            val echoed = ByteArray(length)
            readFully(flow, echoed)
            Verdict(REPLY_SUCCEEDED, sha256(echoed), "READY, $length bytes back")
        }
    }

    /** Opens a flow, runs [body], and turns whatever happened into one verdict. */
    private fun withFlow(
        portalHost: String,
        portalPort: Int,
        key: String,
        target: Target,
        kind: FlowKind,
        body: (Flow) -> Verdict,
    ): Verdict {
        val session = session(portalHost, portalPort, key)
        return try {
            when (val opened = session.openFlow(target, kind)) {
                is DecodeResult.Ok -> opened.value.use(body)
                is DecodeResult.Invalid -> rejection(opened.reason)
            }
        } catch (error: Exception) {
            // A transport that failed before the protocol was reached.
            Verdict(REPLY_NETWORK_UNREACHABLE, "", "transport: ${error.javaClass.simpleName}")
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * This side's outcome, put through upstream's own client-side mapping.
     *
     * `src/vector/flow.rs`, `OpenFlowError::socks_reply`. The lossiness is
     * upstream's: several distinct rejections share reply 1, and that is
     * precisely why the untranslated reason travels alongside in the note.
     */
    private fun rejection(reason: eu.nodepass.somewhere.protocol.DecodeReason): Verdict =
        when (reason) {
            is LaneReason.Rejected ->
                when (reason.result) {
                    SetupResult.InvalidRequest, SetupResult.FlowLimit ->
                        Verdict(REPLY_CONNECTION_NOT_ALLOWED, "", reason.result.name)

                    SetupResult.DialFailed -> Verdict(REPLY_HOST_UNREACHABLE, "", reason.result.name)
                    SetupResult.PairTimeout -> Verdict(REPLY_TTL_EXPIRED, "", reason.result.name)
                    else -> Verdict(REPLY_GENERAL_FAILURE, "", reason.result.name)
                }

            // No setup byte: the Portal accepted the connection and said
            // nothing, which is what a rejected AuthFrame looks like on the
            // wire. Upstream's own client reaches reply 1 here too, by way of
            // its setup timeout — so the alphabets agree while the reasons
            // stay visible in the note.
            LaneReason.NoSetupByte -> Verdict(REPLY_GENERAL_FAILURE, "", "no setup byte (silence)")
            else -> Verdict(REPLY_GENERAL_FAILURE, "", reason.detail)
        }

    private fun readHttpBody(flow: Flow): ByteArray {
        val all = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = flow.read(buffer)
            if (read < 0) break
            all.write(buffer, 0, read)
        }
        val bytes = all.toByteArray()
        val separator = indexOfHeaderEnd(bytes)
        return if (separator < 0) ByteArray(0) else bytes.copyOfRange(separator, bytes.size)
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        for (index in 0..bytes.size - 4) {
            if (bytes[index] == CR && bytes[index + 1] == LF && bytes[index + 2] == CR && bytes[index + 3] == LF) {
                return index + 4
            }
        }
        return -1
    }

    private fun readFully(
        flow: Flow,
        into: ByteArray,
    ) {
        var filled = 0
        while (filled < into.size) {
            val read = flow.read(into, filled, into.size - filled)
            if (read < 0) throw IllegalStateException("the stream ended after $filled of ${into.size} bytes")
            filled += read
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

    private fun session(
        host: String,
        port: Int,
        key: String,
    ): NowhereSession {
        val shared = (SharedKey.of(key) as DecodeResult.Ok).value
        return NowhereSession(shared, {
            val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
            context.init(null, arrayOf(TrustEverything), SecureRandom())
            val raw = Socket().apply { connect(InetSocketAddress(host, port), 5_000) }
            val socket =
                (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                    soTimeout = SETUP_TIMEOUT_MILLIS
                    Conscrypt.setApplicationProtocols(this, arrayOf("now/1"))
                    startHandshake()
                }
            (TlsTransport.over(socket, ConscryptExporter()) as DecodeResult.Ok).value
        })
    }

    private companion object {
        const val BLOB_PATH = "/blob.bin"

        /**
         * Long enough for a Portal to answer, short enough that a silent one is
         * observed as silence rather than as a hung test. Upstream's own client
         * gives up at five seconds, measured.
         */
        const val SETUP_TIMEOUT_MILLIS = 8_000

        const val CR: Byte = 13
        const val LF: Byte = 10

        // RFC 1928 section 6, and the values upstream's own mapping produces.
        const val REPLY_SUCCEEDED = 0
        const val REPLY_GENERAL_FAILURE = 1
        const val REPLY_CONNECTION_NOT_ALLOWED = 2
        const val REPLY_NETWORK_UNREACHABLE = 3
        const val REPLY_HOST_UNREACHABLE = 4
        const val REPLY_TTL_EXPIRED = 6
    }
}
