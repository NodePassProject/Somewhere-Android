// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

/**
 * Which of the device's own resolvers a declined query is forwarded to.
 *
 * The tunnel announces its own resolver addresses, which exist nowhere but
 * inside the TUN, so a query the interceptor declines cannot be forwarded to
 * where it was addressed. It goes to a resolver the underlying network
 * declared, read before the tunnel replaced them.
 *
 * **Family is a preference, not a requirement**, and that is the whole content
 * of this file. The reply is written back to the device from the address it
 * addressed, whatever family the forwarded query was dialled over, so the two
 * need not match. Requiring them to match fails whenever the underlying network
 * offers resolvers of one family and the tunnel announces both: on an
 * IPv6-only network that was every query this client declines — MX, SRV, PTR,
 * and the HTTPS records a browser asks for before it connects — answered
 * SERVFAIL while A and AAAA kept working. A failure shaped like that looks like
 * the site being broken, not like the tunnel.
 */
object UpstreamResolvers {
    /**
     * @param resolvers the underlying network's own, in the order it gave them.
     * @param addressLength 4 or 16: the family the query arrived over.
     * @return the resolver to dial, or null when the network declared none.
     */
    fun choose(
        resolvers: List<ByteArray>,
        addressLength: Int,
    ): ByteArray? = resolvers.firstOrNull { it.size == addressLength } ?: resolvers.firstOrNull()
}
