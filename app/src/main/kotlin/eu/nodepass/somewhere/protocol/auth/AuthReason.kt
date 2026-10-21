// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.auth

import eu.nodepass.somewhere.protocol.DecodeReason

/** Why a shared key or an authentication frame was rejected. */
sealed interface AuthReason : DecodeReason {
    data object EmptySharedKey : AuthReason {
        override val detail: String = "shared key is empty; 1-255 bytes are required"
    }

    data class SharedKeyTooLong(
        val length: Int,
    ) : AuthReason {
        override val detail: String = "shared key is $length bytes; the maximum is 255"
    }

    data object MissingUserInfo : AuthReason {
        override val detail: String = "URL carries no userinfo, so there is no shared key"
    }

    data object PasswordComponentPresent : AuthReason {
        override val detail: String = "URL userinfo carries a password component; the shared key must stand alone"
    }

    /**
     * The key is not in the form every v3 role requires.
     *
     * Exactly 32 lowercase hexadecimal characters, used as text bytes. A key of
     * any other shape cannot match a v3 Portal's, because the Portal refuses to
     * start with one, so accepting it here would only move the failure to the
     * first connection, where it is silence rather than a sentence.
     */
    data object NotServiceFormat : AuthReason {
        override val detail: String =
            "shared key must be exactly 32 lowercase hexadecimal characters; generate one with `nowhere generate-key`"
    }

    data object MalformedPercentEncoding : AuthReason {
        override val detail: String = "URL userinfo contains a malformed percent escape"
    }

    data class FrameTruncated(
        val actual: Int,
    ) : AuthReason {
        override val detail: String = "authentication frame is $actual bytes; exactly 16 are required"
    }

    /**
     * The tag did not match.
     *
     * Carries no detail about *how* it failed. Over the wire a Portal must not
     * answer differently on authentication failure — a distinguishable response
     * is an oracle for active probing — and keeping that discipline in the type
     * means no caller can accidentally build one.
     */
    data object TagMismatch : AuthReason {
        override val detail: String = "authentication tag does not match"
    }
}
