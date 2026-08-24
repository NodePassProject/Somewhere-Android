// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Prepares the request URL used to fetch a subscription, and carries the two
 * safety properties that go with it.
 *
 * **Capability negotiation.** The client appends `type`, `ver` and `caps` to the
 * subscription request. Dashboards ignore query parameters they do not know —
 * NowhereDash reads only `token` — so sending them is free today and lets a
 * server distinguish client kinds and versions tomorrow. It cannot be
 * retrofitted: a client already installed will never start sending them, so a
 * server would be left guessing from the User-Agent. The same channel is the
 * intended path for server-delivered parameters (V-05), which is why it is worth
 * opening before there is anything to send through it.
 *
 * **A subscription URL is a bearer credential.** Anyone holding it can fetch the
 * node list. Two consequences are enforced here rather than left to callers:
 * plaintext transport is reported so the UI can refuse or warn, and
 * [redactForLogging] exists so that no code path has an excuse to log the token.
 */
object SubscriptionEndpoint {
    /** Identifies this client family to a dashboard. Part of a wire contract. */
    const val CLIENT_TYPE: String = "somewhere"

    const val PARAM_TYPE: String = "type"
    const val PARAM_VERSION: String = "ver"
    const val PARAM_CAPABILITIES: String = "caps"

    /** Parameter names whose values must never be logged or displayed. */
    private val SECRET_PARAMETERS: Set<String> = setOf("token", "sub", "key", "secret")

    private const val REDACTED = "***"

    sealed interface Result {
        /**
         * @param requestUrl the URL to fetch, with capability parameters applied.
         * @param plaintextTransport true when the URL is `http`, meaning the
         *   token crosses the network in the clear. The caller decides whether to
         *   refuse or to warn; this type only guarantees the caller was told.
         */
        data class Ready(
            val requestUrl: String,
            val plaintextTransport: Boolean,
        ) : Result

        data class Rejected(
            val reason: Reason,
        ) : Result
    }

    enum class Reason {
        /** Not parseable as a URI at all. */
        MALFORMED,

        /** Parseable, but not http or https — a subscription is always fetched over HTTP. */
        UNSUPPORTED_SCHEME,

        /** No authority component, so there is nothing to connect to. */
        MISSING_HOST,
    }

    /**
     * Builds the subscription request URL.
     *
     * Existing query parameters are preserved, except that any pre-existing
     * `type`, `ver` or `caps` are replaced: those describe the client making the
     * request, so only the client can state them truthfully.
     */
    fun prepare(
        rawUrl: String,
        clientVersion: String,
        capabilities: Set<Capability>,
    ): Result {
        val uri =
            try {
                URI(rawUrl.trim())
            } catch (_: URISyntaxException) {
                return Result.Rejected(Reason.MALFORMED)
            }

        val scheme = uri.scheme?.lowercase() ?: return Result.Rejected(Reason.MALFORMED)
        if (scheme != "http" && scheme != "https") {
            return Result.Rejected(Reason.UNSUPPORTED_SCHEME)
        }
        if (uri.authority.isNullOrEmpty()) {
            return Result.Rejected(Reason.MISSING_HOST)
        }

        val preserved =
            splitQuery(uri.rawQuery).filterNot { (name, _) ->
                name == PARAM_TYPE || name == PARAM_VERSION || name == PARAM_CAPABILITIES
            }

        val appended =
            listOf(
                PARAM_TYPE to CLIENT_TYPE,
                PARAM_VERSION to clientVersion,
                PARAM_CAPABILITIES to Capability.encode(capabilities),
            ).map { (name, value) -> name to encode(value) }

        val query = (preserved + appended).joinToString("&") { (name, value) -> "$name=$value" }

        val rebuilt =
            buildString {
                append(scheme).append("://").append(uri.rawAuthority)
                append(uri.rawPath.orEmpty())
                append('?').append(query)
                uri.rawFragment?.let { append('#').append(it) }
            }

        return Result.Ready(requestUrl = rebuilt, plaintextTransport = scheme == "http")
    }

    /**
     * Returns the URL with credential-bearing parameter values replaced.
     *
     * Use this anywhere a subscription URL might reach a log, a crash report, a
     * screenshot or a share sheet. Input that cannot be parsed collapses to a
     * placeholder rather than being passed through: a malformed URL is exactly
     * the case where a token is most likely to be sitting somewhere unexpected.
     */
    fun redactForLogging(rawUrl: String): String {
        val uri =
            try {
                URI(rawUrl.trim())
            } catch (_: URISyntaxException) {
                return "<unparseable url>"
            }
        val scheme = uri.scheme ?: return "<unparseable url>"
        val authority = uri.rawAuthority ?: return "<unparseable url>"

        val parameters = splitQuery(uri.rawQuery)
        val redacted =
            parameters.joinToString("&") { (name, value) ->
                if (name.lowercase() in SECRET_PARAMETERS) "$name=$REDACTED" else "$name=$value"
            }

        return buildString {
            append(scheme).append("://").append(authority).append(uri.rawPath.orEmpty())
            if (redacted.isNotEmpty()) append('?').append(redacted)
            if (uri.rawFragment != null) append("#").append(REDACTED)
        }
    }

    /**
     * Splits a raw query into name/value pairs, preserving order and original
     * encoding. Values are not decoded: they are being put straight back into a
     * URL, and a decode/re-encode round trip is a way to corrupt them.
     */
    private fun splitQuery(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery.isNullOrEmpty()) return emptyList()
        return rawQuery
            .split('&')
            .filter { it.isNotEmpty() }
            .map { part ->
                val separator = part.indexOf('=')
                if (separator < 0) part to "" else part.substring(0, separator) to part.substring(separator + 1)
            }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
