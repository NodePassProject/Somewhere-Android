// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl

/**
 * The one rewrite the app is allowed to make to a node, and only when told to.
 *
 * NW-P-25 is often misread as "never change a pasted configuration". It is
 * narrower and more useful than that: never change it **on the user's behalf**.
 * Upstream defaults both directions to `udp`, so a pasted default needs QUIC,
 * which has not shipped — and a client that silently rewrote it to `tcp` would
 * connect successfully while lying about what the user asked for, which is the
 * failure the requirement exists to prevent. A client that refuses and explains,
 * offering the rewrite as one of two choices, satisfies it.
 *
 * This lives in one named function rather than inline in a click handler so
 * that what it changes — and, more importantly, what it leaves alone — is
 * stated once and tested.
 */
fun NowhereUrl.switchedToTcp(): NowhereUrl = copy(up = NextHopCarrier.Tcp, down = NextHopCarrier.Tcp)
