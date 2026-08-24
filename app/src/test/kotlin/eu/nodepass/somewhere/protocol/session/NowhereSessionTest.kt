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

/** D1: the session owns what spans connections — the session id and the flow-id space. */
class NowhereSessionTest {
    private val key = (SharedKey.of("secret") as DecodeResult.Ok).value
    private val target = (Target.ofIpv4(byteArrayOf(127, 0, 0, 1), 443) as DecodeResult.Ok).value

    private val transports = mutableListOf<FakeTransport>()

    private fun session(peerReply: ByteArray = byteArrayOf(SetupResult.Ready.byte.toByte())) =
        NowhereSession(key, { FakeTransport(peerBytes = peerReply).also(transports::add) })

    @Test
    fun everySessionGetsItsOwnRandomIdentity() {
        // A predictable session id would let someone else's traffic be paired
        // with yours, since the Portal pairs split flows on it.
        val ids = (1..64).map { NowhereSession(key, { FakeTransport() }).id.toByteArray().toList() }
        assertEquals("session ids must not repeat", 64, ids.toSet().size)
    }

    @Test
    fun eachFlowGetsItsOwnConnectionAndItsOwnId() {
        val session = session()
        val first = (session.openFlow(target) as DecodeResult.Ok).value
        val second = (session.openFlow(target) as DecodeResult.Ok).value
        assertNotEquals(first.id, second.id)
        assertEquals("each flow opens its own lane at L1", 2, transports.size)
    }

    @Test
    fun theSessionIdIsTheSameOnEveryConnectionBeneathIt() {
        // The property the type exists for: a Portal pairs the halves of a split
        // flow on (session_id, flow_id), so the id must be identical across
        // connections that know nothing about each other.
        val session = session()
        session.openFlow(target)
        session.openFlow(target)
        val sent = transports.map { it.writtenBytes().copyOf(16).toList() }
        assertEquals("all connections carry one session id", 1, sent.toSet().size)
        assertEquals(session.id.toByteArray().toList(), sent.first())
    }

    @Test
    fun closingAFlowReturnsItsIdToTheSession() {
        val session = session()
        val flow = (session.openFlow(target) as DecodeResult.Ok).value
        assertEquals(1, session.liveFlowCount)
        flow.close()
        assertEquals(0, session.liveFlowCount)
    }

    @Test
    fun closingAFlowTwiceReleasesItsIdOnce() {
        val session = session()
        val flow = (session.openFlow(target) as DecodeResult.Ok).value
        flow.close()
        flow.close()
        assertEquals(0, session.liveFlowCount)
    }

    @Test
    fun aFailedOpenLeaksNeitherIdNorConnection() {
        // Retrying after a failure is the normal case, not the exception. A
        // session that leaked an id per failure would run out, and one that
        // leaked a socket would run out sooner.
        val session = session(peerReply = ByteArray(0)) // Portal closes without answering
        repeat(20) {
            val result = session.openFlow(target)
            assertTrue("open should fail", result is DecodeResult.Invalid)
        }
        assertEquals("no flow ids leaked", 0, session.liveFlowCount)
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
    fun theFirstPayloadRidesTheOpeningWrite() {
        val session = session()
        session.openFlow(target, firstPayload = "GET / HTTP/1.1\r\n".encodeToByteArray())
        val written = String(transports.single().writtenBytes())
        assertTrue("the caller's first bytes travel in the same write", written.endsWith("GET / HTTP/1.1\r\n"))
    }
}
