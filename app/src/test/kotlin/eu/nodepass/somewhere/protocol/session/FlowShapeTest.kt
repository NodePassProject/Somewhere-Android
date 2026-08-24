// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.frame.FlowCarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NW-P-04, the rule that makes split-direction transport work.
 *
 * Same carrier both ways means one connection and DUPLEX; different carriers
 * mean two connections the Portal pairs on `(session_id, flow_id)`. Getting this
 * backwards would produce a client that appears to work on `tcp/tcp` and fails
 * on every asymmetric configuration — which is the configuration this protocol
 * exists for.
 */
class FlowShapeTest {
    @Test
    fun matchingCarriersMeanOneConnection() {
        assertEquals(
            FlowShape.Duplex(FlowCarrier.TlsTcp),
            FlowShape.of(FlowCarrier.TlsTcp, FlowCarrier.TlsTcp),
        )
        assertEquals(
            FlowShape.Duplex(FlowCarrier.Quic),
            FlowShape.of(FlowCarrier.Quic, FlowCarrier.Quic),
        )
    }

    @Test
    fun differingCarriersMeanTwoConnectionsThePortalPairs() {
        val shape = FlowShape.of(FlowCarrier.TlsTcp, FlowCarrier.Quic)
        assertTrue(shape is FlowShape.Split)
        assertEquals(FlowCarrier.TlsTcp, (shape as FlowShape.Split).up)
        assertEquals(FlowCarrier.Quic, shape.down)
    }

    @Test
    fun theSplitRemembersWhichDirectionIsWhich() {
        // Not symmetric: up=tcp,down=quic is a different configuration from
        // up=quic,down=tcp, and confusing them sends the halves to the wrong
        // transports.
        val one = FlowShape.of(FlowCarrier.TlsTcp, FlowCarrier.Quic)
        val other = FlowShape.of(FlowCarrier.Quic, FlowCarrier.TlsTcp)
        assertTrue(one != other)
    }

    @Test
    fun everyCarrierCombinationProducesAShape() {
        // Four combinations, all legal — that is the whole point of the protocol.
        val shapes =
            FlowCarrier.entries.flatMap { up ->
                FlowCarrier.entries.map { down -> FlowShape.of(up, down) }
            }
        assertEquals(4, shapes.size)
        assertEquals("two are duplex, two are split", 2, shapes.count { it is FlowShape.Duplex })
    }
}
