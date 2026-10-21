// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.session

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session over TLS: one connection per flow, and nothing shared between them. */
class NowhereSessionTest {
    private val key = (SharedKey.of("0123456789abcdef0123456789abcdef") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    private val transports = mutableListOf<FakeTransport>()

    private fun session(peerReply: ByteArray = byteArrayOf(SetupResult.Ready.byte.toByte())) =
        NowhereSession(key, { FakeTransport(peerBytes = peerReply).also(transports::add) })

    @Test
    fun eachFlowGetsItsOwnConnectionAndItsOwnId() {
        val session = session()
        val first = (session.openFlow(target) as DecodeResult.Ok).value
        val second = (session.openFlow(target) as DecodeResult.Ok).value
        assertNotEquals(first.id, second.id)
        assertEquals("each flow opens its own connection over TLS", 2, transports.size)
        assertEquals(2, session.carrierCount)
    }

    @Test
    fun noConnectionCarriesAnythingFromAnother() {
        // v1 put one session id on every connection so a Portal could pair
        // them. v3 has nothing to pair: each connection's AuthFrame is bound to
        // its own exporter, and two connections with different exporters must
        // open with different bytes.
        val exporters = listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        var next = 0
        val session =
            NowhereSession(key, {
                FakeTransport(exporter = exporters[next++], peerBytes = byteArrayOf(0)).also(transports::add)
            })
        session.openFlow(target)
        session.openFlow(target)
        val openings = transports.map { it.writtenBytes().copyOf(16).toList() }
        assertEquals("two connections, two AuthFrames", 2, openings.toSet().size)
    }

    @Test
    fun aClosedFlowReleasesItsConnection() {
        val session = session()
        val flow = (session.openFlow(target) as DecodeResult.Ok).value
        assertEquals(1, session.carrierCount)
        flow.close()
        assertEquals(0, session.carrierCount)
    }

    @Test
    fun closingAFlowLowersTheLiveCount() {
        val session = session()
        val flow = (session.openFlow(target) as DecodeResult.Ok).value
        assertEquals(1, session.liveFlowCount)
        flow.close()
        assertEquals(0, session.liveFlowCount)
    }

    @Test
    fun closingAFlowTwiceCountsOnce() {
        val session = session()
        val flow = (session.openFlow(target) as DecodeResult.Ok).value
        flow.close()
        flow.close()
        assertEquals(0, session.liveFlowCount)
    }

    @Test
    fun aFailedOpenLeaksNeitherIdNorConnection() {
        // Retrying after a failure is the normal case, not the exception. A
        // session that leaked a socket per failure would run out of them.
        val session = session(peerReply = ByteArray(0)) // Portal closes without answering
        repeat(20) {
            val result = session.openFlow(target)
            assertTrue("open should fail", result is DecodeResult.Invalid)
        }
        assertEquals("no flows counted", 0, session.liveFlowCount)
        assertEquals("no connections held", 0, session.carrierCount)
        assertTrue("every failed lane's transport was closed", transports.none { it.isOpen })
    }

    @Test
    fun aRejectionIsReportedWithItsReason() {
        val session = session(peerReply = byteArrayOf(SetupResult.FlowLimit.byte.toByte()))
        val reason = session.openFlow(target).reasonOrNull()
        assertTrue(reason is LaneReason.Rejected)
        assertEquals(SetupResult.FlowLimit, (reason as LaneReason.Rejected).result)
    }

    @Test
    fun closingTheSessionClosesEverythingUnderIt() {
        val session = session()
        session.openFlow(target)
        session.openFlow(target)
        session.close()
        assertTrue("every transport closes with the session", transports.none { it.isOpen })
    }

    @Test
    fun openingOnAClosedSessionIsARefusalNotAQuietFailure() {
        val session = session()
        session.close()
        val failure = runCatching { session.openFlow(target) }
        assertTrue("a closed session must refuse loudly", failure.isFailure)
    }

    @Test
    fun theFirstPayloadIsSentOnceThePortalAnswers() {
        val session = session()
        session.openFlow(target, firstPayload = "GET / HTTP/1.1\r\n".encodeToByteArray())
        val transport = transports.single()
        val written = String(transport.writtenBytes())
        assertTrue("the caller's first bytes are sent", written.endsWith("GET / HTTP/1.1\r\n"))
        assertTrue(
            "and none of them before READY",
            transport.writtenBeforeFirstRead!! <= transport.writtenBytes().size - "GET / HTTP/1.1\r\n".length,
        )
    }
}
