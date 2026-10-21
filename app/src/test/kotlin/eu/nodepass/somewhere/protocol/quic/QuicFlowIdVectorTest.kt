// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.quic

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.protocol.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives every vector in the `udpFlowId` family, `docs/protocol.md` § UDP flows.
 *
 * The vectors are upstream's, and they include the server-initiated streams an
 * Egress connection uses. This client only ever derives from its own streams,
 * so a server-initiated stream is checked to be *refused* here — the vector's
 * id is what an Egress would derive, not what a Vector may.
 */
class QuicFlowIdVectorTest {
    @Test
    fun everyClientVectorDerivesItsId() {
        val cases = VectorFixture.cases("udpFlowId")
        assertEquals("fixture should carry 5 udpFlowId cases", 5, cases.size)
        cases.forEach { case ->
            val streamId = case.str("streamId").toLong()
            val derived = QuicFlowId.forClientStream(streamId)
            when (case.str("initiator")) {
                "client" -> assertEquals("stream $streamId", case.str("flowId").toUInt(), derived.valueOrNull())
                "server" -> assertTrue("stream $streamId is not this client's", derived.reasonOrNull() is QuicFlowIdReason.WrongInitiator)
                else -> error("unknown initiator")
            }
        }
    }

    @Test
    fun everyInvalidStreamIsRefused() {
        val rejects = VectorFixture.rejects("udpFlowId")
        assertEquals(4, rejects.size)
        rejects.forEach { reject ->
            val streamId = reject.str("streamId").toLong()
            assertTrue("stream $streamId", QuicFlowId.forClientStream(streamId) is DecodeResult.Invalid)
        }
    }

    @Test
    fun theTypeBitsAreCheckedBeforeTheShift() {
        // 2 shifts to the same 0 as stream 0 does; it must not derive id 1.
        assertTrue(QuicFlowId.forClientStream(2).reasonOrNull() is QuicFlowIdReason.NotBidirectional)
        assertTrue(QuicFlowId.forClientStream(1).reasonOrNull() is QuicFlowIdReason.WrongInitiator)
    }

    @Test
    fun theLastUsableStreamIsTheLastOne() {
        assertEquals(QuicFlowId.MAX, QuicFlowId.forClientStream(QuicFlowId.LAST_CLIENT_STREAM).valueOrNull())
        assertTrue(
            QuicFlowId.forClientStream(QuicFlowId.LAST_CLIENT_STREAM + 4).reasonOrNull() is QuicFlowIdReason.Exhausted,
        )
    }

    @Test
    fun theAuthenticationStreamDerivesOneAndTheNextDerivesTwo() {
        // "Authentication-only Stream 0 creates no UDP route; the next client
        // Stream 4 derives ID 2."
        assertEquals(1u, QuicFlowId.forClientStream(0).valueOrNull())
        assertEquals(2u, QuicFlowId.forClientStream(4).valueOrNull())
    }
}
