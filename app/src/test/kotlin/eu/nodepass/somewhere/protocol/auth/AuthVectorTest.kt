// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.auth

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives every vector in the `auth` family, `docs/protocol.md` § Authentication.
 *
 * The positive vectors are upstream's own (`src/tests/protocol/vectors.json`):
 * both role keys and all four AuthFrames, over both transports, for one shared
 * key and one exporter.
 */
class AuthVectorTest {
    private val family = VectorFixture.family("auth")
    private val cases = VectorFixture.cases("auth")
    private val rejects = VectorFixture.rejects("auth")
    private val exporter = family.str("exporterHex").hexToByteArrayCompat()
    private val sharedKey = key(family.str("sharedKeyUtf8"))

    private fun key(text: String): SharedKey = (SharedKey.of(text) as DecodeResult.Ok).value

    private fun transportOf(name: String): AuthTransport =
        when (name) {
            "tlsTcp" -> AuthTransport.TlsTcp
            "quic" -> AuthTransport.Quic
            else -> error("unknown transport in fixture: $name")
        }

    private fun roleOf(name: String): AuthRole =
        when (name) {
            "vector" -> AuthRole.Vector
            "egress" -> AuthRole.Egress
            else -> error("unknown role in fixture: $name")
        }

    // ── Positive vectors ────────────────────────────────────────────────────

    @Test
    fun everyPositiveVectorIsReproduced() {
        assertEquals("fixture should carry 4 auth cases", 4, cases.size)
        cases.forEach { case ->
            val role = roleOf(case.str("role"))
            val roleKey = Authentication.deriveRoleKey(sharedKey, role)
            assertEquals("${case.str("name")} role key", case.str("expectedRoleKeyHex"), roleKey.toHex())
            val frame = Authentication.tag(roleKey, transportOf(case.str("transport")), exporter)
            assertEquals(case.str("name"), case.str("expectedFrameHex"), frame.toHex())
        }
    }

    @Test
    fun thisClientSendsTheVectorFrame() {
        // encodeFrame is what goes on the wire, and it must be the Vector role's:
        // an Egress tag over TLS is refused, and over QUIC it would register as
        // an Egress and never be offered a flow.
        cases.filter { it.str("role") == "vector" }.forEach { case ->
            val frame = Authentication.encodeFrame(sharedKey, transportOf(case.str("transport")), exporter)
            assertEquals(case.str("name"), case.str("expectedFrameHex"), frame.toHex())
        }
    }

    @Test
    fun theExporterLabelIsTheV3One() {
        assertEquals(family["derivation"]!!.jsonObject.str("exporterLabel"), Authentication.EXPORTER_LABEL)
        assertEquals("EXPORTER-Nowhere-nw3-Auth", Authentication.EXPORTER_LABEL)
    }

    @Test
    fun theTransportByteChangesTheTag() {
        // The whole point of binding transport into the tag: a frame minted for
        // TLS must not be replayable on QUIC.
        val tls = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, exporter)
        val quic = Authentication.encodeFrame(sharedKey, AuthTransport.Quic, exporter)
        assertNotEquals(tls.toHex(), quic.toHex())
    }

    @Test
    fun theExporterBindsTheTagToOneConnection() {
        val one = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 1 })
        val other = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 2 })
        assertNotEquals(one.toHex(), other.toHex())
    }

    @Test
    fun aFrameVerifiesAsTheVectorRole() {
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, exporter)
        val verified = Authentication.verifyFrame(frame, sharedKey, AuthTransport.TlsTcp, exporter)
        assertEquals(AuthRole.Vector, (verified as DecodeResult.Ok).value)
    }

    @Test
    fun anEgressTagVerifiesOverQuicOnly() {
        val roleKey = Authentication.deriveRoleKey(sharedKey, AuthRole.Egress)
        val overQuic = Authentication.tag(roleKey, AuthTransport.Quic, exporter)
        assertEquals(AuthRole.Egress, Authentication.verifyFrame(overQuic, sharedKey, AuthTransport.Quic, exporter).valueOrNull())

        val overTls = Authentication.tag(roleKey, AuthTransport.TlsTcp, exporter)
        assertEquals(AuthReason.TagMismatch, Authentication.verifyFrame(overTls, sharedKey, AuthTransport.TlsTcp, exporter).reasonOrNull())
    }

    @Test
    fun aFrameDoesNotVerifyOnAnotherConnection() {
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 1 })
        val replayed = Authentication.verifyFrame(frame, sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 2 })
        assertEquals(AuthReason.TagMismatch, replayed.reasonOrNull())
    }

    @Test
    fun aFrameDoesNotVerifyOnAnotherTransport() {
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, exporter)
        val crossed = Authentication.verifyFrame(frame, sharedKey, AuthTransport.Quic, exporter)
        assertEquals(AuthReason.TagMismatch, crossed.reasonOrNull())
    }

    @Test
    fun theFrameIsExactlySixteenBytes() {
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, exporter)
        assertEquals(16, frame.size)
        assertEquals(Authentication.FRAME_LENGTH, frame.size)
    }

    @Test
    fun theRoleKeysDiffer() {
        // One shared key derives both, and the specification is explicit that
        // this is role distinction rather than credential isolation — but the
        // two must still differ, or a Portal could not tell the roles apart.
        assertNotEquals(
            Authentication.deriveRoleKey(sharedKey, AuthRole.Vector).toHex(),
            Authentication.deriveRoleKey(sharedKey, AuthRole.Egress).toHex(),
        )
    }

    // ── Rejection vectors ───────────────────────────────────────────────────

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 4 auth rejects", 4, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            when {
                "sharedKeyUtf8" in reject -> {
                    assertFalse(name, SharedKey.isServiceFormat(reject.str("sharedKeyUtf8").encodeToByteArray()))
                    checked++
                }
                "frameLen" in reject -> {
                    val length = reject.str("frameLen").toInt()
                    val result = Authentication.verifyFrame(ByteArray(length), sharedKey, AuthTransport.TlsTcp, exporter)
                    assertEquals(name, AuthReason.FrameTruncated(length), result.reasonOrNull())
                    checked++
                }
                name.startsWith("Egress tag over TLS") -> {
                    // Covered by anEgressTagVerifiesOverQuicOnly; counted here so
                    // that the fixture and this suite cannot drift apart.
                    checked++
                }
                else -> error("unhandled auth reject shape: $name")
            }
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    @Test
    fun theVectorsKeyIsInServiceForm() {
        assertTrue(SharedKey.isServiceFormat(family.str("sharedKeyUtf8").encodeToByteArray()))
    }

    @Test
    fun rejectionsAreDistinguishable() {
        // A single "invalid key" reason would make it impossible to tell a user
        // whether they pasted a URL with a password or one with a broken escape.
        assertEquals(AuthReason.EmptySharedKey, SharedKey.of(ByteArray(0)).reasonOrNull())
        assertTrue(SharedKey.of(ByteArray(256)).reasonOrNull() is AuthReason.SharedKeyTooLong)
        assertEquals(AuthReason.MissingUserInfo, SharedKey.fromUserInfo(null).reasonOrNull())
        assertEquals(AuthReason.PasswordComponentPresent, SharedKey.fromUserInfo("key:pass").reasonOrNull())
        assertEquals(AuthReason.MalformedPercentEncoding, SharedKey.fromUserInfo("bad%GG").reasonOrNull())
    }

    @Test
    fun boundaryLengthsAreAccepted() {
        assertTrue(SharedKey.of(ByteArray(1)) is DecodeResult.Ok)
        assertTrue(SharedKey.of(ByteArray(255)) is DecodeResult.Ok)
    }

    // ── Shared-key handling ─────────────────────────────────────────────────

    @Test
    fun plusIsALiteralPlusAndNotASpace() {
        // Using a form decoder here would silently turn a key containing '+'
        // into a different key.
        val decoded = SharedKey.fromUserInfo("a+b")
        assertEquals("a+b", String((decoded as DecodeResult.Ok).value.toByteArray()))
    }

    @Test
    fun percentEncodedBytesAreDecoded() {
        val decoded = SharedKey.fromUserInfo("a%2Bb") as DecodeResult.Ok
        assertEquals("a+b", String(decoded.value.toByteArray()))
    }

    @Test
    fun hexLookingTextIsNotHexDecoded() {
        // "Its text bytes enter the KDF directly; hexadecimal-looking text is
        // not hex-decoded." The 32 characters are 32 bytes, not 16.
        assertEquals(32, sharedKey.size)
    }

    @Test
    fun theKeyIsNotRenderedByToString() {
        // It is the one value in this project that must never reach a log.
        val rendered = key("hunter2").toString()
        assertTrue("toString must not contain the key", !rendered.contains("hunter2"))
        assertTrue("toString should state the length", rendered.contains("7"))
    }

    @Test
    fun theKeyCannotBeMutatedThroughAReturnedArray() {
        val sharedKey = key("secret")
        sharedKey.toByteArray()[0] = 0
        assertEquals("secret", String(sharedKey.toByteArray()))
    }
}
