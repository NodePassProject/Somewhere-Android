// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertEquals
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.MessageDigest

/**
 * Brings the real tunnel up on the device and fetches through it.
 *
 * Shared by every device test that needs traffic to actually move, so that the
 * setup is written once — two copies of "start the service and wait" drift, and
 * the copy that drifts is the one that stops proving anything.
 */
object TunnelHarness {
    /** The large payload, where the size is the point. */
    const val PATH = "/blob.bin"

    /**
     * A small payload, for the cases that are about counting flows.
     *
     * Sixteen concurrent 20 MB fetches saturate an emulator and time out, which
     * says nothing about how many connections they used — the figure the test
     * exists to produce.
     */
    const val SMALL_PATH = "/small.bin"

    private const val STATE_TIMEOUT_MILLIS = 30_000L
    private const val HTTP_TIMEOUT_MILLIS = 60_000

    /** Long enough to be a real answer, short enough not to stall a suite. */
    private const val DIRECT_PROBE_MILLIS = 2_000

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun start() {
        val portal = E2eEnvironment.requirePortal()
        val host = portal.substringBeforeLast(':')
        val port = portal.substringAfterLast(':').toInt()
        // One case list, several carriers. That is where L2's only finding came
        // from — a protocol fact that had grown a second shape the moment a
        // second carrier landed — and QUIC is the third.
        //
        // `mux` is a TLS notion and is not sent with a QUIC node: QUIC
        // multiplexes by construction, so `mux=1` there would be a parameter
        // asking for something the carrier already is.
        val carrier = E2eEnvironment.carrier
        val mux = if (E2eEnvironment.mux && !E2eEnvironment.quic) "&mux=1" else ""
        val url = "nowhere://${E2eEnvironment.sharedKey}@$host:$port?up=$carrier&down=$carrier$mux"
        val node =
            when (val parsed = NowhereUrl.parse(url)) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid ->
                    throw AssertionError("the test node URL does not parse: ${parsed.reason.detail}")
            }

        refuseADirectlyReachableTarget()

        SomewhereVpnService.start(context, node)
        await("the tunnel to come up") { TunnelController.state.value is TunnelState.Connected }
    }

    /**
     * Refuses to run a tunnel case whose target this device can reach without a
     * tunnel.
     *
     * **This exists because the suite spent a day proving nothing.** Every case
     * here fetches over an ordinary socket from inside the app's own process,
     * and this client is forced out of its own tunnel in every mode — a VPN
     * inside its own tunnel is a routing loop, so `AppSelection.ruleFor` removes
     * it deliberately and there is no path that puts it back. The app's traffic
     * therefore never enters the TUN, and a fetch that succeeds proves only that
     * the destination was reachable some other way.
     *
     * It went unnoticed because the two things were introduced two runs apart
     * and the suite was not run in between: per-app selection landed on a day
     * when no device was attached, so the first execution of these cases after
     * it was against a host-local origin at `10.0.2.2`, which an emulator
     * reaches directly. Every case passed. The Portal's byte counters had not
     * moved.
     *
     * `e2e-fakeip.sh` avoids this by construction — its origin lives on a
     * container network the device cannot route to — but *by construction* is
     * not the same as *checked*, and nothing checked it. This does.
     */
    private fun refuseADirectlyReachableTarget() {
        val target = E2eEnvironment.target ?: return
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toInt()
        val reachable =
            runCatching {
                Socket().use { probe ->
                    probe.connect(InetSocketAddress(host, port), DIRECT_PROBE_MILLIS)
                    true
                }
            }.getOrDefault(false)

        if (reachable) {
            throw AssertionError(
                "this device can reach $target without a tunnel, so a case that fetches it " +
                    "from inside the app's own process would pass whether or not the tunnel " +
                    "carried anything — and this client is forced out of its own tunnel in " +
                    "every mode, so it would not. Use an origin the device cannot route to, " +
                    "which is what conformance/scripts/e2e-fakeip.sh provides.",
            )
        }
    }

    fun stop() {
        SomewhereVpnService.stop(context)
        await("the tunnel to stop") { TunnelController.state.value is TunnelState.Disconnected }
    }

    /**
     * Fetches [url] and returns the digest the server declared alongside the
     * one computed here, with the number of body bytes read.
     *
     * The origin publishes its own SHA-256 in a header, so a transfer truncated
     * on a block boundary is still caught — comparing against a length would
     * not catch it.
     */
    fun fetchAndDigest(url: String): Fetched {
        // NO_PROXY, deliberately. Emulators commonly carry a system HTTP proxy
        // — this one advertises 10.0.2.2:6152 — and `HttpURLConnection` honours
        // it while `curl` does not, which is why the same fetch can look broken
        // here and fine from a shell. Through a proxy the request goes to the
        // proxy's address and the name is never resolved on this device at all,
        // so the test would be measuring the emulator's configuration rather
        // than the tunnel.
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MILLIS
        connection.readTimeout = HTTP_TIMEOUT_MILLIS
        try {
            assertEquals("HTTP status from $url", 200, connection.responseCode)
            val declared =
                connection.getHeaderField("X-Content-Sha256")
                    ?: throw AssertionError("the origin server did not declare a digest")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            DataInputStream(connection.inputStream).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            return Fetched(
                declaredDigest = declared.lowercase(),
                computedDigest = digest.digest().joinToString("") { "%02x".format(it) },
                bodyBytes = total,
            )
        } finally {
            connection.disconnect()
        }
    }

    data class Fetched(
        val declaredDigest: String,
        val computedDigest: String,
        val bodyBytes: Long,
    )

    fun await(
        what: String,
        timeoutMillis: Long = STATE_TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what; tunnel state is ${TunnelController.state.value}")
    }
}
