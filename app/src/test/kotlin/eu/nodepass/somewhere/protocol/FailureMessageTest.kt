// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol

import eu.nodepass.somewhere.protocol.auth.AuthReason
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowStartReason
import eu.nodepass.somewhere.protocol.frame.SetupResultReason
import eu.nodepass.somewhere.protocol.frame.UotReason
import eu.nodepass.somewhere.protocol.quic.DatagramReason
import eu.nodepass.somewhere.protocol.quic.QuicFlowIdReason
import eu.nodepass.somewhere.protocol.session.LaneReason
import eu.nodepass.somewhere.protocol.session.QuicCarrierReason
import eu.nodepass.somewhere.protocol.target.TargetReason
import eu.nodepass.somewhere.protocol.tls.ExporterReason
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.protocol.url.UrlReason
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every failure reason, checked for the two things a failure message must be.
 *
 * **Useful**: it reaches a person, either in a log they will paste into an issue
 * or in a message they will read on screen. An empty or generic one wastes the
 * only chance to say what went wrong.
 *
 * **Safe**: a reason may be logged, so it must never carry key material, a
 * token, or a full node address. This is the rule most likely to be broken by an
 * innocent-looking change — adding the offending value to a message is the
 * obvious way to make it more helpful.
 */
class FailureMessageTest {
    private companion object {
        const val SERVICE_KEY = "0123456789abcdef0123456789abcdef"
    }

    private val everyReason: List<DecodeReason> =
        listOf(
            AuthReason.EmptySharedKey,
            AuthReason.SharedKeyTooLong(999),
            AuthReason.MissingUserInfo,
            AuthReason.PasswordComponentPresent,
            AuthReason.MalformedPercentEncoding,
            AuthReason.FrameTruncated(7),
            AuthReason.TagMismatch,
            AuthReason.NotServiceFormat,
            FlowStartReason.Missing,
            TargetReason.PortZero,
            TargetReason.UnknownAddressType(2),
            TargetReason.Truncated,
            TargetReason.DomainLength(300),
            TargetReason.DomainNotAscii,
            TargetReason.DomainLabelLength(70),
            TargetReason.DomainLabelHyphen,
            TargetReason.DomainLabelCharacter,
            SetupResultReason.OutOfRange(9),
            SetupResultReason.Missing,
            UotReason.PayloadTooLarge(70000),
            UotReason.TruncatedLength,
            UotReason.TruncatedPayload(10, 2),
            DatagramReason.Truncated(3, 5),
            DatagramReason.InvalidType,
            DatagramReason.FlowIdOutOfRange(1u shl 31),
            DatagramReason.ClosePayload,
            DatagramReason.FragmentPayloadLength,
            DatagramReason.TotalLengthTooSmall(1, 3),
            DatagramReason.FlowIdZero,
            DatagramReason.PacketIdZero,
            DatagramReason.FragmentIndexOutOfRange(5, 3),
            DatagramReason.FragmentCountOutOfRange(1),
            DatagramReason.PayloadTooLarge(70000),
            DatagramReason.MetadataConflict,
            DatagramReason.DuplicateFragmentDiffers,
            DatagramReason.ReassembledLengthMismatch(10, 20),
            DatagramReason.NotReady,
            DatagramReason.DatagramTooSmall(8),
            UrlReason.Malformed,
            UrlReason.WrongScheme("vector"),
            UrlReason.MissingHost,
            UrlReason.InvalidPort(0),
            UrlReason.CarrierPath("/tcp:1/udp:2"),
            UrlReason.InvalidCarrier("pigeon"),
            UrlReason.InvalidMorph("2"),
            UrlReason.InvalidSni("a_b"),
            UrlReason.InvalidPin("nope"),
            UrlReason.InvalidRate("rate", "-1"),
            QuicFlowIdReason.NotBidirectional(2),
            QuicFlowIdReason.WrongInitiator(1),
            QuicFlowIdReason.Exhausted(4_294_967_292),
            QuicCarrierReason.CarrierClosed,
            QuicCarrierReason.NoSetupByte,
            QuicCarrierReason.NoDatagrams,
            QuicCarrierReason.StreamUnusable(QuicFlowIdReason.Exhausted(4_294_967_292)),
            ExporterReason.NotConnected,
            ExporterReason.WrongLength(16, 32),
            ExporterReason.Unsupported("no provider"),
            ExporterReason.Failed("SSLException"),
            LaneReason.AlreadyUsed,
            LaneReason.TransportClosed,
            LaneReason.NoSetupByte,
            LaneReason.Rejected(eu.nodepass.somewhere.protocol.frame.SetupResult.FlowLimit),
            LaneReason.HeaderInvalid(SetupResultReason.OutOfRange(9)),
        )

    @Test
    fun everyReasonSaysSomething() {
        everyReason.forEach { reason ->
            assertTrue("${reason::class.simpleName} has an empty detail", reason.detail.isNotBlank())
            assertTrue(
                "${reason::class.simpleName}: '${reason.detail}' is too short to be useful",
                reason.detail.length >= 12,
            )
        }
    }

    @Test
    fun noReasonEchoesTheValueItRejected() {
        // The rule that matters, tested by feeding real secrets through the
        // failure paths and requiring that none comes back out.
        //
        // An earlier version of this test banned the *word* "password", which
        // failed on a message that correctly describes a URL carrying a password
        // component — a structural fact, not a leaked value. A rule that
        // produces false positives gets relaxed, and a relaxed rule stops
        // catching the real thing. So this checks values, not vocabulary.
        val secret = "hunter2SuperSecret"

        val leaks =
            listOf(
                "shared key too long" to
                    SharedKey.of(ByteArray(300) { 'x'.code.toByte() }).reasonOrNull(),
                "userinfo with a password" to
                    SharedKey.fromUserInfo("$secret:alsoSecret").reasonOrNull(),
                "malformed escape" to
                    SharedKey.fromUserInfo("$secret%GG").reasonOrNull(),
                "url with a token" to
                    NowhereUrl.parse("nowhere://$secret@host:0?net=tcp").reasonOrNull(),
                "a key in the wrong form" to
                    NowhereUrl.parse("nowhere://$secret@host:443").reasonOrNull(),
                "bad pin" to
                    NowhereUrl.parse("nowhere://$SERVICE_KEY@host:443?pin=$secret").reasonOrNull(),
                "bad sni" to
                    NowhereUrl.parse("nowhere://$SERVICE_KEY@host:443?sni=${secret}_").reasonOrNull(),
            )

        leaks.forEach { (what, reason) ->
            assertTrue("$what produced no reason at all", reason != null)
            assertTrue(
                "$what leaked the value into its message: ${reason!!.detail}",
                !reason.detail.contains(secret),
            )
        }
    }

    @Test
    fun noReasonCarriesKeyMaterialMarkers() {
        // Cheap belt-and-braces: nothing should ever quote a PEM block or a
        // query string wholesale.
        val forbidden = listOf("BEGIN ", "token=", "-----")
        everyReason.forEach { reason ->
            forbidden.forEach { needle ->
                assertTrue(
                    "${reason::class.simpleName} contains '$needle': ${reason.detail}",
                    !reason.detail.contains(needle),
                )
            }
        }
    }

    @Test
    fun reasonsReadAsSentencesNotAsSymbols() {
        // A reason is read by a person. "INVALID_STATE_3" is not a message.
        everyReason.forEach { reason ->
            assertTrue(
                "${reason::class.simpleName}: '${reason.detail}' should contain words",
                reason.detail.contains(" "),
            )
        }
    }
}
