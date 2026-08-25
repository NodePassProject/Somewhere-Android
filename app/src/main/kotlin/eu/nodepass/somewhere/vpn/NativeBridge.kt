// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors
//
// Derived from NodePassProject/Anywhere-Android at e9a9274 (GPL-3.0), with
// the BLAKE3 and libyaml entry points removed: this client speaks one
// protocol and reads no Clash configuration, so neither library is built.

package eu.nodepass.somewhere.vpn

/**
 * The Kotlin half of the lwIP bridge.
 *
 * Every declaration here is one end of a two-part contract whose other end is
 * a C symbol in `libsomewhere_native.so`. **Nothing in the toolchain checks
 * that the two agree.** A renamed method, a changed parameter type, a package
 * move — each compiles cleanly, ships, and throws `UnsatisfiedLinkError` the
 * first time a packet arrives, which on a VPN client is the moment the user
 * taps connect. `NativeBridgeSymbolTest` compares the two sides against the
 * built library so the mismatch is a build failure instead.
 *
 * **Threading.** lwIP is built `NO_SYS=1`: it has no locks and assumes a
 * single thread. Every `native*` call must therefore come from the same
 * thread, and every `on*` callback arrives on it. The C side attaches that
 * thread to the JVM on first use.
 */
object NativeBridge {
    init {
        System.loadLibrary("somewhere_native")
    }

    /**
     * lwIP's events, in the direction C → Kotlin.
     *
     * `onTcpAccept` returns a connection id the caller invents; every later
     * callback for that connection carries it back. Returning 0 refuses the
     * connection.
     */
    interface LwipCallback {
        /** An IP packet lwIP wants written to the TUN. */
        fun onOutput(
            packet: ByteArray,
            length: Int,
            isIpv6: Boolean,
        )

        /** A device app opened a TCP connection. Returns a connection id, or 0 to refuse. */
        fun onTcpAccept(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
            isIpv6: Boolean,
            pcb: Long,
        ): Long

        /** Payload from the device. A null [data] is the device's half-close. */
        fun onTcpRecv(
            connId: Long,
            data: ByteArray?,
        )

        /** The device acknowledged [length] bytes, freeing that much send buffer. */
        fun onTcpSent(
            connId: Long,
            length: Int,
        )

        /** The connection is gone. The pcb is already freed and must not be touched. */
        fun onTcpErr(
            connId: Long,
            err: Int,
        )

        /** A UDP datagram from the device. UDP is connectionless, so there is no id. */
        fun onUdpRecv(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
            isIpv6: Boolean,
            data: ByteArray,
        )
    }

    /**
     * Volatile because it is written by whoever starts the service and read
     * on the lwIP thread. Null means the stack is running with nowhere to
     * deliver, which happens for the moments between `nativeShutdown` and the
     * last in-flight callback.
     */
    @Volatile
    var callback: LwipCallback? = null

    // The six entry points JNI resolves by name and signature. They are
    // static because the C side holds a jclass, not an instance.

    @JvmStatic
    fun onOutput(
        packet: ByteArray,
        length: Int,
        isIpv6: Boolean,
    ) {
        callback?.onOutput(packet, length, isIpv6)
    }

    @JvmStatic
    fun onTcpAccept(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        isIpv6: Boolean,
        pcb: Long,
    ): Long = callback?.onTcpAccept(srcIp, srcPort, dstIp, dstPort, isIpv6, pcb) ?: 0L

    @JvmStatic
    fun onTcpRecv(
        connId: Long,
        data: ByteArray?,
    ) {
        callback?.onTcpRecv(connId, data)
    }

    @JvmStatic
    fun onTcpSent(
        connId: Long,
        length: Int,
    ) {
        callback?.onTcpSent(connId, length)
    }

    @JvmStatic
    fun onTcpErr(
        connId: Long,
        err: Int,
    ) {
        callback?.onTcpErr(connId, err)
    }

    @JvmStatic
    fun onUdpRecv(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        isIpv6: Boolean,
        data: ByteArray,
    ) {
        callback?.onUdpRecv(srcIp, srcPort, dstIp, dstPort, isIpv6, data)
    }

    /** Brings the stack up and registers the callbacks above. */
    @JvmStatic
    external fun nativeInit()

    /** Hands one IP packet read from the TUN to lwIP. */
    @JvmStatic
    external fun nativeInput(
        packet: ByteArray,
        length: Int,
    )

    /**
     * Runs lwIP's timers. Must be called regularly — roughly every 100 ms —
     * or retransmission and TIME_WAIT never fire and connections wedge rather
     * than fail.
     */
    @JvmStatic
    external fun nativeTimerPoll()

    /**
     * Aborts every TCP connection without taking the netif down.
     *
     * For the case where the device changed network or came back from sleep:
     * the sockets on the far side were killed by the kernel while lwIP's pcbs
     * survived, so they would sit there timing out one by one.
     */
    @JvmStatic
    external fun nativeAbortAllTcp()

    /** Takes the stack down. */
    @JvmStatic
    external fun nativeShutdown()

    /** Queues payload toward the device. Returns 0 on success, negative on error. */
    @JvmStatic
    external fun nativeTcpWrite(
        pcb: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /** Flushes what [nativeTcpWrite] queued. */
    @JvmStatic
    external fun nativeTcpOutput(pcb: Long)

    /**
     * Reopens [length] bytes of receive window.
     *
     * This is the back-pressure valve: until it is called for bytes taken from
     * [LwipCallback.onTcpRecv], the device's TCP window stays closed and the
     * app stops sending. Calling it too early turns the tunnel into an
     * unbounded queue.
     */
    @JvmStatic
    external fun nativeTcpRecved(
        pcb: Long,
        length: Int,
    )

    /** Closes cleanly — the device sees a FIN. */
    @JvmStatic
    external fun nativeTcpClose(pcb: Long)

    /** Closes abruptly — the device sees an RST. */
    @JvmStatic
    external fun nativeTcpAbort(pcb: Long)

    /** Send buffer space available, in bytes. Writing past it fails. */
    @JvmStatic
    external fun nativeTcpSndbuf(pcb: Long): Int

    /** Segments already queued on this connection. */
    @JvmStatic
    external fun nativeTcpSndQueuelen(pcb: Long): Int

    /** Sends a UDP datagram toward the device. */
    @JvmStatic
    external fun nativeUdpSendto(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        isIpv6: Boolean,
        data: ByteArray,
        length: Int,
    )

    /** Formats raw address bytes. Returns null if [addr] is not 4 or 16 bytes. */
    @JvmStatic
    external fun nativeIpToString(
        addr: ByteArray,
        isIpv6: Boolean,
    ): String?
}
