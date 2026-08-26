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
import java.net.Proxy
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
    const val PATH = "/blob.bin"

    private const val STATE_TIMEOUT_MILLIS = 30_000L
    private const val HTTP_TIMEOUT_MILLIS = 60_000

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun start() {
        val portal = E2eEnvironment.requirePortal()
        val host = portal.substringBeforeLast(':')
        val port = portal.substringAfterLast(':').toInt()
        val url = "nowhere://${E2eEnvironment.sharedKey}@$host:$port?up=tcp&down=tcp"
        val node =
            when (val parsed = NowhereUrl.parse(url)) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid ->
                    throw AssertionError("the test node URL does not parse: ${parsed.reason.detail}")
            }

        SomewhereVpnService.start(context, node)
        await("the tunnel to come up") { TunnelController.state.value is TunnelState.Connected }
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
