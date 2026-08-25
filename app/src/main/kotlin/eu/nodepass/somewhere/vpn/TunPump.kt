// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Moves packets between the TUN file descriptor and lwIP.
 *
 * **Everything that touches lwIP happens on one thread.** The stack is built
 * `NO_SYS=1`: it has no locks and no internal synchronisation, so a second
 * thread calling into it corrupts pcb lists rather than blocking on them, and
 * the symptom appears somewhere else entirely and much later. That constraint
 * is the whole reason this class exists as a thing rather than as two loops.
 *
 * Which forces the shape:
 *
 *  - **A reader thread** does the blocking `read` on the TUN and immediately
 *    hands the packet to the lwIP thread. It cannot call `nativeInput` itself.
 *  - **A single-threaded scheduled executor** is the lwIP thread. It runs
 *    `nativeInput`, the 100 ms timer, and every write back to the TUN.
 *
 * The reader cannot be folded into the lwIP thread even though that would need
 * one thread fewer: a blocking read would hold it for as long as the device is
 * idle, and `nativeTimerPoll` would stop firing. lwIP's retransmission and
 * TIME_WAIT live on that timer, so connections would wedge rather than fail —
 * the worst of the available failure modes, because nothing reports it.
 */
class TunPump(
    private val descriptor: ParcelFileDescriptor,
    private val handler: FlowHandler,
) {
    /**
     * What the pump does with the connections lwIP hands it.
     *
     * Separate from the pump because this is the seam where Nowhere arrives:
     * the pump's job ends at "an app on this device wants to reach this
     * address", and what happens next is a protocol decision.
     */
    interface FlowHandler {
        /**
         * A device app opened a TCP connection. Return a non-zero id to accept
         * it, or 0 to have lwIP refuse it.
         *
         * Called on the lwIP thread. Do not block: dialling a Portal takes
         * milliseconds at best and the timer is not running while this runs.
         */
        fun onTcpOpen(
            destination: ByteArray,
            port: Int,
            isIpv6: Boolean,
            pcb: Long,
        ): Long

        /** Payload from the device. A null [data] is the device's half-close. */
        fun onTcpPayload(
            id: Long,
            data: ByteArray?,
        )

        /** The device acknowledged [length] bytes. */
        fun onTcpAcknowledged(
            id: Long,
            length: Int,
        )

        /** The connection is gone; the pcb is already freed. */
        fun onTcpClosed(
            id: Long,
            error: Int,
        )

        /** A datagram from the device. */
        fun onUdpDatagram(
            source: ByteArray,
            sourcePort: Int,
            destination: ByteArray,
            destinationPort: Int,
            isIpv6: Boolean,
            data: ByteArray,
        )
    }

    private companion object {
        const val TAG = "TunPump"

        /** lwIP's own `TCP_TMR_INTERVAL`, in `port/lwipopts.h`. */
        const val TIMER_INTERVAL_MS = 100L

        /**
         * One read buffer, sized to the TUN's MTU plus room for the largest IP
         * header. A short read is a truncated packet, and lwIP would drop it on
         * the checksum without saying why.
         */
        const val READ_BUFFER = 65_536
    }

    private val running = AtomicBoolean(false)

    /**
     * The lwIP thread.
     *
     * `DiscardPolicy` rather than the default `AbortPolicy`: work submitted
     * after [stop] is work for a stack that no longer exists, and throwing
     * from the reader thread's `execute` at that moment would turn an ordinary
     * shutdown into a crash.
     */
    private val lwip: ScheduledExecutorService =
        Executors
            .newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "lwip").apply { isDaemon = true }
            }.also {
                (it as? ThreadPoolExecutor)?.rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy()
            }

    private var reader: Thread? = null
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    fun start() {
        if (!running.compareAndSet(false, true)) return

        NativeBridge.callback = bridgeCallback
        lwip.execute { NativeBridge.nativeInit() }
        lwip.scheduleWithFixedDelay(
            { NativeBridge.nativeTimerPoll() },
            TIMER_INTERVAL_MS,
            TIMER_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )

        reader =
            Thread({ readLoop() }, "tun-reader").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Stops the pump and closes the TUN.
     *
     * Order matters: the descriptor is closed first so the reader's blocking
     * `read` returns rather than waiting for a packet that will never come.
     * The reader treats that failure as the shutdown it is.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return

        runCatching { descriptor.close() }
        reader?.interrupt()
        reader = null

        lwip.execute {
            NativeBridge.nativeShutdown()
            // Only if it is still ours. The bridge's callback is process-wide,
            // so a pump that stops after its successor started would otherwise
            // silence the live one.
            if (NativeBridge.callback === bridgeCallback) NativeBridge.callback = null
        }
        lwip.shutdown()
        if (!lwip.awaitTermination(2, TimeUnit.SECONDS)) {
            // The stack is wedged in native code. Nothing useful can be done
            // about it here, but saying so beats a silent leak of a thread
            // that still holds lwIP's globals.
            Log.w(TAG, "the lwIP thread did not stop within 2s")
            lwip.shutdownNow()
        }
    }

    /** Queues a write toward the device. Safe to call from any thread. */
    fun writeToDevice(action: () -> Unit) {
        if (running.get()) lwip.execute(action)
    }

    /**
     * Runs [block] on the lwIP thread and returns its result.
     *
     * Needed wherever a caller has to *read* lwIP state — how much send buffer
     * is left, whether a write was accepted — because a value obtained off the
     * thread is a value that was already stale when it arrived.
     *
     * Returns null if the pump has stopped or the stack did not answer within
     * [timeoutMillis]. Null rather than an exception because the callers are
     * per-connection pumps whose correct response to a stopped stack is to
     * stop, not to unwind.
     */
    suspend fun <T> onStackThread(
        timeoutMillis: Long = 5_000,
        block: () -> T,
    ): T? {
        if (!running.get()) return null
        val result = CompletableDeferred<T>()
        // The executor discards work submitted after shutdown, which would
        // leave this waiting forever — hence the timeout rather than a plain
        // await.
        lwip.execute {
            runCatching(block).fold({ result.complete(it) }, { result.completeExceptionally(it) })
        }
        return withTimeoutOrNull(timeoutMillis) { result.await() }
    }

    /** A one-line description of an IP packet, for tracing. */
    private fun describe(packet: ByteArray): String {
        if (packet.isEmpty()) return "empty"
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4 || packet.size < 20) return "v$version ${packet.size}B"
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        fun address(offset: Int) = (0 until 4).joinToString(".") { (packet[offset + it].toInt() and 0xFF).toString() }

        fun port(offset: Int) = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val name =
            when (protocol) {
                6 -> "tcp"
                17 -> "udp"
                1 -> "icmp"
                else -> "p$protocol"
            }
        if (packet.size < headerLength + 4) return "$name ${address(12)} -> ${address(16)} ${packet.size}B"
        val flags = if (protocol == 6 && packet.size > headerLength + 13) " f=%02x".format(packet[headerLength + 13]) else ""
        return "$name ${address(12)}:${port(headerLength)} -> ${address(16)}:${port(headerLength + 2)}$flags ${packet.size}B"
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_BUFFER)
        while (running.get()) {
            val length =
                try {
                    input.read(buffer)
                } catch (_: IOException) {
                    // Expected on stop(): the descriptor was closed underneath
                    // the blocking read.
                    break
                }
            if (length <= 0) break

            val packet = buffer.copyOf(length)
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "in  ${describe(packet)}")
            lwip.execute { NativeBridge.nativeInput(packet, packet.size) }
        }
    }

    private val bridgeCallback =
        object : NativeBridge.LwipCallback {
            override fun onOutput(
                packet: ByteArray,
                length: Int,
                isIpv6: Boolean,
            ) {
                // Already on the lwIP thread. Writing here rather than posting
                // keeps ordering: two packets written out of order are a
                // reordering the device's TCP has to recover from, and the
                // whole point of an in-memory link is that it never reorders.
                try {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "out ${describe(packet.copyOf(length))}")
                    output.write(packet, 0, length)
                } catch (error: IOException) {
                    if (running.get()) Log.w(TAG, "write to TUN failed: ${error.message}")
                }
            }

            override fun onTcpAccept(
                srcIp: ByteArray,
                srcPort: Int,
                dstIp: ByteArray,
                dstPort: Int,
                isIpv6: Boolean,
                pcb: Long,
            ): Long = handler.onTcpOpen(dstIp, dstPort, isIpv6, pcb)

            override fun onTcpRecv(
                connId: Long,
                data: ByteArray?,
            ) = handler.onTcpPayload(connId, data)

            override fun onTcpSent(
                connId: Long,
                length: Int,
            ) = handler.onTcpAcknowledged(connId, length)

            override fun onTcpErr(
                connId: Long,
                err: Int,
            ) = handler.onTcpClosed(connId, err)

            override fun onUdpRecv(
                srcIp: ByteArray,
                srcPort: Int,
                dstIp: ByteArray,
                dstPort: Int,
                isIpv6: Boolean,
                data: ByteArray,
            ) = handler.onUdpDatagram(srcIp, srcPort, dstIp, dstPort, isIpv6, data)
        }
}
