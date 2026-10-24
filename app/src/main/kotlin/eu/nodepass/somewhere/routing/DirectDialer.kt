// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.routing

import eu.nodepass.somewhere.dns.FakeIpPool
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.session.Flow
import eu.nodepass.somewhere.protocol.session.PacketFlow
import eu.nodepass.somewhere.protocol.target.Target
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Why a direct connection did not happen. */
sealed interface DirectReason : DecodeReason {
    /** The rule set said so. Not a failure — a decision. */
    data class Rejected(
        val target: Target,
    ) : DirectReason {
        override val detail: String = "a rule rejects this destination"
    }

    /** The socket could not be kept out of the tunnel, so it was not opened. */
    data object NotProtected : DirectReason {
        override val detail: String =
            "the socket could not be protected, and an unprotected direct socket routes back into the tunnel"
    }

    /**
     * A synthetic address reached the direct path.
     *
     * Never a user's problem and always this client's: 198.18.0.0/15 exists
     * only inside this device, so dialling one directly reaches nothing. It
     * means a name was lost somewhere between the resolver and here.
     */
    data object SyntheticAddress : DirectReason {
        override val detail: String = "a synthetic address cannot be dialled directly"
    }

    data class DialFailed(
        val cause: String,
    ) : DirectReason {
        override val detail: String = "the destination could not be reached: $cause"
    }
}

/**
 * Opens a connection that leaves the device without touching the Portal.
 *
 * ## Two rules, both of which have teeth
 *
 * **The socket is protected before it connects, and the connection is refused
 * if it cannot be.** An unprotected socket routes back into the TUN, arrives at
 * lwIP, and is dialled again — a loop that looks like a hang and takes the
 * device with it. And `protect()` on a fresh socket protects nothing: a
 * `Socket` has no file descriptor until it is bound or connected, so the order
 * is bind, protect, connect, and after `connect` the routing decision has
 * already been made. That is recorded in the project's own list of things that
 * are easy to get wrong, having been got wrong once.
 *
 * **A synthetic address is never dialled.** A direct flow dials the *name*
 * the fake-IP layer was carrying; the address it arrived on exists only inside
 * this device. This is the invariant that replaces consulting the rule set a
 * second time in the resolver — see [Router] for why that design was not the
 * one taken.
 */
class DirectDialer(
    /** `VpnService.protect`, or something standing in for it. */
    private val protect: (Socket) -> Boolean,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val open: () -> Socket = ::Socket,
    /**
     * `VpnService.protect` for a datagram socket.
     *
     * Refuses by default, so a caller that forgot to pass one gets no direct
     * UDP at all rather than datagrams that loop back into the tunnel.
     */
    private val protectDatagram: (DatagramSocket) -> Boolean = { false },
    private val openDatagram: () -> DatagramSocket = { DatagramSocket(null) },
) {
    fun connect(target: Target): DecodeResult<Flow> {
        if (target is Target.Ip && isSynthetic(target.octets)) {
            return DecodeResult.Invalid(DirectReason.SyntheticAddress)
        }

        val socket = open()
        return try {
            // Bind first: without a file descriptor there is nothing to
            // protect and `protect` returns false having done nothing.
            socket.bind(InetSocketAddress(0))
            if (!protect(socket)) {
                socket.close()
                return DecodeResult.Invalid(DirectReason.NotProtected)
            }
            val address =
                when (target) {
                    is Target.Domain -> InetSocketAddress(target.host, target.port)
                    is Target.Ip -> InetSocketAddress(java.net.InetAddress.getByAddress(target.octets), target.port)
                }
            socket.connect(address, connectTimeoutMillis)
            DecodeResult.Ok(DirectFlow(target, socket))
        } catch (error: IOException) {
            runCatching { socket.close() }
            DecodeResult.Invalid(DirectReason.DialFailed(error.javaClass.simpleName))
        } catch (error: SecurityException) {
            runCatching { socket.close() }
            DecodeResult.Invalid(DirectReason.DialFailed(error.javaClass.simpleName))
        }
    }

    /**
     * A UDP association that leaves the device without touching the Portal.
     *
     * The same two rules as [connect]: protected before anything is sent, and
     * never to a synthetic address. A name is resolved here, on the network
     * underneath — this process is outside its own tunnel — which is what a
     * DIRECT rule for a name means.
     */
    fun datagram(target: Target): DecodeResult<PacketFlow> {
        if (target is Target.Ip && isSynthetic(target.octets)) {
            return DecodeResult.Invalid(DirectReason.SyntheticAddress)
        }

        val socket = openDatagram()
        return try {
            socket.bind(InetSocketAddress(0))
            if (!protectDatagram(socket)) {
                socket.close()
                return DecodeResult.Invalid(DirectReason.NotProtected)
            }
            val address =
                when (target) {
                    is Target.Domain -> InetAddress.getByName(target.host)
                    is Target.Ip -> InetAddress.getByAddress(target.octets)
                }
            // Connected, so the kernel drops datagrams from anyone else — the
            // reply has to come from the address the device wrote to.
            socket.connect(InetSocketAddress(address, target.port))
            DecodeResult.Ok(DirectPacketFlow(target, socket))
        } catch (error: IOException) {
            runCatching { socket.close() }
            DecodeResult.Invalid(DirectReason.DialFailed(error.javaClass.simpleName))
        } catch (error: SecurityException) {
            runCatching { socket.close() }
            DecodeResult.Invalid(DirectReason.DialFailed(error.javaClass.simpleName))
        }
    }

    companion object {
        /**
         * The same order of magnitude as a Portal dial, so a direct
         * destination that is down does not hang longer than a tunnelled one.
         */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000

        /**
         * Whether an address is one this device minted.
         *
         * Asked of [FakeIpPool] rather than answered here. The range is its
         * fact, and a second copy of it in this file would be right until the
         * day one of them moved.
         */
        fun isSynthetic(octets: ByteArray): Boolean = FakeIpPool.isFake(octets)
    }
}

/**
 * A plain socket wearing the same interface a tunnelled flow wears.
 *
 * So that everything downstream — the pumps, the back-pressure, the teardown —
 * is one code path rather than two. The parts of [Flow] that describe a Portal
 * are answered honestly: [setupResult] is READY because the connection is
 * established, and there is no Portal to have said so.
 */
private class DirectFlow(
    override val target: Target,
    private val socket: Socket,
) : Flow {
    override val id: UInt = 0u

    override val kind: FlowKind = FlowKind.Tcp

    override val setupResult: SetupResult = SetupResult.Ready

    override val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    private val output = socket.getOutputStream()
    private val input = socket.getInputStream()

    override fun write(bytes: ByteArray) = output.write(bytes)

    override fun flush() = output.flush()

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int = input.read(into, offset, length)

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * A protected UDP socket wearing the interface a tunnelled UDP flow wears.
 *
 * A [PacketFlow], so the relay's packet pumps carry it unchanged. The stream
 * half of [Flow] sends and receives one datagram per call, which nothing uses
 * but which is at least not a lie about what a datagram socket does.
 */
private class DirectPacketFlow(
    override val target: Target,
    private val socket: DatagramSocket,
) : PacketFlow {
    override val id: UInt = 0u

    override val kind: FlowKind = FlowKind.Udp

    override val setupResult: SetupResult = SetupResult.Ready

    override val isOpen: Boolean get() = !socket.isClosed

    private val buffer = ByteArray(MAX_DATAGRAM)

    override fun sendPacket(payload: ByteArray) {
        // Lossy, as the interface says: a datagram that cannot be sent is
        // dropped, which is what the device's own UDP socket would do.
        runCatching { socket.send(DatagramPacket(payload, payload.size)) }
    }

    override fun receivePacket(timeoutMillis: Long): ByteArray? {
        socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun write(bytes: ByteArray) = sendPacket(bytes)

    override fun flush() = Unit

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val packet = receivePacket(Long.MAX_VALUE) ?: return 0
        val count = minOf(packet.size, length)
        packet.copyInto(into, offset, 0, count)
        return count
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        /** The largest UDP payload over IPv4 or IPv6 without jumbograms. */
        const val MAX_DATAGRAM = 65_535
    }
}
