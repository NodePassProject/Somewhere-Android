// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRejected
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
import kotlin.concurrent.thread

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
 *
 * ## Both carriers, one case list
 *
 * Every case runs twice, once with `mux=0` and once with `mux=1`, and the
 * second set is prefixed `mux_`. Nothing about a case is supposed to change
 * between the two: the Mux carrier moves the same frames over a shared
 * connection, so any case whose outcome differs is either a defect here or a
 * misreading of what Mux is for. Running them as one list rather than writing
 * separate Mux cases is what makes that assertion rather than an assumption.
 *
 * The `burst` cases are the ones Mux exists for, and the only ones that need
 * flows to be open **simultaneously**: how many connections a carrier used is
 * not observable from flows that merely happened quickly. The origin they
 * fetch from answers nobody until all [BURST_WIDTH] requests have arrived, so
 * the overlap is guaranteed by the origin rather than by this machine being
 * fast. The connections themselves are counted at the Portal, by the script —
 * neither implementation is asked how many it opened, because that is exactly
 * the number a broken one would get wrong in its own favour.
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

        val (holdDedicatedHost, holdDedicatedPort) = endpoint("ORACLE_HOLD_DEDICATED")
        val (holdMuxHost, holdMuxPort) = endpoint("ORACLE_HOLD_MUX")

        val verdicts = LinkedHashMap<String, Verdict>()

        for ((prefix, mux) in listOf("" to false, "mux_" to true)) {
            verdicts["${prefix}tcp_ip_payload"] =
                fetch(host, port, keyText, mux, ipTarget(targetHost, targetPort), targetHost)

            // The same fetch by name. Both implementations send a domain target
            // and the Portal resolves it, so this is remote resolution as the
            // wire sees it — not the device-side half, which needs a real TUN.
            verdicts["${prefix}tcp_domain_payload"] =
                fetch(host, port, keyText, mux, Target.ofDomain(nameHost, namePort), nameHost)

            // A port with nothing behind it. The Portal answers DIAL_FAILED,
            // which is the one rejection reachable without a second carrier.
            verdicts["${prefix}dial_failed"] =
                fetch(host, port, keyText, mux, ipTarget(closedHost, closedPort), closedHost)

            // Upstream answers a bad authentication tag with silence rather
            // than a close, deliberately, so that failure is not an oracle for
            // probing. Worth running over both carriers rather than once: at
            // `mux=1` the AuthFrame and the 0xff marker leave in a single
            // write, so a Portal that refuses the key is refusing a connection
            // that has already declared what it intends to become.
            verdicts["${prefix}wrong_key"] =
                fetch(host, port, "not-the-shared-key", mux, ipTarget(targetHost, targetPort), targetHost)

            verdicts["${prefix}uot_round_trip"] = udpEcho(host, port, keyText, mux, udpHost, udpPort)
        }

        // The arithmetic L2 exists for, stated as two measurements rather than
        // one: without the dedicated half, "four connections" is a number with
        // nothing to be smaller than, and a harness that had stopped counting
        // would report it just as happily.
        verdicts["dedicated_burst"] = burst(host, port, false, holdDedicatedHost, holdDedicatedPort)
        verdicts["mux_burst"] = burst(host, port, true, holdMuxHost, holdMuxPort)

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
        mux: Boolean,
        target: DecodeResult<Target>,
        requestHost: String,
    ): Verdict {
        val resolved = (target as? DecodeResult.Ok)?.value ?: return Verdict(REPLY_GENERAL_FAILURE, "", "target rejected locally")
        return withFlow(portalHost, portalPort, key, mux, resolved, FlowKind.Tcp) { flow ->
            get(flow, BLOB_PATH, requestHost)
        }
    }

    /** One HTTP GET over an open flow, reported as a verdict. */
    private fun get(
        flow: Flow,
        path: String,
        requestHost: String,
    ): Verdict {
        val request =
            "GET $path HTTP/1.1\r\nHost: $requestHost\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
        flow.write(request)
        flow.flush()
        val body = readHttpBody(flow)
        return Verdict(REPLY_SUCCEEDED, sha256(body), "READY, ${body.size} bytes")
    }

    /**
     * [BURST_WIDTH] flows opened at once, on **one** session.
     *
     * One session because that is the whole question: a session is what owns
     * the carriers, and flows spread across several sessions would each get
     * their own no matter what `mux` said.
     *
     * Every flow is opened from its own thread and none of them can finish
     * before the last one has opened, because the origin holds them all until
     * it has [BURST_WIDTH] requests in hand. What this reports is only whether
     * all of them completed carrying the same bytes; how many connections that
     * took is counted at the Portal, where neither implementation can flatter
     * itself.
     */
    private fun burst(
        portalHost: String,
        portalPort: Int,
        mux: Boolean,
        host: String,
        port: Int,
    ): Verdict {
        val target = (ipTarget(host, port) as DecodeResult.Ok).value
        val session = session(portalHost, portalPort, keyText, mux)
        val outcomes = arrayOfNulls<Verdict>(BURST_WIDTH)
        try {
            (0 until BURST_WIDTH)
                .map { index ->
                    thread {
                        outcomes[index] =
                            try {
                                when (val opened = session.openFlow(target, FlowKind.Tcp)) {
                                    is DecodeResult.Ok -> opened.value.use { flow -> get(flow, HOLD_PATH, host) }
                                    is DecodeResult.Invalid -> rejection(opened.reason)
                                }
                            } catch (error: Exception) {
                                Verdict(REPLY_NETWORK_UNREACHABLE, "", "transport: ${error.javaClass.simpleName}")
                            }
                    }
                }.forEach { it.join() }
        } finally {
            runCatching { session.close() }
        }

        val verdicts = outcomes.filterNotNull()
        val failed = verdicts.filter { it.reply != REPLY_SUCCEEDED }
        if (failed.isNotEmpty()) {
            return Verdict(failed.first().reply, "", "${failed.size} of $BURST_WIDTH flows failed: ${failed.first().note}")
        }
        val digests = verdicts.map { it.detail }.toSet()
        if (digests.size != 1) {
            return Verdict(REPLY_GENERAL_FAILURE, "", "$BURST_WIDTH flows returned ${digests.size} different payloads")
        }
        return Verdict(REPLY_SUCCEEDED, digests.first(), "$BURST_WIDTH of $BURST_WIDTH flows held open together")
    }

    private fun udpEcho(
        portalHost: String,
        portalPort: Int,
        key: String,
        mux: Boolean,
        host: String,
        port: Int,
    ): Verdict {
        val target = (ipTarget(host, port) as DecodeResult.Ok).value
        return withFlow(portalHost, portalPort, key, mux, target, FlowKind.Udp) { flow ->
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
        mux: Boolean,
        target: Target,
        kind: FlowKind,
        body: (Flow) -> Verdict,
    ): Verdict {
        val session = session(portalHost, portalPort, key, mux)
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
            // `FlowRejected` rather than either carrier's own rejection type,
            // which is the whole reason that interface exists: this asks what
            // the Portal said, and the Portal says the same thing over a lane
            // and over a shard. Matching one carrier's shape is how the first
            // run of the Mux half reported `mux_dial_failed` as a general
            // failure while the oracle reported a host that could not be
            // reached — a divergence in the reading, not on the wire.
            is FlowRejected ->
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
        mux: Boolean,
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
        }, mux = mux)
    }

    private companion object {
        const val BLOB_PATH = "/blob.bin"

        /** The origin that answers nobody until [BURST_WIDTH] flows are waiting. */
        const val HOLD_PATH = "/hold"

        /**
         * Flows opened at once in the burst cases.
         *
         * Sixteen because upstream places four active flows per shard, so this
         * is four shards' worth — enough that a carrier which quietly stopped
         * multiplexing is off by twelve rather than by one, and small enough to
         * stay a test rather than a load generator.
         */
        const val BURST_WIDTH = 16

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
