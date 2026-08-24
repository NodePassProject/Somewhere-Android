// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.auth

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.conformance.hexToByteArrayCompat
import eu.nodepass.somewhere.conformance.toHex
import eu.nodepass.somewhere.protocol.DecodeResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives every vector in the `auth` family. NW-P-01.
 *
 * The family's rejection vectors cover shared-key extraction from a URL as well
 * as the key itself — the URL rules are what decide which bytes become the key,
 * so they belong with authentication rather than with URL parsing.
 */
class AuthVectorTest {
    private val cases = VectorFixture.cases("auth")
    private val rejects = VectorFixture.rejects("auth")

    private fun key(text: String): SharedKey = (SharedKey.of(text) as DecodeResult.Ok).value

    private fun transportOf(name: String): AuthTransport =
        when (name) {
            "tlsTcp" -> AuthTransport.TlsTcp
            "quic" -> AuthTransport.Quic
            else -> error("unknown transport in fixture: $name")
        }

    // ── Positive vectors ────────────────────────────────────────────────────

    @Test
    fun everyPositiveVectorIsReproduced() {
        assertEquals("fixture should carry 3 auth cases", 3, cases.size)
        var checked = 0
        cases.forEach { case ->
            when {
                "expectedAuthKeyHex" in case -> {
                    val derived = Authentication.deriveAuthKey(key(case.str("sharedKeyUtf8")))
                    assertEquals(case.str("name"), case.str("expectedAuthKeyHex"), derived.toHex())
                    checked++
                }
                "expectedFrameHex" in case -> {
                    val frame =
                        Authentication.encodeFrame(
                            sharedKey = key(case.str("sharedKeyUtf8")),
                            transport = transportOf(case.str("transport")),
                            exporter = case.str("exporterHex").hexToByteArrayCompat(),
                            sessionId = case.str("sessionIdHex").hexToByteArrayCompat(),
                        )
                    assertEquals(case.str("name"), case.str("expectedFrameHex"), frame.toHex())
                    checked++
                }
                else -> error("unhandled auth case shape: ${case.str("name")}")
            }
        }
        assertEquals("every case must be exercised", cases.size, checked)
    }

    @Test
    fun theTransportByteChangesTheTag() {
        // The whole point of binding transport into the tag: a frame minted for
        // TLS must not be replayable on QUIC.
        val tls = cases.first { it.str("name").contains("TLS") }
        val quic = cases.first { it.str("name").contains("QUIC") }
        assertNotEquals(tls.str("expectedFrameHex"), quic.str("expectedFrameHex"))
    }

    @Test
    fun theExporterBindsTheTagToOneConnection() {
        val sharedKey = key("secret")
        val sessionId = ByteArray(16)
        val one = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 1 }, sessionId)
        val other = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 2 }, sessionId)
        assertNotEquals(one.toHex(), other.toHex())
    }

    @Test
    fun aFrameVerifiesAgainstItsOwnInputs() {
        val sharedKey = key("secret")
        val exporter = ByteArray(32) { it.toByte() }
        val sessionId = ByteArray(16) { (it * 3).toByte() }
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, exporter, sessionId)

        val verified = Authentication.verifyFrame(frame, sharedKey, AuthTransport.TlsTcp, exporter)
        assertEquals(sessionId.toHex(), (verified as DecodeResult.Ok).value.toHex())
    }

    @Test
    fun aFrameDoesNotVerifyOnAnotherConnection() {
        val sharedKey = key("secret")
        val sessionId = ByteArray(16)
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 1 }, sessionId)

        val replayed = Authentication.verifyFrame(frame, sharedKey, AuthTransport.TlsTcp, ByteArray(32) { 2 })
        assertEquals(AuthReason.TagMismatch, replayed.reasonOrNull())
    }

    @Test
    fun aFrameDoesNotVerifyOnAnotherTransport() {
        val sharedKey = key("secret")
        val exporter = ByteArray(32) { it.toByte() }
        val frame = Authentication.encodeFrame(sharedKey, AuthTransport.TlsTcp, exporter, ByteArray(16))

        val crossed = Authentication.verifyFrame(frame, sharedKey, AuthTransport.Quic, exporter)
        assertEquals(AuthReason.TagMismatch, crossed.reasonOrNull())
    }

    @Test
    fun theFrameIsExactlyThirtyTwoBytes() {
        val frame = Authentication.encodeFrame(key("secret"), AuthTransport.TlsTcp, ByteArray(32), ByteArray(16))
        assertEquals(32, frame.size)
        assertEquals(Authentication.FRAME_LENGTH, frame.size)
    }

    // ── Rejection vectors ───────────────────────────────────────────────────

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals("fixture should carry 5 auth rejects", 5, rejects.size)
        var checked = 0
        rejects.forEach { reject ->
            val name = reject.str("name")
            when {
                "sharedKeyLen" in reject -> {
                    val length = reject["sharedKeyLen"]!!.jsonPrimitive.content.toInt()
                    val result = SharedKey.of(ByteArray(length))
                    assertTrue("$name should be rejected", result is DecodeResult.Invalid)
                    checked++
                }
                "url" in reject -> {
                    assertTrue("$name should be rejected", userInfoOf(reject.str("url")).let(::rejectsUserInfo))
                    checked++
                }
                "urls" in reject -> {
                    reject["urls"]!!.jsonArray.forEach { element ->
                        val url = element.jsonPrimitive.content
                        assertTrue("$name ($url) should be rejected", rejectsUserInfo(userInfoOf(url)))
                    }
                    checked++
                }
                else -> error("unhandled auth reject shape: $name")
            }
        }
        assertEquals("every rejection must be exercised", rejects.size, checked)
    }

    private fun userInfoOf(url: String): String? = url.substringAfter("://").substringBefore('@').takeIf { it != url.substringAfter("://") }

    private fun rejectsUserInfo(userInfo: String?): Boolean = SharedKey.fromUserInfo(userInfo) is DecodeResult.Invalid

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

    // ── Fixture notes rendered as tests ─────────────────────────────────────

    @Test
    fun plusIsALiteralPlusAndNotASpace() {
        // The fixture states this explicitly. Using a form decoder here would
        // silently turn a key containing '+' into a different key.
        val decoded = SharedKey.fromUserInfo("a+b")
        assertEquals("a+b", String((decoded as DecodeResult.Ok).value.toByteArray()))
    }

    @Test
    fun percentEncodedBytesAreDecoded() {
        val decoded = SharedKey.fromUserInfo("a%2Bb") as DecodeResult.Ok
        assertEquals("a+b", String(decoded.value.toByteArray()))
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
