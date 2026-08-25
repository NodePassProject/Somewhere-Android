// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The native library loads, resolves, and runs a TCP/IP stack on a device.
 *
 * `NativeBridgeSymbolTest` proves the two halves of the bridge *say* the same
 * thing. This proves they *are* the same thing, which is a different claim: a
 * JVM unit test cannot load an `.so` built for Android, so until something runs
 * on a device the entire C layer is unexecuted code that merely compiled.
 *
 * That distinction has already cost this project three defects — a Compose
 * classpath skew, a `fillMaxHeight` measuring to zero, and a missing `INTERNET`
 * permission — each of which was green in the build, green in lint, and green
 * across hundreds of unit tests.
 *
 * The test performs a whole three-way handshake, because **lwIP does not call
 * the accept callback on a SYN.** `tcp_listen_input` answers a SYN with a
 * SYN-ACK and leaves the new pcb in `SYN_RCVD`; `TCP_EVENT_ACCEPT` fires from
 * `tcp_process` only when the final ACK arrives. A test that sent a SYN and
 * waited for `onTcpAccept` would time out against a perfectly working stack —
 * which is exactly what the first version of this test did.
 *
 * Completing the handshake is also the stronger claim. Reaching `onTcpAccept`
 * requires that `System.loadLibrary` found the library, that `nativeInit`
 * resolved all six callbacks by name *and* signature, that `lwip_init`
 * succeeded, that the catch-all netif came up, that the wildcard listener on
 * port 0 matched a packet addressed to port 443, and that lwIP both read from
 * and wrote to the bridge.
 */
@RunWith(AndroidJUnit4::class)
class LwipStackIsAliveTest {
    private companion object {
        val CLIENT = byteArrayOf(10, 0, 0, 2)
        val SERVER = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
        const val CLIENT_PORT = 40000
        const val SERVER_PORT = 443
        const val CLIENT_ISN = 0x1000
    }

    private var started = false
    private val fromStack = ArrayBlockingQueue<ByteArray>(16)

    @After
    fun tearDown() {
        if (started) {
            NativeBridge.nativeShutdown()
            started = false
        }
        NativeBridge.callback = null
    }

    /** The internet checksum: one's complement of the one's-complement sum. */
    private fun onesComplement(bytes: ByteArray): Int {
        var sum = 0
        var index = 0
        while (index + 1 < bytes.size) {
            sum += ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < bytes.size) sum += (bytes[index].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    /**
     * One IPv4 TCP segment with both checksums filled in.
     *
     * Built rather than captured so the test states its own input. lwIP drops a
     * segment with a wrong checksum silently, which is indistinguishable from a
     * stack that never came up — so getting this right is load-bearing.
     */
    private fun segment(
        flags: Int,
        sequence: Long,
        acknowledgement: Long,
    ): ByteArray {
        val tcp =
            ByteArray(20).apply {
                this[0] = (CLIENT_PORT shr 8).toByte()
                this[1] = CLIENT_PORT.toByte()
                this[2] = (SERVER_PORT shr 8).toByte()
                this[3] = SERVER_PORT.toByte()
                writeUInt32(4, sequence)
                writeUInt32(8, acknowledgement)
                this[12] = 0x50 // data offset 5, no options
                this[13] = flags.toByte()
                this[14] = 0xFF.toByte()
                this[15] = 0xFF.toByte()
            }
        val pseudo =
            CLIENT + SERVER +
                byteArrayOf(0x00, 0x06) +
                byteArrayOf((tcp.size shr 8).toByte(), tcp.size.toByte())
        val tcpChecksum = onesComplement(pseudo + tcp)
        tcp[16] = (tcpChecksum shr 8).toByte()
        tcp[17] = tcpChecksum.toByte()

        val total = 20 + tcp.size
        val ip =
            ByteArray(20).apply {
                this[0] = 0x45 // IPv4, 20-byte header
                this[2] = (total shr 8).toByte()
                this[3] = total.toByte()
                this[5] = 0x01 // identification
                this[6] = 0x40 // don't fragment
                this[8] = 0x40 // TTL 64
                this[9] = 0x06 // TCP
                CLIENT.copyInto(this, 12)
                SERVER.copyInto(this, 16)
            }
        val ipChecksum = onesComplement(ip)
        ip[10] = (ipChecksum shr 8).toByte()
        ip[11] = ipChecksum.toByte()

        return ip + tcp
    }

    private fun ByteArray.writeUInt32(
        offset: Int,
        value: Long,
    ) {
        this[offset] = (value shr 24).toByte()
        this[offset + 1] = (value shr 16).toByte()
        this[offset + 2] = (value shr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.readUInt32(offset: Int): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or (this[offset + it].toLong() and 0xFF) }
        return value
    }

    private fun recordingCallback(onAccept: (ByteArray, Int, ByteArray, Int) -> Unit) =
        object : NativeBridge.LwipCallback {
            override fun onOutput(
                packet: ByteArray,
                length: Int,
                isIpv6: Boolean,
            ) {
                fromStack.offer(packet.copyOf(length))
            }

            override fun onTcpAccept(
                srcIp: ByteArray,
                srcPort: Int,
                dstIp: ByteArray,
                dstPort: Int,
                isIpv6: Boolean,
                pcb: Long,
            ): Long {
                onAccept(srcIp, srcPort, dstIp, dstPort)
                // A non-zero id accepts the connection; 0 makes lwIP abort it.
                return 1L
            }

            override fun onTcpRecv(
                connId: Long,
                data: ByteArray?,
            ) = Unit

            override fun onTcpSent(
                connId: Long,
                length: Int,
            ) = Unit

            override fun onTcpErr(
                connId: Long,
                err: Int,
            ) = Unit

            override fun onUdpRecv(
                srcIp: ByteArray,
                srcPort: Int,
                dstIp: ByteArray,
                dstPort: Int,
                isIpv6: Boolean,
                data: ByteArray,
            ) = Unit
        }

    @Test
    fun aHandshakeFromTheTunReachesAcceptCarryingTheAddressItWasSentTo() {
        val accepted = CountDownLatch(1)
        var seenDestination: ByteArray? = null
        var seenDestinationPort = -1
        var seenSourcePort = -1

        NativeBridge.callback =
            recordingCallback { _, srcPort, dstIp, dstPort ->
                seenDestination = dstIp
                seenDestinationPort = dstPort
                seenSourcePort = srcPort
                accepted.countDown()
            }

        NativeBridge.nativeInit()
        started = true

        // 1. SYN.
        send(segment(flags = 0x02, sequence = CLIENT_ISN.toLong(), acknowledgement = 0))

        // 2. The stack must answer with SYN-ACK. Nothing else is expected on
        //    this netif, so the first packet out is the answer.
        val synAck =
            fromStack.poll(5, TimeUnit.SECONDS)
                ?: error(
                    "the stack wrote nothing back within 5s. Either the library did not load, " +
                        "nativeInit failed to resolve a callback (it only logs and returns), or " +
                        "lwIP dropped the SYN — a wrong checksum looks identical to a stack that " +
                        "never came up.",
                )
        assertEquals("the answer is not TCP", 0x06.toByte(), synAck[9])
        assertEquals(
            "expected SYN|ACK from the listener, got flags 0x%02X".format(synAck[33].toInt() and 0xFF),
            0x12,
            synAck[33].toInt() and 0xFF,
        )

        // 3. ACK, which is what actually moves the pcb to ESTABLISHED and fires
        //    the accept callback. lwIP does not call accept on the SYN.
        val serverIsn = synAck.readUInt32(24)
        send(
            segment(
                flags = 0x10,
                sequence = CLIENT_ISN + 1L,
                acknowledgement = serverIsn + 1L,
            ),
        )

        assertTrue(
            "the handshake completed but onTcpAccept never fired, so the listener matched the " +
                "SYN and then failed to hand the connection over",
            accepted.await(5, TimeUnit.SECONDS),
        )

        // The destination survives the round trip through C. This is the value
        // the Nowhere flow layer will put in a Target, so a byte wrong here
        // means dialling somewhere else entirely.
        assertArrayEquals("the destination address reached Kotlin altered", SERVER, seenDestination)
        assertEquals("destination port", SERVER_PORT, seenDestinationPort)
        assertEquals("source port", CLIENT_PORT, seenSourcePort)
    }

    private fun send(packet: ByteArray) {
        NativeBridge.nativeInput(packet, packet.size)
        NativeBridge.nativeTimerPoll()
    }

    @Test
    fun addressFormattingCrossesTheBridge() {
        assertEquals("10.0.0.2", NativeBridge.nativeIpToString(CLIENT, false))
        assertEquals("93.184.216.34", NativeBridge.nativeIpToString(SERVER, false))
    }
}
