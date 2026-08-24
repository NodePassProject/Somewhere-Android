// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.auth

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Which carrier an authentication tag is bound to.
 *
 * The transport byte is part of the tag input, so a tag computed for a TLS
 * connection cannot be replayed on a QUIC one.
 */
enum class AuthTransport(
    val byte: Byte,
) {
    TlsTcp(0x01),
    Quic(0x02),
}

/**
 * Connection authentication, per `docs/protocol.md` section 2.
 *
 * ```
 * salt      = SHA256("nowhere/now/1/auth-root")
 * auth_root = HMAC-SHA256(salt, shared_key)
 * auth_key  = HMAC-SHA256(auth_root, "authentication" || 0x01)
 * tag       = HMAC-SHA256(auth_key, transport || exporter || session_id)[0..16]
 * ```
 *
 * The exporter binds the tag to one specific TLS or QUIC connection, so a
 * captured frame cannot be replayed onto another. Authentication has no response
 * frame: a Portal that answered differently on failure would be an oracle for
 * active probing.
 */
object Authentication {
    const val SESSION_ID_LENGTH: Int = 16
    const val TAG_LENGTH: Int = 16
    const val FRAME_LENGTH: Int = SESSION_ID_LENGTH + TAG_LENGTH
    const val EXPORTER_LENGTH: Int = 32

    /** RFC 8446 exporter label. Fixed by the protocol and unchanged by a custom ALPN. */
    const val EXPORTER_LABEL: String = "EXPORTER-Nowhere-Auth"

    private const val AUTH_ROOT_SALT_LABEL = "nowhere/now/1/auth-root"
    private const val AUTH_KEY_INFO = "authentication"
    private const val HMAC_SHA256 = "HmacSHA256"

    /**
     * Derives the connection-independent authentication key from a shared key.
     *
     * This is HKDF-SHA256 with the expansion loop unrolled: the requested output
     * is exactly one SHA-256 block, so HKDF-Expand reduces to a single
     * `HMAC(prk, info || 0x01)`.
     */
    fun deriveAuthKey(sharedKey: SharedKey): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(AUTH_ROOT_SALT_LABEL.encodeToByteArray())
        val authRoot = hmac(salt, sharedKey.toByteArray())
        return hmac(authRoot, AUTH_KEY_INFO.encodeToByteArray() + 0x01)
    }

    /**
     * Computes the 16-byte tag binding this session to this connection.
     *
     * @param exporter the 32 bytes exported from the TLS or QUIC connection.
     */
    fun tag(
        authKey: ByteArray,
        transport: AuthTransport,
        exporter: ByteArray,
        sessionId: ByteArray,
    ): ByteArray {
        require(exporter.size == EXPORTER_LENGTH) {
            "exporter must be $EXPORTER_LENGTH bytes, got ${exporter.size}"
        }
        require(sessionId.size == SESSION_ID_LENGTH) {
            "session id must be $SESSION_ID_LENGTH bytes, got ${sessionId.size}"
        }
        val message = ByteArray(1 + EXPORTER_LENGTH + SESSION_ID_LENGTH)
        message[0] = transport.byte
        exporter.copyInto(message, 1)
        sessionId.copyInto(message, 1 + EXPORTER_LENGTH)
        return hmac(authKey, message).copyOf(TAG_LENGTH)
    }

    /** Builds the 32-byte frame: `session_id[16] || tag[16]`. */
    fun encodeFrame(
        sharedKey: SharedKey,
        transport: AuthTransport,
        exporter: ByteArray,
        sessionId: ByteArray,
    ): ByteArray {
        val tag = tag(deriveAuthKey(sharedKey), transport, exporter, sessionId)
        return sessionId.copyOf(SESSION_ID_LENGTH) + tag
    }

    /**
     * Verifies a frame and returns the session id it carries.
     *
     * The tag comparison is constant-time. A short-circuiting comparison would
     * leak how many leading bytes matched, which is enough to forge a tag one
     * byte at a time.
     */
    fun verifyFrame(
        frame: ByteArray,
        sharedKey: SharedKey,
        transport: AuthTransport,
        exporter: ByteArray,
    ): DecodeResult<ByteArray> {
        if (frame.size != FRAME_LENGTH) return invalid(AuthReason.FrameTruncated(frame.size))
        val sessionId = frame.copyOfRange(0, SESSION_ID_LENGTH)
        val presented = frame.copyOfRange(SESSION_ID_LENGTH, FRAME_LENGTH)
        val expected = tag(deriveAuthKey(sharedKey), transport, exporter, sessionId)
        return if (MessageDigest.isEqual(expected, presented)) {
            sessionId.ok()
        } else {
            invalid(AuthReason.TagMismatch)
        }
    }

    private fun hmac(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray =
        Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(key, HMAC_SHA256))
            doFinal(message)
        }
}
