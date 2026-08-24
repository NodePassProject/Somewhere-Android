// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import java.net.URI
import java.net.URISyntaxException

/** The next-hop carrier a direction uses, as expressed in configuration. */
enum class NextHopCarrier(
    val token: String,
) {
    Tcp("tcp"),
    Udp("udp"),
    ;

    companion object {
        val DEFAULT: NextHopCarrier = Udp

        fun fromToken(token: String): NextHopCarrier? = entries.firstOrNull { it.token == token.lowercase() }
    }
}

/**
 * How the server certificate will be checked.
 *
 * Modelled as a value in the parsed configuration rather than as a pair of
 * nullable strings, so that a `when` over it cannot quietly omit the insecure
 * case. [Skipped] is what upstream does when neither `sni` nor `pin` is given —
 * and it is what every URL NowhereDash currently generates produces, so the
 * parser accepts it and states it. Whether to warn or refuse is the UI's call
 * (D-11).
 */
sealed interface CertificateVerification {
    /** Neither `sni` nor `pin`: upstream skips verification entirely. */
    data object Skipped : CertificateVerification

    /** Chain verification against this name. */
    data class Sni(
        val host: String,
    ) : CertificateVerification

    /**
     * Leaf certificate SHA-256, lower-case hex.
     *
     * Takes priority over [Sni] when a URL carries both, matching upstream.
     */
    data class Pin(
        val sha256: String,
    ) : CertificateVerification

    val isVerified: Boolean get() = this !is Skipped
}

sealed interface UrlReason : DecodeReason {
    data object Malformed : UrlReason {
        override val detail: String = "not a parseable URL"
    }

    data class WrongScheme(
        val scheme: String,
    ) : UrlReason {
        override val detail: String =
            "scheme '$scheme' is not a client import URL; the client scheme is 'nowhere'"
    }

    data object MissingHost : UrlReason {
        override val detail: String = "URL carries no host"
    }

    data class InvalidPort(
        val port: Int,
    ) : UrlReason {
        override val detail: String = "port $port is outside 1..65535"
    }

    data class InvalidCarrier(
        val parameter: String,
        val value: String,
    ) : UrlReason {
        override val detail: String = "$parameter='$value' is neither tcp nor udp"
    }

    data class InvalidAlpn(
        val length: Int,
    ) : UrlReason {
        override val detail: String = "alpn is $length bytes; 1-255 are required"
    }

    data class InvalidPin(
        val value: String,
    ) : UrlReason {
        override val detail: String = "pin must be 64 lower-case hex characters"
    }
}

/**
 * A node as it arrives from a dashboard, a QR code, or a paste. NW-P-23, NW-P-24.
 *
 * ```
 * nowhere://<pct-encoded shared key>@<host>:<port>?up=..&down=..&mux=..#<name>
 * ```
 *
 * **This is not `vector://`.** That is the command URL used to start the
 * upstream Rust process, and confusing the two is common enough that NowhereDash
 * has a test asserting its own responses never contain it. Any scheme but
 * `nowhere` is rejected here.
 *
 * Unknown and deprecated parameters are **ignored, never rejected** (NW-P-24).
 * Upstream's own rule is that unknown keys are ignored, and NowhereDash still
 * emits 1.7-era URLs carrying `pool=5`, a parameter 1.8 removed. Rejecting those
 * would reject a live dashboard's entire output.
 */
data class NowhereUrl(
    val sharedKey: SharedKey,
    val host: String,
    val port: Int,
    val up: NextHopCarrier,
    val down: NextHopCarrier,
    val mux: Boolean,
    val alpn: String,
    val certificateVerification: CertificateVerification,
    val rateMbps: Int,
    val etarMbps: Int,
    val displayName: String?,
) {
    /**
     * True when this configuration needs QUIC, which has not shipped.
     *
     * Upstream defaults both directions to `udp`, so a default configuration
     * lands here. NW-P-25 requires saying so and offering `tcp` — never silently
     * rewriting the user's configuration, and never failing without explanation.
     */
    val requiresQuic: Boolean
        get() = up == NextHopCarrier.Udp || down == NextHopCarrier.Udp

    fun toUrl(): String {
        val query =
            buildList {
                add("up=${up.token}")
                add("down=${down.token}")
                if (mux) add("mux=1")
                if (alpn != DEFAULT_ALPN) add("alpn=${percentEncode(alpn)}")
                when (val verification = certificateVerification) {
                    is CertificateVerification.Sni -> add("sni=${percentEncode(verification.host)}")
                    is CertificateVerification.Pin -> add("pin=${verification.sha256}")
                    CertificateVerification.Skipped -> Unit
                }
                if (rateMbps != 0) add("rate=$rateMbps")
                if (etarMbps != 0) add("etar=$etarMbps")
            }.joinToString("&")

        val fragment = displayName?.let { "#${percentEncode(it)}" }.orEmpty()
        // Encoded from the key's bytes, never through a String. `String(bytes)`
        // is a UTF-8 decode, and a key is not required to be text: the parser
        // decodes userinfo to bytes precisely so that it need not be. Rendering
        // a non-UTF-8 key through a String replaced every stray byte with U+FFFD
        // — a one-byte key of 0x80 came back as the three bytes EF BF BD — so a
        // node saved and reloaded authenticated as something else, with no
        // message anywhere. Found by fuzzing the round trip.
        return "$SCHEME://${percentEncode(sharedKey.toByteArray())}@$host:$port?$query$fragment"
    }

    companion object {
        const val SCHEME: String = "nowhere"
        const val DEFAULT_ALPN: String = "now/1"
        const val ALPN_MIN_LENGTH: Int = 1
        const val ALPN_MAX_LENGTH: Int = 255
        private const val PIN_LENGTH = 64
        private const val NONE = "none"

        fun parse(input: String): DecodeResult<NowhereUrl> {
            val trimmed = input.trim()
            val uri =
                try {
                    URI(trimmed)
                } catch (_: URISyntaxException) {
                    return invalid(UrlReason.Malformed)
                }

            val scheme = uri.scheme?.lowercase() ?: return invalid(UrlReason.Malformed)
            if (scheme != SCHEME) return invalid(UrlReason.WrongScheme(scheme))

            val host = uri.host ?: return invalid(UrlReason.MissingHost)
            if (host.isEmpty()) return invalid(UrlReason.MissingHost)
            val port = uri.port
            if (port !in 1..65535) return invalid(UrlReason.InvalidPort(port))

            val sharedKey =
                when (val extracted = SharedKey.fromUserInfo(uri.rawUserInfo)) {
                    is DecodeResult.Invalid -> return extracted
                    is DecodeResult.Ok -> extracted.value
                }

            val parameters = parseQuery(uri.rawQuery)

            val up =
                carrier("up", parameters["up"]) ?: return invalid(
                    UrlReason.InvalidCarrier("up", parameters.getValue("up")),
                )
            val down =
                carrier("down", parameters["down"]) ?: return invalid(
                    UrlReason.InvalidCarrier("down", parameters.getValue("down")),
                )

            val alpn = parameters["alpn"]?.let(::percentDecode) ?: DEFAULT_ALPN
            if (alpn.encodeToByteArray().size !in ALPN_MIN_LENGTH..ALPN_MAX_LENGTH) {
                return invalid(UrlReason.InvalidAlpn(alpn.encodeToByteArray().size))
            }

            val verification =
                when (val result = verification(parameters)) {
                    is DecodeResult.Invalid -> return result
                    is DecodeResult.Ok -> result.value
                }

            return NowhereUrl(
                sharedKey = sharedKey,
                host = host,
                port = port,
                up = up,
                down = down,
                // Anything but "1" is the default. Upstream treats mux as a
                // boolean switch, so an unparseable value must not be an error.
                mux = parameters["mux"] == "1",
                alpn = alpn,
                certificateVerification = verification,
                rateMbps = parameters["rate"]?.toIntOrNull() ?: 0,
                etarMbps = parameters["etar"]?.toIntOrNull() ?: 0,
                displayName = uri.rawFragment?.let(::percentDecode)?.takeIf { it.isNotEmpty() },
            ).ok()
        }

        private fun carrier(
            name: String,
            raw: String?,
        ): NextHopCarrier? = if (raw == null) NextHopCarrier.DEFAULT else NextHopCarrier.fromToken(raw)

        /** `pin` wins over `sni`, matching upstream's precedence. */
        private fun verification(parameters: Map<String, String>): DecodeResult<CertificateVerification> {
            val pin = parameters["pin"]?.takeIf { it.isNotEmpty() && !it.equals(NONE, ignoreCase = true) }
            if (pin != null) {
                val normalised = pin.lowercase()
                val valid = normalised.length == PIN_LENGTH && normalised.all { it in "0123456789abcdef" }
                return if (valid) {
                    CertificateVerification.Pin(normalised).ok()
                } else {
                    invalid(UrlReason.InvalidPin(pin))
                }
            }
            val sni =
                parameters["sni"]
                    ?.let(::percentDecode)
                    ?.takeIf { it.isNotEmpty() && !it.equals(NONE, ignoreCase = true) }
            return (sni?.let { CertificateVerification.Sni(it) } ?: CertificateVerification.Skipped).ok()
        }

        /**
         * Splits the raw query. Later duplicates lose to earlier ones, and
         * unknown keys survive here so that ignoring them stays a decision made
         * by the reader rather than by the splitter.
         */
        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            rawQuery.split('&').filter { it.isNotEmpty() }.forEach { pair ->
                val separator = pair.indexOf('=')
                val name = if (separator < 0) pair else pair.substring(0, separator)
                val value = if (separator < 0) "" else pair.substring(separator + 1)
                out.putIfAbsent(name.lowercase(), value)
            }
            return out
        }

        private fun percentDecode(value: String): String {
            val bytes = SharedKey.strictPercentDecode(value) ?: return value
            return String(bytes, Charsets.UTF_8)
        }

        private fun percentEncode(value: String): String = percentEncode(value.encodeToByteArray())

        /**
         * The byte-level encoder. Every other overload funnels through here.
         *
         * Taking bytes rather than text is the whole point: the shared key is
         * an arbitrary byte string, and any path that turns it into a String
         * first has already lost the bytes that are not valid UTF-8.
         */
        private fun percentEncode(value: ByteArray): String =
            buildString {
                value.forEach { byte ->
                    val code = byte.toInt() and 0xFF
                    val character = code.toChar()
                    if (character.isLetterOrDigit() && code < 0x80 || character in "-._~") {
                        append(character)
                    } else {
                        append('%').append("%02X".format(code))
                    }
                }
            }
    }
}
