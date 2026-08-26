// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.dns

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.target.Target

/**
 * Decides what happens to a DNS query the tunnel has caught.
 *
 * Two outcomes and no third: either this client answers, from the fake-IP pool,
 * or the query goes on to the resolver it was addressed to. There is no drop.
 * A dropped query is indistinguishable from a broken network, and the device's
 * resolver will spend its whole retry schedule proving it.
 *
 * Everything here is a pure decision over bytes, which is why the class holds no
 * Android type and does no I/O: the caller owns the socket and the lwIP thread,
 * and this owns the rule.
 *
 * ## Only the families the tunnel can carry
 *
 * A synthetic address is only useful if a flow to it can reach this client, and
 * a flow can only reach it if the TUN carries a route for that family. So AAAA
 * is answered with NODATA while [synthesiseIpv6] is false, and the device falls
 * back to A.
 *
 * That costs nothing, which is worth being precise about, because refusing a
 * AAAA normally would. **This client never connects to the address it resolves.**
 * The name goes to the Portal and the Portal resolves it in its own network —
 * over IPv6 if that is what it prefers. Denying the device a AAAA record
 * therefore says nothing about how the connection is finally made; it only
 * chooses which of two equivalent local placeholders is used to carry the name.
 *
 * The alternative — handing back an `fc00::` address with no route behind it —
 * produces a connection that fails at the device with no error anyone can act
 * on, which is exactly the class of defect the fake-IP layer exists to avoid.
 */
class DnsInterceptor(
    private val pool: FakeIpPool,
    private val synthesiseIpv6: Boolean = false,
) {
    sealed interface Outcome {
        /** Write these bytes back to the device as the answer. */
        data class Answer(
            val message: ByteArray,
            val name: String,
        ) : Outcome {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Answer && name == other.name && message.contentEquals(other.message))

            override fun hashCode(): Int = 31 * message.contentHashCode() + name.hashCode()
        }

        /**
         * Not ours. Send it to the resolver the device addressed it to,
         * unchanged.
         *
         * [why] is for the log and for tests; it never reaches the device,
         * which gets the query it wrote and nothing added to it.
         */
        data class Relay(
            val why: String,
        ) : Outcome
    }

    fun handle(query: ByteArray): Outcome {
        val question =
            when (val parsed = DnsMessage.parseQuestion(query)) {
                is DecodeResult.Ok -> parsed.value
                is DecodeResult.Invalid -> return Outcome.Relay(parsed.reason.detail)
            }

        if (!question.isAddressQuery) {
            return Outcome.Relay("type ${question.type} class ${question.recordClass} is not an address query")
        }

        // A name the protocol will not encode is a name that cannot become a
        // target, so minting an address for it would only move the failure to
        // the moment a flow opens. `_dns-sd._udp.local` and the reverse-lookup
        // zones are the ordinary cases, and they belong to a real resolver.
        if (Target.ofDomain(question.name, VALIDATION_PORT) !is DecodeResult.Ok) {
            return Outcome.Relay("'${question.name}' is not encodable as a domain target")
        }

        if (question.type == DnsMessage.TYPE_AAAA && !synthesiseIpv6) {
            return Outcome.Answer(DnsMessage.noData(query, question), question.name)
        }

        val offset =
            pool.allocate(question.name)
                ?: return Outcome.Relay("the fake-IP pool is full and every entry is in use")

        val address =
            if (question.type == DnsMessage.TYPE_AAAA) FakeIpPool.ipv6(offset) else FakeIpPool.ipv4(offset)

        return when (val built = DnsMessage.answer(query, question, address)) {
            is DecodeResult.Ok -> Outcome.Answer(built.value, question.name)
            is DecodeResult.Invalid -> Outcome.Relay(built.reason.detail)
        }
    }

    private companion object {
        /**
         * Any non-zero port. Only the name is under test here — a target's port
         * comes from the flow, not from the query, and zero is the one value
         * [Target] rejects on its own account.
         */
        const val VALIDATION_PORT = 1
    }
}
