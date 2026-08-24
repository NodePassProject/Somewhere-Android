// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import java.util.Base64

/**
 * What a dashboard reports about an account. NW-D-02.
 *
 * **There is no upload figure, on purpose.** Upstream does not meter upload
 * separately, so the header always says `upload=0`. Carrying that through would
 * invite a UI to render "0 B uploaded" as though it were a measurement, and this
 * type refuses to make that possible.
 *
 * @param downloadBytes traffic counted against this subscription. Counted, not
 *   used: metering is per Portal, so two subscriptions sharing one are each
 *   charged the full amount (NW-D-05). Callers must word it accordingly.
 * @param totalBytes the cap, or null for unlimited (`-1` on the wire).
 * @param expiresAtEpochSeconds expiry, or null when none was given.
 */
data class SubscriptionUsage(
    val downloadBytes: Long,
    val totalBytes: Long?,
    val expiresAtEpochSeconds: Long?,
) {
    val isUnlimited: Boolean get() = totalBytes == null

    /** Null when unlimited — a fraction of no limit is not a number. */
    val fractionCounted: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let { downloadBytes.toDouble() / it }

    companion object {
        private const val UNLIMITED_SENTINEL = -1L

        /**
         * Parses `subscription-userinfo: upload=0; download=…; total=…; expire=…`.
         *
         * Tolerant by construction: an unparseable field is dropped rather than
         * failing the whole fetch, because a dashboard that adds a field or
         * formats one unusually should not cost the user their node list.
         */
        fun parse(header: String?): SubscriptionUsage? {
            if (header.isNullOrBlank()) return null
            val fields =
                header
                    .split(';')
                    .mapNotNull { part ->
                        val pieces = part.split('=', limit = 2)
                        if (pieces.size != 2) return@mapNotNull null
                        pieces[0].trim().lowercase() to pieces[1].trim()
                    }.toMap()

            val download = fields["download"]?.toLongOrNull() ?: return null
            val total =
                fields["total"]?.toLongOrNull()?.let { if (it == UNLIMITED_SENTINEL) null else it }
            return SubscriptionUsage(
                downloadBytes = download,
                totalBytes = total,
                expiresAtEpochSeconds = fields["expire"]?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
    }
}

sealed interface SubscriptionReason : DecodeReason {
    data class Transport(
        val cause: String,
    ) : SubscriptionReason {
        override val detail: String = "could not reach the subscription: $cause"
    }

    data class HttpStatus(
        val code: Int,
    ) : SubscriptionReason {
        override val detail: String = "the subscription returned HTTP $code"
    }

    /**
     * The fetch succeeded and the feed was empty.
     *
     * NW-D-04: a dashboard removes nodes from the feed when a subscription is
     * over quota or expired, so an empty feed is almost never a broken
     * subscription — it is an exhausted one, and saying "network error" would
     * send the user to debug the wrong thing entirely.
     */
    data object NoNodes : SubscriptionReason {
        override val detail: String = "the subscription returned no nodes — it has expired or is out of quota"
    }

    data class Unusable(
        val cause: String,
    ) : SubscriptionReason {
        override val detail: String = "the subscription response could not be read: $cause"
    }
}

/** A fetched subscription: the nodes, and what the dashboard said about the account. */
data class Subscription(
    val nodes: List<NowhereUrl>,
    val usage: SubscriptionUsage?,
    val title: String?,
    /** True when the fetch crossed the network in the clear. */
    val fetchedOverPlaintext: Boolean,
) {
    companion object {
        /**
         * Builds a subscription from a response body and headers.
         *
         * Unparseable lines are skipped rather than failing the fetch: one
         * malformed node in a feed of twenty should cost the user that node, not
         * the other nineteen.
         */
        fun from(
            body: String,
            usageHeader: String?,
            titleHeader: String?,
            plaintext: Boolean,
        ): DecodeResult<Subscription> {
            val nodes =
                body
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line -> (NowhereUrl.parse(line) as? DecodeResult.Ok)?.value }
                    .toList()

            if (nodes.isEmpty()) return invalid(SubscriptionReason.NoNodes)

            return Subscription(
                nodes = nodes,
                usage = SubscriptionUsage.parse(usageHeader),
                title = decodeTitle(titleHeader),
                fetchedOverPlaintext = plaintext,
            ).ok()
        }

        /** `profile-title: base64:…`, or a literal. A bad encoding yields no title. */
        private fun decodeTitle(header: String?): String? {
            if (header.isNullOrBlank()) return null
            val trimmed = header.trim()
            if (!trimmed.startsWith(BASE64_PREFIX)) return trimmed
            return runCatching {
                String(Base64.getDecoder().decode(trimmed.removePrefix(BASE64_PREFIX)), Charsets.UTF_8)
            }.getOrNull()
        }

        private const val BASE64_PREFIX = "base64:"
    }
}
