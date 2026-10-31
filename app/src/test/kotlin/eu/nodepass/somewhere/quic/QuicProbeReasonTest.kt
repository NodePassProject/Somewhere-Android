// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import eu.nodepass.somewhere.net.DialReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicProbeReasonTest {
    @Test
    fun `a refused chain is a refusal`() {
        assertTrue(QuicProbe.reasonFor(QuicFailure.Untrusted("expired")) is DialReason.Untrusted)
    }

    @Test
    fun `a handshake that stalled on the pin is a refusal, not a dead Portal`() {
        val message = "the certificate does not match the pin; it presented ab12"
        assertTrue(QuicProbe.reasonFor(QuicFailure.HandshakeTimeout(message)) is DialReason.Untrusted)
        assertTrue(QuicProbe.reasonFor(QuicFailure.Transport(message)) is DialReason.Untrusted)
    }

    @Test
    fun `a handshake that stalled on nothing is unreachability`() {
        assertEquals(DialReason.Unreachable, QuicProbe.reasonFor(QuicFailure.HandshakeTimeout()))
        assertEquals(DialReason.Unreachable, QuicProbe.reasonFor(QuicFailure.Transport("reset")))
    }

    @Test
    fun `a socket that could not be protected says so`() {
        assertEquals(DialReason.Unprotected, QuicProbe.reasonFor(QuicFailure.NotProtected))
    }
}
