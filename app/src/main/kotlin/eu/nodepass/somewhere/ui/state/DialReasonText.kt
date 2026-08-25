// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.net.DialReason
import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.subscription.SubscriptionReason

/**
 * A dial failure, as a sentence in the reader's language.
 *
 * `DecodeReason.detail` exists for diagnostics: it is English, it names types
 * and fields, and it is written for whoever is reading a log. Putting it on a
 * node card — which is what the first wiring of this screen did — ships an
 * English developer string to a Chinese user and quietly makes every failure
 * message untranslatable.
 *
 * So the reason is mapped here, and the ALPN is passed as an argument rather
 * than embedded, keeping `docs/i18n.md`'s rule intact: the sentence translates,
 * the identifier does not.
 */
@Composable
fun DecodeReason.asMessage(): String =
    when (this) {
        is DialReason.Unreachable -> stringResource(R.string.dial_unreachable)
        is DialReason.HandshakeFailed -> stringResource(R.string.dial_handshake_failed, requestedAlpn)
        is DialReason.AlpnRejected -> stringResource(R.string.dial_alpn_rejected, requested)
        is DialReason.PinMismatch -> stringResource(R.string.dial_pin_mismatch)
        is DialReason.NoCertificate -> stringResource(R.string.dial_no_certificate)
        is DialReason.Unprotected -> stringResource(R.string.dial_unprotected)

        // A subscription failure is not a node failure, and rendering it as one
        // sends the reader to check a node that is fine. NW-D-04 in particular:
        // an empty feed is an exhausted subscription far more often than it is
        // a broken one, and it has to say so rather than "could not be reached".
        is SubscriptionReason.Transport -> stringResource(R.string.subscription_unreachable)
        is SubscriptionReason.HttpStatus -> stringResource(R.string.subscription_http_status, code)
        is SubscriptionReason.NoNodes -> stringResource(R.string.subscription_exhausted)
        is SubscriptionReason.Unusable -> stringResource(R.string.subscription_unreadable)

        // Anything neither layer raised itself — a decode failure from deeper
        // down. There is no honest sentence for a reason this layer does not
        // know, so it says the one thing it does know.
        else -> stringResource(R.string.dial_unknown)
    }
