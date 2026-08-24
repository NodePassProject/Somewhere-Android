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

    data object MalformedPercentEncoding : AuthReason {
        override val detail: String = "URL userinfo contains a malformed percent escape"
    }

    data class FrameTruncated(
        val actual: Int,
    ) : AuthReason {
        override val detail: String = "authentication frame is $actual bytes; exactly 32 are required"
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
