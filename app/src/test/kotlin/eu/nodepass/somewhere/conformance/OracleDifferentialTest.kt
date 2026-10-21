// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import eu.nodepass.somewhere.net.NowhereDialer
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.FlowRejected
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.frame.UdpOverTcp
import eu.nodepass.somewhere.protocol.morph.MorphDatagram
import eu.nodepass.somewhere.protocol.morph.MorphKeys
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.LaneReason
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.session.PacketFlow
import eu.nodepass.somewhere.protocol.session.QuicCarrier
import eu.nodepass.somewhere.protocol.session.QuicCarrierReason
import eu.nodepass.somewhere.protocol.session.StreamTransport
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.quic.QuicConnection
import eu.nodepass.somewhere.quic.QuicStreamTransport
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
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
 * ## Every carrier, one case list
 *
 * Every case runs over TLS and over QUIC, each against a Portal with Morph on
 * and one with Morph off: four combinations, prefixed `""`, `quic_`,
 * `nomorph_` and `nomorph_quic_`. Nothing about a case is supposed to change
 * between them — the carriers move the same frames, and Morph masks the bytes
 * underneath without touching them — so any case whose outcome differs is a
 * defect here or a misreading of what the carrier or the mask is for.
 *
 * The `burst` cases are the only ones that need flows open **at the same
 * time**: how many connections a carrier used is not observable from flows
 * that merely happened quickly. The origin answers nobody until all
 * [BURST_WIDTH] requests have arrived, and at that instant the script counts
 * the TCP connections to the Portal — sixteen over TLS, which is one per flow
 * as v3 specifies, and none over QUIC. Neither implementation is asked how many
 * it opened, because that is the number a broken one would get wrong in its
 * own favour.
 */
class OracleDifferentialTest {
    /**
     * The carrier combinations this client can be run in.
     *
     * One case list over several carriers is where the differential earns its
     * keep: L2's only finding was a protocol fact that had grown a second shape
     * the moment a second carrier landed, and it was invisible to every test
     * that asserted on one carrier's own types. Three more carriers are three
     * more chances for that.
     *
     * The QUIC ones are skipped unless a host bridge was built — see
     * `conformance/scripts/build-host-quic.sh`. Skipped and said so, rather
     * than quietly reporting the TLS answer under a QUIC name.
     */
    private enum class Carriers(
        val prefix: String,
        val quic: Boolean,
        val morph: Boolean,
    ) {
        Tls("", quic = false, morph = true),
        Quic("quic_", quic = true, morph = true),
        TlsNoMorph("nomorph_", quic = false, morph = false),
        QuicNoMorph("nomorph_quic_", quic = true, morph = false),
    }

    /** Set when a host QUIC bridge exists; absent means the QUIC rows are skipped. */
    private val hostQuic: String? = System.getProperty("somewhere.quic.library")

    private val portalEnv: String? = System.getenv("NOWHERE_E2E_PORTAL")
    private val keyText: String = System.getenv("NOWHERE_E2E_KEY") ?: "0123456789abcdef0123456789abcdef"

    /** Each Portal's own certificate, which both of its listeners serve. */
    private val pin: String? = System.getenv("NOWHERE_E2E_PIN")
    private val noMorphPin: String? = System.getenv("ORACLE_NOMORPH_PIN")

    /** A second Portal, the same in every respect except `morph=0`. */
    private val noMorphPortalEnv: String? = System.getenv("ORACLE_PORTAL_NOMORPH")
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
            portalEnv != null && output != null && pin != null && noMorphPortalEnv != null && noMorphPin != null,
        )
        val (targetHost, targetPort) = endpoint("ORACLE_TARGET")
        val (nameHost, namePort) = endpoint("ORACLE_TARGET_NAME")
        val (udpHost, udpPort) = endpoint("ORACLE_UDP")
        val (closedHost, closedPort) = endpoint("ORACLE_CLOSED")

        val (holdTlsHost, holdTlsPort) = endpoint("ORACLE_HOLD_TLS")
        val (holdQuicHost, holdQuicPort) = endpoint("ORACLE_HOLD_QUIC")

        val verdicts = LinkedHashMap<String, Verdict>()

        val carriers = Carriers.entries.filter { !it.quic || hostQuic != null }
        if (carriers.none { it.quic }) {
            println("oracle-diff: no host QUIC bridge, so the QUIC combinations are not run")
        }

        for (combination in carriers) {
            val prefix = combination.prefix
            val (host, port) = portalFor(combination)
            verdicts["${prefix}tcp_ip_payload"] =
                fetch(host, port, keyText, combination, ipTarget(targetHost, targetPort), targetHost)

            // The same fetch by name. Both implementations send a domain target
            // and the Portal resolves it, so this is remote resolution as the
            // wire sees it — not the device-side half, which needs a real TUN.
            verdicts["${prefix}tcp_domain_payload"] =
                fetch(host, port, keyText, combination, Target.ofDomain(nameHost, namePort), nameHost)

            // A port with nothing behind it. The Portal answers DIAL_FAILED.
            verdicts["${prefix}dial_failed"] =
                fetch(host, port, keyText, combination, ipTarget(closedHost, closedPort), closedHost)

            // A wrong key fails in two different places depending on Morph.
            // With it, the mask is keyed by the shared key, so the handshake
            // itself is noise to the Portal and nothing is ever authenticated.
            // Without it, TLS completes and the AuthFrame is what is refused.
            // Both are worth comparing, because they are different failures.
            verdicts["${prefix}wrong_key"] =
                fetch(host, port, WRONG_KEY, combination, ipTarget(targetHost, targetPort), targetHost)

            verdicts["${prefix}udp_round_trip"] = udpEcho(host, port, keyText, combination, udpHost, udpPort)
        }

        // How many connections carried sixteen simultaneous flows is counted
        // by the script, at the instant the origin releases them. Here only
        // that all sixteen completed with the same bytes.
        val (host, port) = portalFor(Carriers.Tls)
        verdicts["tls_burst"] = burst(host, port, Carriers.Tls, holdTlsHost, holdTlsPort)
        if (hostQuic != null) {
            verdicts["quic_burst"] = burst(host, port, Carriers.Quic, holdQuicHost, holdQuicPort)
        }

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
        combination: Carriers,
        target: DecodeResult<Target>,
        requestHost: String,
    ): Verdict {
        val resolved = (target as? DecodeResult.Ok)?.value ?: return Verdict(REPLY_GENERAL_FAILURE, "", "target rejected locally")
        return withFlow(portalHost, portalPort, key, combination, resolved, FlowKind.Tcp) { flow ->
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

    private fun portalFor(combination: Carriers): Pair<String, Int> {
        val value = if (combination.morph) portalEnv!! else noMorphPortalEnv!!
        return value.substringBeforeLast(':') to value.substringAfterLast(':').toInt()
    }

    /**
     * [BURST_WIDTH] flows opened at once, on **one** session.
     *
     * One session because that is the whole question: a session is what owns
     * the carrier, and flows spread across several sessions would each get
     * their own connection whatever the carrier did.
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
        combination: Carriers,
        host: String,
        port: Int,
    ): Verdict {
        val target = (ipTarget(host, port) as DecodeResult.Ok).value
        val held =
            try {
                session(portalHost, portalPort, keyText, combination)
            } catch (error: Exception) {
                return Verdict(REPLY_NETWORK_UNREACHABLE, "", "transport: ${error.message ?: error.javaClass.simpleName}")
            }
        val session = held.session
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
        combination: Carriers,
        host: String,
        port: Int,
    ): Verdict {
        val target = (ipTarget(host, port) as DecodeResult.Ok).value
        return withFlow(portalHost, portalPort, key, combination, target, FlowKind.Udp) { flow ->
            val payload = ByteArray(512) { (it * 31 + 7).toByte() }

            // Which framing a UDP flow wants is the carrier's business, not
            // this case's: over a QUIC carrier each packet is its own DATAGRAM,
            // over TLS it is length-prefixed inside a stream. Asking the flow
            // is the same question `UdpRelay` asks, and asking it the same way
            // is the point — a case that framed for one carrier and ran on the
            // other would report a divergence that is only its own assumption.
            if (flow is PacketFlow) {
                flow.sendPacket(payload)
                val echoed =
                    flow.receivePacket(SETUP_TIMEOUT_MILLIS.toLong())
                        ?: return@withFlow Verdict(REPLY_GENERAL_FAILURE, "", "no packet came back")
                Verdict(REPLY_SUCCEEDED, sha256(echoed), "READY, ${echoed.size} bytes back")
            } else {
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
    }

    /** Opens a flow, runs [body], and turns whatever happened into one verdict. */
    private fun withFlow(
        portalHost: String,
        portalPort: Int,
        key: String,
        combination: Carriers,
        target: Target,
        kind: FlowKind,
        body: (Flow) -> Verdict,
    ): Verdict {
        var held: Held? = null
        return try {
            // Inside the try: over QUIC the connection is the session's first
            // act, and a handshake that never completes — a wrong key under
            // Morph — is a transport failure like any other.
            held = session(portalHost, portalPort, key, combination)
            when (val opened = held.session.openFlow(target, kind)) {
                is DecodeResult.Ok -> opened.value.use(body)
                is DecodeResult.Invalid -> rejection(opened.reason)
            }
        } catch (error: Exception) {
            // A transport that failed before the protocol was reached.
            Verdict(REPLY_NETWORK_UNREACHABLE, "", "transport: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            held?.close()
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
            // the Portal said, and the Portal says the same thing over a TLS
            // connection and over a QUIC stream.
            is FlowRejected ->
                when (reason.result) {
                    SetupResult.InvalidRequest, SetupResult.FlowLimit ->
                        Verdict(REPLY_CONNECTION_NOT_ALLOWED, "", reason.result.name)

                    SetupResult.DialFailed -> Verdict(REPLY_HOST_UNREACHABLE, "", reason.result.name)
                    else -> Verdict(REPLY_GENERAL_FAILURE, "", reason.result.name)
                }

            // No setup byte: the Portal ended the stream or said nothing,
            // which is what a refused AuthFrame looks like from here.
            // Upstream's own client reads the same end of stream as a protocol
            // failure, reply 1, so the alphabets agree while the reasons stay
            // visible in the note.
            LaneReason.NoSetupByte, QuicCarrierReason.NoSetupByte ->
                Verdict(REPLY_GENERAL_FAILURE, "", "no setup byte")
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

    /**
     * A session on whichever carriers the combination names, and whatever has
     * to be closed with it.
     *
     * The QUIC connection is not owned by the session — several sessions could
     * share one — so it travels alongside and is closed by the same `use`.
     */
    private class Held(
        val session: NowhereSession,
        private val connection: QuicConnection?,
    ) : AutoCloseable {
        override fun close() {
            runCatching { session.close() }
            runCatching { connection?.close() }
        }
    }

    /** The node this case dials: the combination's Portal, pinned to its certificate. */
    private fun node(
        host: String,
        port: Int,
        key: String,
        combination: Carriers,
    ): NowhereUrl =
        NowhereUrl(
            sharedKey = (SharedKey.of(key) as DecodeResult.Ok).value,
            host = host,
            port = port,
            net = if (combination.quic) NextHopCarrier.Udp else NextHopCarrier.Tcp,
            morph = combination.morph,
            certificateVerification = CertificateVerification.Pin(if (combination.morph) pin!! else noMorphPin!!),
        )

    private fun session(
        host: String,
        port: Int,
        key: String,
        combination: Carriers,
    ): Held {
        val node = node(host, port, key, combination)
        if (!combination.quic) {
            val dialer = NowhereDialer(readTimeoutMillis = SETUP_TIMEOUT_MILLIS)
            return Held(
                NowhereSession(node.sharedKey, {
                    when (val transport = dialer.connect(node)) {
                        is DecodeResult.Ok -> transport.value
                        is DecodeResult.Invalid -> throw IllegalStateException(transport.reason.detail)
                    }
                }),
                null,
            )
        }

        val connection =
            QuicConnection.open(
                remote = InetSocketAddress(host, port),
                serverName = null,
                pinSha256 = (node.certificateVerification as CertificateVerification.Pin).bytes,
                morph = if (node.morph) MorphDatagram.forClient(MorphKeys.derive(node.sharedKey)) else null,
                // Nothing to protect: this half of the differential runs on a
                // build host, where there is no tunnel to route back into.
                protect = { true },
            )
        connection.completeHandshake()

        fun quicLane(): StreamTransport = QuicStreamTransport(connection, connection.openStream())

        return Held(
            NowhereSession(
                node.sharedKey,
                { error("a QUIC session must not dial TLS") },
                quicStreams = QuicCarrier.StreamFactory { quicLane() },
                quicDatagrams = connection,
            ),
            connection,
        )
    }

    private companion object {
        const val BLOB_PATH = "/blob.bin"

        /** The origin that answers nobody until [BURST_WIDTH] flows are waiting. */
        const val HOLD_PATH = "/hold"

        /**
         * Flows opened at once in the burst cases. Enough that a carrier which
         * quietly shared or failed to share connections is off by a lot rather
         * than by one, and small enough to stay a test rather than a load
         * generator.
         */
        const val BURST_WIDTH = 16

        /** In service form, so the failure is the key's and not its spelling. */
        const val WRONG_KEY = "ffffffffffffffffffffffffffffffff"

        /**
         * The Portal's own flow-setup limit. Shorter, and a slow DIAL_FAILED
         * would be read here as silence.
         */
        const val SETUP_TIMEOUT_MILLIS = 20_000

        const val CR: Byte = 13
        const val LF: Byte = 10

        // RFC 1928 section 6, and the values upstream's own mapping produces.
        const val REPLY_SUCCEEDED = 0
        const val REPLY_GENERAL_FAILURE = 1
        const val REPLY_CONNECTION_NOT_ALLOWED = 2
        const val REPLY_NETWORK_UNREACHABLE = 3
        const val REPLY_HOST_UNREACHABLE = 4
    }
}
