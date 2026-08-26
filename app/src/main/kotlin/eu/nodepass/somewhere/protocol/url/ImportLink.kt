// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import java.net.URI
import java.net.URISyntaxException

/**
 * The wrapper a dashboard's "add to app" button puts around a link. NW-D-03.
 *
 * NowhereDash does not hand the app a link. It hands it a link **inside another
 * link**:
 *
 * ```
 * anywhere://add-proxy?link=<percent-encoded subscription or node URL>
 * ```
 *
 * (`web/src/lib/subscriptions-api.ts`.) The outer form exists so a browser has
 * a scheme to dispatch on; the payload is the inner one, and it is ordinarily a
 * subscription URL rather than a node.
 *
 * This client declares the `anywhere` scheme in its manifest, so those links
 * already **arrive** — they simply were not understood, and reached the import
 * screen as "scheme 'anywhere' is not a client import URL". Every import button
 * on a live dashboard produces this shape, so the manifest entry was an
 * invitation the parser could not honour.
 *
 * Unwrapping is deliberately the only thing done here. What comes out is
 * handed to the same two parsers as anything typed by hand, so a wrapper cannot
 * become a way to reach a code path a pasted link cannot.
 */
object ImportLink {
    /** The one action the family's dashboards use. */
    private const val ACTION = "add-proxy"

    private const val PARAMETER = "link"

    /**
     * The link inside [text], or [text] unchanged when there is no wrapper.
     *
     * Total on purpose: this sits in front of the parsers rather than beside
     * them, and anything it cannot make sense of has to arrive at them looking
     * exactly as it did — so the reason the user is shown is the parser's
     * reason about their link, never this function's about its own.
     */
    fun unwrap(text: String): String {
        val trimmed = text.trim()
        val uri =
            try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return text
            }

        val scheme = uri.scheme?.lowercase() ?: return text
        if (scheme !in ACCEPTED_SCHEMES) return text

        // Two spellings reach this, and `URI` models them differently.
        // `anywhere://add-proxy?link=…` is hierarchical: the action is the
        // authority and the query is parsed out for us. `anywhere:add-proxy?…`
        // is *opaque* — no authority, no path and no query, just one
        // scheme-specific part — so it has to be split by hand. A browser
        // dispatches both identically, so this must too.
        val (action, query) =
            if (uri.isOpaque) {
                val part = uri.rawSchemeSpecificPart.orEmpty()
                val mark = part.indexOf('?')
                if (mark < 0) part to null else part.substring(0, mark) to part.substring(mark + 1)
            } else {
                (uri.authority ?: uri.path?.trimStart('/')).orEmpty() to uri.rawQuery
            }

        if (!action.equals(ACTION, ignoreCase = true)) return text

        val inner = parameter(query, PARAMETER) ?: return text
        return inner.ifBlank { text }
    }

    /** True when [text] is a wrapper this understands, whatever it wraps. */
    fun isWrapped(text: String): Boolean = unwrap(text) != text

    /**
     * One parameter from a raw query, percent-decoded.
     *
     * Decoded here rather than by [URI.getQuery] because a decoded query cannot
     * be split safely: the inner link's own `&` and `=` are encoded, and
     * splitting after decoding would truncate every subscription URL that
     * carries more than one parameter — which is all of them, since the token
     * follows the path.
     */
    private fun parameter(
        rawQuery: String?,
        name: String,
    ): String? {
        rawQuery ?: return null
        for (pair in rawQuery.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            if (!pair.regionMatches(0, name, 0, separator, ignoreCase = true) || separator != name.length) continue
            return percentDecode(pair.substring(separator + 1))
        }
        return null
    }

    /**
     * Percent-decoding that leaves `+` alone.
     *
     * `URLDecoder` would turn it into a space, which is the
     * `application/x-www-form-urlencoded` rule and not the URI one. A shared key
     * containing a literal `+` has already been corrupted that way once in this
     * project, so it is spelled out rather than delegated.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%' && index + 2 < value.length) {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded != null) {
                    out.append(decoded.toChar())
                    index += 3
                    continue
                }
            }
            out.append(character)
            index++
        }
        return out.toString()
    }

    /**
     * The schemes a wrapper may arrive under.
     *
     * The same three the manifest declares. `nowhere` is included for symmetry
     * rather than because anything emits it — a dashboard that started to would
     * otherwise be understood by the manifest and not by the parser, which is
     * the exact gap this closes.
     */
    private val ACCEPTED_SCHEMES = setOf("somewhere", "anywhere", "nowhere")
}
