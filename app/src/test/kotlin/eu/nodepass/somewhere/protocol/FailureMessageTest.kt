// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol

import eu.nodepass.somewhere.protocol.auth.AuthReason
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.frame.FlowHeaderReason
import eu.nodepass.somewhere.protocol.frame.SetupResultReason
import eu.nodepass.somewhere.protocol.frame.UotReason
import eu.nodepass.somewhere.protocol.mux.MuxReason
import eu.nodepass.somewhere.protocol.quic.DatagramReason
import eu.nodepass.somewhere.protocol.session.LaneReason
import eu.nodepass.somewhere.protocol.session.SessionReason
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
    private val everyReason: List<DecodeReason> =
        listOf(
            AuthReason.EmptySharedKey,
            AuthReason.SharedKeyTooLong(999),
            AuthReason.MissingUserInfo,
            AuthReason.PasswordComponentPresent,
            AuthReason.MalformedPercentEncoding,
            AuthReason.FrameTruncated(7),
            AuthReason.TagMismatch,
            FlowHeaderReason.WrongLength(3),
            FlowHeaderReason.ReservedRole,
            FlowHeaderReason.ZeroFlowId,
            FlowHeaderReason.HopsOutOfRange(9),
            FlowHeaderReason.ClientFlowWithHops(2),
            FlowHeaderReason.DuplexCarriersDiffer,
            FlowHeaderReason.SplitCarriersMatch,
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
            MuxReason.Truncated(3),
            MuxReason.UnknownKind(9),
            MuxReason.DatagramUnsupported,
            MuxReason.ReservedFlagBits(0x80),
            MuxReason.ResetNotAlone,
            MuxReason.ResetWithValue(5),
            MuxReason.StreamFlowIdZero,
            MuxReason.StreamPayloadTooLarge(99999),
            MuxReason.WindowWithFlags(1),
            MuxReason.WindowZeroCredit,
            MuxReason.CreditExceedsWindow(999999, 524288),
            MuxReason.UnknownFlow(42u),
            DatagramReason.Truncated(3, 5),
            DatagramReason.InvalidType,
            DatagramReason.ReservedBitsSet(0x80),
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
            UrlReason.InvalidCarrier("up", "pigeon"),
            UrlReason.InvalidAlpn(0),
            UrlReason.InvalidPin("nope"),
            ExporterReason.NotConnected,
            ExporterReason.WrongLength(16, 32),
            ExporterReason.Unsupported("no provider"),
            ExporterReason.Failed("SSLException"),
            LaneReason.AlreadyUsed,
            LaneReason.TransportClosed,
            LaneReason.NoSetupByte,
            LaneReason.Rejected(eu.nodepass.somewhere.protocol.frame.SetupResult.FlowLimit),
            LaneReason.HeaderInvalid(FlowHeaderReason.ZeroFlowId),
            SessionReason.FlowIdsExhausted,
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
                    NowhereUrl.parse("nowhere://$secret@host:0?up=tcp").reasonOrNull(),
                "bad pin" to
                    NowhereUrl.parse("nowhere://k@host:443?pin=$secret").reasonOrNull(),
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
