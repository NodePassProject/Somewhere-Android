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
 * The role a connection authenticates as.
 *
 * One shared key derives both role keys, and a Portal tells the roles apart
 * only by which key the tag verifies under. This client is always a Vector;
 * [Egress] exists because the derivation is the protocol's and a vector for it
 * is the only proof that the label is transcribed correctly.
 */
enum class AuthRole(
    internal val info: String,
) {
    Vector("vector-authentication"),
    Egress("egress-authentication"),
}

/**
 * Connection authentication, per `docs/protocol.md` § Authentication (nw3).
 *
 * ```
 * salt       = SHA256("nowhere/nw3/auth-root")
 * root       = HMAC-SHA256(salt, shared_key)
 * role_key   = HMAC-SHA256(root, "<role>-authentication" || 0x01)
 * exporter   = TLS-Exporter("EXPORTER-Nowhere-nw3-Auth", empty, 32)
 * AuthFrame  = HMAC-SHA256(role_key, transport || exporter)[0..16]
 * ```
 *
 * The AuthFrame is the tag and nothing else: no session id, no role byte. v1
 * prefixed sixteen bytes of session id because a Portal paired split flows on
 * it; v3 has no split flows, so it has no session.
 *
 * The exporter binds the tag to one specific TLS or QUIC connection, so a
 * captured frame cannot be replayed onto another. Authentication has no response
 * frame: a Portal that answered differently on failure would be an oracle for
 * active probing.
 */
object Authentication {
    const val FRAME_LENGTH: Int = 16
    const val EXPORTER_LENGTH: Int = 32

    /** RFC 8446 exporter label. Lowercase `nw3`, exactly as the specification spells it. */
    const val EXPORTER_LABEL: String = "EXPORTER-Nowhere-nw3-Auth"

    private const val AUTH_ROOT_SALT_LABEL = "nowhere/nw3/auth-root"
    private const val HMAC_SHA256 = "HmacSHA256"

    /**
     * Derives a connection-independent role key from a shared key.
     *
     * HKDF-SHA256 with the expansion loop unrolled: the requested output is
     * exactly one SHA-256 block, so HKDF-Expand reduces to a single
     * `HMAC(prk, info || 0x01)`.
     */
    fun deriveRoleKey(
        sharedKey: SharedKey,
        role: AuthRole = AuthRole.Vector,
    ): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(AUTH_ROOT_SALT_LABEL.encodeToByteArray())
        val root = hmac(salt, sharedKey.toByteArray())
        return hmac(root, role.info.encodeToByteArray() + 0x01)
    }

    /**
     * Computes the 16-byte AuthFrame binding this role to this connection.
     *
     * @param exporter the 32 bytes exported from the TLS or QUIC connection.
     */
    fun tag(
        roleKey: ByteArray,
        transport: AuthTransport,
        exporter: ByteArray,
    ): ByteArray {
        require(exporter.size == EXPORTER_LENGTH) {
            "exporter must be $EXPORTER_LENGTH bytes, got ${exporter.size}"
        }
        return hmac(roleKey, byteArrayOf(transport.byte) + exporter).copyOf(FRAME_LENGTH)
    }

    /** Builds this client's AuthFrame: the Vector tag for this connection. */
    fun encodeFrame(
        sharedKey: SharedKey,
        transport: AuthTransport,
        exporter: ByteArray,
    ): ByteArray = tag(deriveRoleKey(sharedKey, AuthRole.Vector), transport, exporter)

    /**
     * Verifies a frame and returns the role it authenticates as.
     *
     * Both comparisons are constant-time and both always run, so the time taken
     * does not say which role came closer. TLS admits a Vector only, matching a
     * Portal's own rule.
     */
    fun verifyFrame(
        frame: ByteArray,
        sharedKey: SharedKey,
        transport: AuthTransport,
        exporter: ByteArray,
    ): DecodeResult<AuthRole> {
        if (frame.size != FRAME_LENGTH) return invalid(AuthReason.FrameTruncated(frame.size))
        val vector = MessageDigest.isEqual(tag(deriveRoleKey(sharedKey, AuthRole.Vector), transport, exporter), frame)
        val egress = MessageDigest.isEqual(tag(deriveRoleKey(sharedKey, AuthRole.Egress), transport, exporter), frame)
        return when {
            vector && !egress -> AuthRole.Vector.ok()
            egress && !vector && transport == AuthTransport.Quic -> AuthRole.Egress.ok()
            else -> invalid(AuthReason.TagMismatch)
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
