// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import eu.nodepass.somewhere.protocol.frame.FlowKind
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import java.io.Closeable

/**
 * One logical flow: a single connection to one target, from the caller's side.
 *
 * A flow is not a carrier. On a dedicated lane the two are one-to-one; on a Mux
 * carrier or a QUIC connection many flows share one. Callers hold flows and never
 * carriers, so the same code works across all three.
 */
interface Flow : Closeable {
    val id: UInt

    val target: Target

    val kind: FlowKind

    /**
     * The Portal's answer, once it has arrived.
     *
     * Null until the setup byte is read. On a split flow only the downstream
     * half receives it, and that result is authoritative for the whole logical
     * flow (NW-P-06).
     */
    val setupResult: SetupResult?

    val isOpen: Boolean

    fun write(bytes: ByteArray)

    fun flush()

    /** @return bytes read, or -1 at clean end of stream. */
    fun read(
        into: ByteArray,
        offset: Int = 0,
        length: Int = into.size - offset,
    ): Int
}

/**
 * How a flow's two directions are arranged.
 *
 * Not the same as [FlowCarrier]: this is the *shape* — one carrier or two — while
 * the carrier is *which transport* each direction rides. NW-P-04 ties them
 * together: DUPLEX requires the same carrier in both directions, OPEN and ATTACH
 * require different ones.
 */
sealed interface FlowShape {
    /** Both directions on one carrier. */
    data class Duplex(
        val carrier: FlowCarrier,
    ) : FlowShape

    /** Upstream and downstream on different carriers, paired by the Portal. */
    data class Split(
        val up: FlowCarrier,
        val down: FlowCarrier,
    ) : FlowShape

    companion object {
        /**
         * Chooses the shape from a node's configured carriers.
         *
         * The whole reason this type exists: `up=tcp&down=udp` is a normal
         * configuration, and it means two physical connections that the Portal
         * pairs on `(session_id, flow_id)`.
         */
        fun of(
            up: FlowCarrier,
            down: FlowCarrier,
        ): FlowShape = if (up == down) Duplex(up) else Split(up, down)
    }
}
