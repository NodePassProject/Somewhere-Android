// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NW-P-25's rewrite: what it changes, and everything it must not.
 */
class NodeActionsTest {
    private fun node(url: String) = (NowhereUrl.parse(url) as DecodeResult.Ok).value

    private val defaults =
        node(
            "nowhere://a-key@fra04.example.net:8443?up=udp&down=udp&mux=1" +
                "&alpn=custom%2F1&sni=real.example.net&rate=50&etar=20#Frankfurt",
        )

    @Test
    fun bothDirectionsBecomeTcp() {
        val switched = defaults.switchedToTcp()
        assertEquals(NextHopCarrier.Tcp, switched.up)
        assertEquals(NextHopCarrier.Tcp, switched.down)
        assertFalse("the whole point is that it no longer needs QUIC", switched.requiresQuic)
    }

    @Test
    fun nothingElseAboutTheNodeChanges() {
        // The rewrite the user consented to is "use TCP". Anything else that
        // moved would be a change they did not ask for, in the one place the
        // requirement is specifically about not doing that.
        val switched = defaults.switchedToTcp()
        assertEquals(defaults.sharedKey, switched.sharedKey)
        assertEquals(defaults.host, switched.host)
        assertEquals(defaults.port, switched.port)
        assertEquals(defaults.mux, switched.mux)
        assertEquals(defaults.alpn, switched.alpn)
        assertEquals(defaults.certificateVerification, switched.certificateVerification)
        assertEquals(defaults.rateMbps, switched.rateMbps)
        assertEquals(defaults.etarMbps, switched.etarMbps)
        assertEquals(defaults.displayName, switched.displayName)
    }

    @Test
    fun aHalfQuicNodeIsFullyConverted() {
        // up=tcp&down=udp still needs QUIC, because one direction does.
        val split = node("nowhere://k@h.example.net:443?up=tcp&down=udp")
        assertTrue(split.requiresQuic)
        assertFalse(split.switchedToTcp().requiresQuic)
    }

    @Test
    fun switchingANodeThatAlreadyUsesTcpChangesNothingAtAll() {
        // Equality matters here rather than just carrier equality: the node list
        // writes the result back, and a "change" that is not one would rewrite
        // the file and reorder nothing usefully.
        val already = node("nowhere://k@h.example.net:443?up=tcp&down=tcp")
        assertEquals(already, already.switchedToTcp())
    }

    @Test
    fun theRewriteSurvivesBeingWrittenAndReadBack() {
        // It is stored as text, so a rewrite that does not round-trip is a
        // rewrite that half-happens.
        val switched = defaults.switchedToTcp()
        assertEquals(switched, node(switched.toUrl()))
    }
}
