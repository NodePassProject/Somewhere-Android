// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two rules a direct connection has to keep.
 *
 * Both are invisible on a JVM in the ordinary sense — there is no VpnService
 * here, so an unprotected socket connects perfectly well and a synthetic
 * address is just an address nobody answers. They are asserted structurally
 * instead: on what was called, in what order, and on what was refused before
 * anything was called at all.
 */
class DirectDialerTest {
    private fun target(
        host: String,
        port: Int,
    ) = Target.Domain(host, port)

    @Test
    fun theSocketIsProtectedBeforeItConnects() {
        // `protect` on a socket with no file descriptor protects nothing and
        // returns false, and after `connect` the routing decision has already
        // been made. Bind, protect, connect — in that order, always.
        ServerSocket(0).use { server ->
            val events = mutableListOf<String>()
            val socket =
                object : Socket() {
                    override fun connect(
                        endpoint: java.net.SocketAddress?,
                        timeout: Int,
                    ) {
                        events += "connect"
                        super.connect(endpoint, timeout)
                    }

                    override fun bind(bindpoint: java.net.SocketAddress?) {
                        events += "bind"
                        super.bind(bindpoint)
                    }
                }
            val dialer =
                DirectDialer(
                    protect = {
                        events += "protect"
                        true
                    },
                    open = { socket },
                )

            val outcome = dialer.connect(target("127.0.0.1", server.localPort))
            assertTrue("the connection should have opened", outcome is DecodeResult.Ok)
            assertEquals(listOf("bind", "protect", "connect"), events)
            (outcome as DecodeResult.Ok).value.close()
        }
    }

    @Test
    fun aSocketThatCannotBeProtectedIsNeverConnected() {
        // An unprotected direct socket routes back into the TUN, arrives at
        // lwIP, and is dialled again. The loop looks like a hang.
        val connected = AtomicBoolean(false)
        val socket =
            object : Socket() {
                override fun connect(
                    endpoint: java.net.SocketAddress?,
                    timeout: Int,
                ) {
                    connected.set(true)
                    super.connect(endpoint, timeout)
                }
            }
        val dialer = DirectDialer(protect = { false }, open = { socket })

        val outcome = dialer.connect(target("127.0.0.1", 9))
        assertTrue(outcome is DecodeResult.Invalid)
        assertEquals(DirectReason.NotProtected, (outcome as DecodeResult.Invalid).reason)
        assertFalse("nothing may be dialled on an unprotected socket", connected.get())
        assertTrue("and the socket must not be left open", socket.isClosed)
    }

    @Test
    fun aSyntheticAddressIsRefusedBeforeASocketExists() {
        // 198.18.0.0/15 exists only inside this device. Reaching the direct
        // path with one means a name was lost between the resolver and here,
        // and dialling it would produce a timeout instead of a defect report.
        var opened = false
        val dialer =
            DirectDialer(protect = { true }, open = {
                opened = true
                Socket()
            })
        val fake = FakeIpPool.ipv4(0)

        val outcome = dialer.connect((Target.ofIpv4(fake, 443) as DecodeResult.Ok).value)
        assertTrue(outcome is DecodeResult.Invalid)
        assertEquals(DirectReason.SyntheticAddress, (outcome as DecodeResult.Invalid).reason)
        assertFalse("no socket should have been opened at all", opened)
    }

    @Test
    fun aRealAddressIsNotMistakenForASyntheticOne() {
        assertFalse(DirectDialer.isSynthetic(byteArrayOf(198.toByte(), 17, 0, 1)))
        assertFalse(DirectDialer.isSynthetic(byteArrayOf(198.toByte(), 20, 0, 1)))
        assertTrue(DirectDialer.isSynthetic(FakeIpPool.ipv4(1)))
    }

    @Test
    fun aDestinationThatRefusesTheConnectionIsReportedRatherThanThrown() {
        // Port 9 with nothing behind it on loopback: the failure has to arrive
        // as a reason a caller can log, not as an exception out of the dialer.
        val dialer = DirectDialer(protect = { true }, connectTimeoutMillis = 500)
        val outcome = dialer.connect(target("127.0.0.1", 9))
        assertTrue(outcome is DecodeResult.Invalid)
        assertTrue((outcome as DecodeResult.Invalid).reason is DirectReason.DialFailed)
    }

    @Test
    fun bytesTravelBothWaysOverADirectFlow() {
        ServerSocket(0).use { server ->
            val echo =
                Thread {
                    server.accept().use { peer ->
                        val buffer = ByteArray(16)
                        val read = peer.getInputStream().read(buffer)
                        peer.getOutputStream().write(buffer, 0, read)
                        peer.getOutputStream().flush()
                    }
                }
            echo.start()

            val dialer = DirectDialer(protect = { true })
            val flow = (dialer.connect(target("127.0.0.1", server.localPort)) as DecodeResult.Ok).value
            flow.write("ping".toByteArray())
            flow.flush()
            val back = ByteArray(4)
            var filled = 0
            while (filled < 4) {
                val read = flow.read(back, filled, 4 - filled)
                if (read < 0) break
                filled += read
            }
            assertEquals("ping", String(back, 0, filled))
            flow.close()
            echo.join(5_000)
        }
    }
}
