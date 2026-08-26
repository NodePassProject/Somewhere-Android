// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.content.Context
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

/**
 * A name survives the whole way to the Portal, on a device. NW-P-05.
 *
 * The claim is narrow and it is the one L1 was missing: an app on this device
 * asks for a **name**, and what the Portal is asked to dial is that name rather
 * than an address the device resolved for itself. Everything else here exists
 * to make that claim falsifiable.
 *
 * ## Why this runs as instrumentation rather than as a shell script
 *
 * Android excludes uids 0, 1000 and 2000 from VPN routing — look at
 * `ip rule`, the ranges sent to `tun0` are 1..999, 1001..1999 and 2001..99999.
 * `adb shell` is uid 2000, so a `curl` from a shell **bypasses the tunnel and
 * proves nothing**, while looking exactly like a pass. Instrumentation runs in
 * the app's own process, so a socket opened here is a socket that goes through
 * the tunnel this test just started.
 *
 * ## What the origin name has to be
 *
 * Resolvable where the Portal runs and meaningless on this device. The
 * conformance script gets that from Docker: the origin server and the Portal
 * share a network, so the Portal's resolver knows the name and nothing on the
 * device does. A name the device could resolve would prove nothing at all —
 * the fetch would succeed with the fake-IP layer removed.
 */
@RunWith(AndroidJUnit4::class)
class FakeIpTunnelTest {
    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
        const val READ_TIMEOUT_MILLIS = 60_000
        const val PATH = "/blob.bin"
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun consentIsAlreadyGranted() {
        // The script pre-grants it with `appops`. Without it `establish()`
        // returns null and every assertion below fails for a reason that has
        // nothing to do with what is under test.
        assumeTrue(
            "VPN consent was not pre-granted; see conformance/scripts/e2e-fakeip.sh",
            VpnService.prepare(context) == null,
        )
    }

    @After
    fun stopTunnel() {
        SomewhereVpnService.stop(context)
        waitFor("the tunnel to stop") { TunnelController.state.value is TunnelState.Disconnected }
    }

    @Test
    fun aDomainFetchGoesOutAsANameAndComesBackIntact() {
        val origin = E2eEnvironment.requireOrigin()
        val expected = connectAndReadDigest(origin)

        // The Portal's log is checked by the script; what this asserts is the
        // half only the device can see — that the name resolved into the
        // synthetic range, so the flow that carried it was opened by name.
        val resolved = InetAddress.getByName(hostOf(origin)).address
        assertTrue(
            "${hostOf(origin)} resolved to ${resolved.joinToString(".") { (it.toInt() and 0xFF).toString() }}, " +
                "which is not in the synthetic range — the query was not intercepted",
            FakeIpPool.isFake(resolved),
        )
        assertEquals("the payload came back changed", expected.first, expected.second)
    }

    @Test
    fun anAddressLiteralStillWorks() {
        // The regression half. Fake-IP must not change what happens to a flow
        // that never involved a name.
        val target = E2eEnvironment.target
        assumeTrue("no literal target supplied", target != null)
        startTunnel()
        val (expected, actual) = fetchAndDigest("http://$target$PATH")
        assertEquals(expected, actual)
    }

    /** Brings the tunnel up and fetches through it. Returns (declared, computed). */
    private fun connectAndReadDigest(origin: String): Pair<String, String> {
        startTunnel()
        return fetchAndDigest("http://$origin$PATH")
    }

    private fun startTunnel() {
        val portal = E2eEnvironment.requirePortal()
        val host = portal.substringBeforeLast(':')
        val port = portal.substringAfterLast(':').toInt()
        val url = "nowhere://${E2eEnvironment.sharedKey}@$host:$port?up=tcp&down=tcp"
        val node =
            when (val parsed = NowhereUrl.parse(url)) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid -> throw AssertionError("test node URL is not parseable: ${parsed.reason.detail}")
            }

        SomewhereVpnService.start(context, node)
        waitFor("the tunnel to come up") { TunnelController.state.value is TunnelState.Connected }
    }

    /**
     * Fetches [url] and returns the digest the server declared alongside the
     * one computed here.
     *
     * The server publishes its own SHA-256 in a header, so a truncated transfer
     * that happens to end on a block boundary is still caught — comparing a
     * computed digest against a length would not be.
     */
    private fun fetchAndDigest(url: String): Pair<String, String> {
        // NO_PROXY, deliberately. Emulators commonly carry a system HTTP proxy
        // — this one advertises 10.0.2.2:6152 — and `HttpURLConnection` honours
        // it while `curl` does not, which is why the same fetch can look broken
        // here and fine from a shell. Through a proxy the request would go to
        // the proxy's address and the name would never be resolved on this
        // device at all, so the test would be measuring the emulator's
        // configuration instead of the tunnel.
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = READ_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        try {
            assertEquals("HTTP status from $url", 200, connection.responseCode)
            val declared =
                connection.getHeaderField("X-Content-Sha256")
                    ?: throw AssertionError("the origin server did not declare a digest")
            val digest = MessageDigest.getInstance("SHA-256")
            DataInputStream(connection.inputStream).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return declared.lowercase() to digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun hostOf(hostAndPort: String): String = hostAndPort.substringBeforeLast(':')

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what; state is ${TunnelController.state.value}")
    }
}
