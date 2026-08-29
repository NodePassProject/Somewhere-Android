// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpstreamResolversTest {
    private val v4 = byteArrayOf(1, 1, 1, 1)
    private val otherV4 = byteArrayOf(8, 8, 8, 8)
    private val v6 =
        ByteArray(16).also {
            it[0] = 0x20
            it[1] = 0x01
        }

    @Test
    fun `the family the query arrived over is preferred`() {
        assertArrayEquals(v4, UpstreamResolvers.choose(listOf(v6, v4), addressLength = 4))
        assertArrayEquals(v6, UpstreamResolvers.choose(listOf(v4, v6), addressLength = 16))
    }

    @Test
    fun `the network's own order decides between two of the same family`() {
        assertArrayEquals(v4, UpstreamResolvers.choose(listOf(v4, otherV4), addressLength = 4))
    }

    @Test
    fun `a query arriving over one family is forwarded over the other rather than failed`() {
        // The case that matters, and the one that was broken: an IPv6-only
        // network declares only IPv6 resolvers, the tunnel announces both
        // families, and every query the interceptor declines arrived over
        // whichever the device picked. Refusing the mismatch answers SERVFAIL
        // to MX, SRV, PTR and HTTPS while A and AAAA keep working — which
        // reads as the site being broken.
        assertArrayEquals(v6, UpstreamResolvers.choose(listOf(v6), addressLength = 4))
        assertArrayEquals(v4, UpstreamResolvers.choose(listOf(v4), addressLength = 16))
    }

    @Test
    fun `a network that declared no resolver gets no answer rather than a guess`() {
        // SERVFAIL is the caller's response to this. Substituting a public
        // resolver would be a policy decision a tunnel has no business making.
        assertNull(UpstreamResolvers.choose(emptyList(), addressLength = 4))
        assertNull(UpstreamResolvers.choose(emptyList(), addressLength = 16))
    }
}
