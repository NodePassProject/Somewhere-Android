// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.session.NowhereSession
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.routing.DirectDialer
import eu.nodepass.somewhere.routing.Router
import eu.nodepass.somewhere.routing.RoutingMode
import eu.nodepass.somewhere.routing.RoutingRules
import eu.nodepass.somewhere.routing.RuleDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP is routed by the same rules as TCP.
 *
 * It once was not: every datagram went to the Portal whatever the mode or the
 * rules said, so HTTP/3 to a DIRECT site and UDP to the LAN left through the
 * Portal. On a device because the relay logs through `android.util.Log`, which
 * a JVM test here cannot call. No Portal is needed: the session counts its
 * dials and refuses them, so "the Portal was never asked" is a number.
 */
@RunWith(AndroidJUnit4::class)
class UdpRoutingOnDeviceTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dials = AtomicInteger(0)

    private val session =
        NowhereSession(
            sharedKey =
                (NowhereUrl.parse("nowhere://0123456789abcdef0123456789abcdef@portal.invalid:443") as DecodeResult.Ok)
                    .value.sharedKey,
            connect = {
                dials.incrementAndGet()
                error("no Portal in this test")
            },
        )

    @After
    fun stop() {
        scope.cancel()
    }

    private fun relay(
        rules: String,
        mode: RoutingMode = RoutingMode.Rules,
    ): UdpRelay {
        val parsed = RuleDocument.parse(rules).getOrThrow()
        val set = RoutingRules.of(parsed.rules).getOrThrow()
        return UdpRelay(
            session,
            FakeIpPool(),
            TrafficMeter { System.nanoTime() / 1_000_000 },
            { null },
            scope,
            router = Router({ listOf(set) }, { mode }),
            direct = DirectDialer(protect = { true }, protectDatagram = { true }),
        )
    }

    private val device = byteArrayOf(10, 7, 0, 2)
    private val loopback = byteArrayOf(127, 0, 0, 1)

    @Test
    fun aDirectRuleSendsTheDatagramStraightOutAndNeverToThePortal() {
        DatagramSocket(InetSocketAddress(InetAddress.getByAddress(loopback), 0)).use { echo ->
            echo.soTimeout = 5_000
            val payload = "straight out".toByteArray()
            val offered =
                relay("IP-CIDR,127.0.0.0/8,DIRECT")
                    .offer(device, 40_000, loopback, echo.localPort, false, payload)
            assertTrue("the relay should have taken the datagram", offered)

            val received = DatagramPacket(ByteArray(64), 64)
            echo.receive(received)
            assertArrayEquals(payload, received.data.copyOf(received.length))
            assertEquals("a DIRECT datagram must not dial the Portal", 0, dials.get())
        }
    }

    @Test
    fun aRejectRuleDropsTheDatagram() {
        val offered = relay("IP-CIDR,127.0.0.0/8,REJECT").offer(device, 40_001, loopback, 9, false, byteArrayOf(1))
        assertFalse("a rejected datagram should be dropped", offered)
        Thread.sleep(200)
        assertEquals(0, dials.get())
    }

    @Test
    fun everythingModeStillSendsItToThePortal() {
        val offered =
            relay("IP-CIDR,127.0.0.0/8,DIRECT", RoutingMode.Everything)
                .offer(device, 40_002, loopback, 9, false, byteArrayOf(1))
        assertTrue(offered)
        val deadline = System.currentTimeMillis() + 5_000
        while (dials.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("Everything mode should have asked the Portal", 1, dials.get())
    }
}
