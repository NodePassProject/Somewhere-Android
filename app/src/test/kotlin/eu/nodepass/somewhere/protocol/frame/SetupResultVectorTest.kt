// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.frame

import eu.nodepass.somewhere.conformance.VectorFixture
import eu.nodepass.somewhere.conformance.VectorFixture.int
import eu.nodepass.somewhere.conformance.VectorFixture.str
import eu.nodepass.somewhere.protocol.DecodeResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives every vector in the `setupResult` family. NW-P-06. */
class SetupResultVectorTest {
    private val cases = VectorFixture.cases("setupResult")
    private val rejects = VectorFixture.rejects("setupResult")

    @Test
    fun everyPositiveVectorDecodesToItsNamedResult() {
        assertEquals("fixture should carry 8 setupResult cases", 8, cases.size)
        cases.forEach { case ->
            val decoded = SetupResult.decode(case.int("byte").toByte())
            val result = (decoded as DecodeResult.Ok).value
            assertEquals(case.str("name"), case.int("byte"), result.byte)
        }
    }

    @Test
    fun theFixtureNamesMatchTheEnumOneForOne() {
        // Guards against a value being silently renumbered: the wire value and
        // the meaning must stay bound together.
        val expected =
            mapOf(
                0 to SetupResult.Ready,
                1 to SetupResult.InvalidRequest,
                2 to SetupResult.MetadataConflict,
                3 to SetupResult.PairTimeout,
                4 to SetupResult.FlowLimit,
                5 to SetupResult.DialFailed,
                6 to SetupResult.SessionReplaced,
                7 to SetupResult.InternalError,
            )
        cases.forEach { case ->
            val byte = case.int("byte")
            assertEquals(
                "fixture ${case.str("name")} at byte $byte",
                expected.getValue(byte),
                (SetupResult.decode(byte.toByte()) as DecodeResult.Ok).value,
            )
        }
    }

    @Test
    fun everyRejectionVectorIsRefused() {
        assertEquals(1, rejects.size)
        val bytes = rejects.first()["bytes"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        bytes.forEach { value ->
            val reason = SetupResult.decode(value.toByte()).reasonOrNull()
            assertTrue("byte $value must be a protocol error", reason is SetupResultReason.OutOfRange)
        }
    }

    @Test
    fun everyValueOutsideTheRangeIsAProtocolError() {
        // Not just the two the fixture names: the whole rest of the byte space.
        for (value in 8..255) {
            val reason = SetupResult.decode(value.toByte()).reasonOrNull()
            assertTrue("byte $value must be rejected", reason is SetupResultReason.OutOfRange)
        }
    }

    @Test
    fun allSevenRejectionReasonsAreDistinct() {
        // The requirement this task exists for. If these ever collapse, the user
        // loses the difference between "the Portal is full" and "your session
        // was replaced" — which is the information they need to act on.
        val rejections = SetupResult.entries.filter { it.isRejection }
        assertEquals("there are exactly seven rejection reasons", 7, rejections.size)
        assertEquals("each must be a distinct value", 7, rejections.toSet().size)
        assertEquals("each must have a distinct wire byte", 7, rejections.map { it.byte }.toSet().size)
    }

    @Test
    fun readyIsTheOnlySuccess() {
        assertTrue(SetupResult.Ready.isReady)
        SetupResult.entries.filter { it != SetupResult.Ready }.forEach {
            assertTrue("$it must not read as ready", !it.isReady)
            assertTrue("$it must read as a rejection", it.isRejection)
        }
    }

    @Test
    fun anAbsentByteIsDistinctFromABadOne() {
        // "The Portal said something invalid" and "the Portal said nothing" are
        // different failures and need different messages.
        assertEquals(SetupResultReason.Missing, SetupResult.decode(ByteArray(0)).reasonOrNull())
        assertTrue(SetupResult.decode(byteArrayOf(99)).reasonOrNull() is SetupResultReason.OutOfRange)
    }

    @Test
    fun decodingHonoursAnOffset() {
        val buffer = byteArrayOf(0xff.toByte(), 0xff.toByte(), 4)
        assertEquals(SetupResult.FlowLimit, (SetupResult.decode(buffer, 2) as DecodeResult.Ok).value)
    }
}
