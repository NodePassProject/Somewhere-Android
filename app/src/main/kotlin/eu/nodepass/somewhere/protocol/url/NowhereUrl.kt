// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.url

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.auth.AuthReason
import eu.nodepass.somewhere.protocol.auth.SharedKey
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import java.net.URI
import java.net.URISyntaxException

/** The carrier a node connects over, as `net` names it. */
enum class NextHopCarrier(
    val token: String,
) {
    /** TLS 1.3 over TCP, one connection per flow. */
    Tcp("tcp"),

    /** QUIC, one connection carrying every flow. */
    Udp("udp"),
    ;

    companion object {
        /**
         * `tcp`, per `docs/configuration.md` § Share links.
         *
         * **This flipped in v3.** v1 defaulted both directions to `udp`, so a
         * bare v1 link was a QUIC node; a bare v3 link is a TLS one.
         */
        val DEFAULT: NextHopCarrier = Tcp

        fun fromToken(token: String): NextHopCarrier? = entries.firstOrNull { it.token == token }
    }
}

/**
 * How the server certificate will be checked.
 *
 * Two modes and no third. v3 states it plainly — "Normal clients verify system
 * CA trust and hostname, or an exact certificate SHA-256 pin" — and the `sni`
 * default is the endpoint host, so a link carrying neither parameter is a link
 * asking for ordinary chain verification against the host it names. v1's
 * "verify nothing" mode is gone, and there is deliberately no way to spell it.
 *
 * A Portal on its generated certificate (`tls=1`) therefore needs a `pin`.
 */
sealed interface CertificateVerification {
    /** Chain and hostname verification against this name. */
    data class Sni(
        val host: String,
    ) : CertificateVerification

    /**
     * Leaf certificate SHA-256, lower-case hex.
     *
     * Takes priority over [Sni] when a URL carries both, matching upstream. The
     * chain is not consulted: a pinned certificate is usually self-signed.
     */
    data class Pin(
        val sha256: String,
    ) : CertificateVerification {
        /**
         * The same value as bytes, for the carriers that compare it there.
         *
         * The TLS path compares hex strings because it has a certificate object
         * to hash; the QUIC path hands 32 bytes across a JNI boundary to be
         * compared in C. One conversion, here, so the two cannot disagree about
         * what a pin is.
         */
        val bytes: ByteArray
            get() =
                ByteArray(sha256.length / 2) { index ->
                    ((digit(sha256[index * 2]) shl 4) or digit(sha256[index * 2 + 1])).toByte()
                }

        private fun digit(character: Char): Int =
            when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                // Unreachable through the parser, which accepts lower-case hex
                // of exactly the right length and nothing else. Stated rather
                // than assumed, because a pin built by hand and silently
                // mistranslated would verify against an arbitrary digest.
                else -> throw IllegalStateException("a pin holds hex and nothing else")
            }
    }
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

    /**
     * `host/tcp:P/udp:Q` and its relatives.
     *
     * Service URLs accept carrier paths and share links do not. A dashboard
     * that targets Nowhere 2.x emits them when a Portal's two listeners use
     * different ports; the link names a real Portal, and saying which form it
     * is in is what lets someone ask for the other.
     */
    data class CarrierPath(
        val path: String,
    ) : UrlReason {
        override val detail: String =
            "share links take HOST:PORT; '$path' is a carrier path, which only service URLs accept"
    }

    data class InvalidCarrier(
        val value: String,
    ) : UrlReason {
        override val detail: String = "net='$value' is neither tcp nor udp"
    }

    data class InvalidMorph(
        val value: String,
    ) : UrlReason {
        override val detail: String = "morph='$value' is neither 0 nor 1"
    }

    data class InvalidSni(
        val value: String,
    ) : UrlReason {
        override val detail: String = "sni must be an ASCII DNS name"
    }

    data class InvalidPin(
        val value: String,
    ) : UrlReason {
        override val detail: String = "pin must be 64 lower-case hex characters"
    }

    data class InvalidRate(
        val parameter: String,
        val value: String,
    ) : UrlReason {
        override val detail: String = "$parameter='$value' is not a whole number of Mbps in 0..2147483647"
    }
}

/**
 * A node as it arrives from a dashboard, a QR code, or a paste.
 * `docs/configuration.md` § Share links.
 *
 * ```
 * nowhere://<pct-encoded shared key>@<host>:<port>[?net=tcp|udp][&morph=0|1][&sni=..][&pin=..]#<name>
 * ```
 *
 * **This is not `vector://`.** That is the command URL used to start the
 * upstream Rust process, and confusing the two is common enough that NowhereDash
 * has a test asserting its own responses never contain it. Any scheme but
 * `nowhere` is rejected here.
 *
 * ## Parameters
 *
 * Unknown parameters are **ignored, never rejected**. For a recognised one the
 * **first occurrence wins, and an invalid first value is an error**:
 * `morph=1&morph=0` is Morph on, `morph=invalid&morph=1` does not parse. Both
 * are upstream's rules, written out in its parsing table.
 *
 * ## The dashboard that has not caught up
 *
 * NowhereDash targets Nowhere 2.1.1 and emits `up=…&down=…[&mux=1]` with no
 * `net`. Read strictly, those are unknown parameters, and the link would land
 * on the `tcp` default — a QUIC-only node dialled over TLS, failing with a
 * timeout. So when `net` is absent and `up` and `down` agree, their value is
 * taken as `net`. When they disagree the node asked for a split flow, which v3
 * cannot carry, and the default applies. `mux` and `alpn` are ignored: v3 has
 * no Mux and one fixed ALPN.
 */
data class NowhereUrl(
    val sharedKey: SharedKey,
    val host: String,
    val port: Int,
    val net: NextHopCarrier = NextHopCarrier.DEFAULT,
    /** Shared-key wire masking. On unless the link says `morph=0`; both peers must agree. */
    val morph: Boolean = true,
    val certificateVerification: CertificateVerification = CertificateVerification.Sni(host),
    val rateMbps: Int = 0,
    val etarMbps: Int = 0,
    val displayName: String? = null,
) {
    /** True when this node connects over QUIC. */
    val usesQuic: Boolean get() = net == NextHopCarrier.Udp

    fun toUrl(): String {
        val query =
            buildList {
                if (net != NextHopCarrier.DEFAULT) add("net=${net.token}")
                if (!morph) add("morph=0")
                when (val verification = certificateVerification) {
                    // The default is the endpoint host, so naming it again
                    // would be noise in every link this client shares.
                    is CertificateVerification.Sni ->
                        if (verification.host != host) add("sni=${percentEncode(verification.host)}")
                    is CertificateVerification.Pin -> add("pin=${verification.sha256}")
                }
                if (rateMbps != 0) add("rate=$rateMbps")
                if (etarMbps != 0) add("etar=$etarMbps")
            }.joinToString("&")

        val fragment = displayName?.let { "#${percentEncode(it)}" }.orEmpty()
        val authority = if (':' in host) "[$host]" else host
        // Encoded from the key's bytes, never through a String. `String(bytes)`
        // is a UTF-8 decode, and a key is not required to be text: the parser
        // decodes userinfo to bytes precisely so that it need not be. Found by
        // fuzzing the round trip, when a one-byte key of 0x80 came back as the
        // three bytes EF BF BD.
        val separator = if (query.isEmpty()) "" else "?"
        return "$SCHEME://${percentEncode(sharedKey.toByteArray())}@$authority:$port$separator$query$fragment"
    }

    companion object {
        const val SCHEME: String = "nowhere"
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

            // Before the port: a carrier path has no authority port, and
            // reporting "port -1" for it would name the wrong problem.
            val path = uri.rawPath.orEmpty()
            if (path.isNotEmpty() && path != "/") return invalid(UrlReason.CarrierPath(path))

            // `URI` keeps an IPv6 literal's brackets. They are URL syntax, not
            // part of the address, and a name to verify a certificate against
            // or to hand to a resolver does not have them.
            val host = uri.host?.removeSurrounding("[", "]") ?: return invalid(UrlReason.MissingHost)
            if (host.isEmpty()) return invalid(UrlReason.MissingHost)
            val port = uri.port
            if (port !in 1..65535) return invalid(UrlReason.InvalidPort(port))

            val sharedKey =
                when (val extracted = SharedKey.fromUserInfo(uri.rawUserInfo)) {
                    is DecodeResult.Invalid -> return extracted
                    is DecodeResult.Ok -> extracted.value
                }
            if (!SharedKey.isServiceFormat(sharedKey.toByteArray())) return invalid(AuthReason.NotServiceFormat)

            val parameters = parseQuery(uri.rawQuery)

            val net =
                when (val raw = parameters["net"]) {
                    null -> legacyCarrier(parameters) ?: NextHopCarrier.DEFAULT
                    else -> NextHopCarrier.fromToken(raw) ?: return invalid(UrlReason.InvalidCarrier(raw))
                }

            val morph =
                when (val raw = parameters["morph"]) {
                    null, "1" -> true
                    "0" -> false
                    else -> return invalid(UrlReason.InvalidMorph(raw))
                }

            val verification =
                when (val result = verification(parameters, host)) {
                    is DecodeResult.Invalid -> return result
                    is DecodeResult.Ok -> result.value
                }

            val rate = mbps("rate", parameters) ?: return invalid(UrlReason.InvalidRate("rate", parameters.getValue("rate")))
            val etar = mbps("etar", parameters) ?: return invalid(UrlReason.InvalidRate("etar", parameters.getValue("etar")))

            return NowhereUrl(
                sharedKey = sharedKey,
                host = host,
                port = port,
                net = net,
                morph = morph,
                certificateVerification = verification,
                rateMbps = rate,
                etarMbps = etar,
                displayName = uri.rawFragment?.let(::percentDecode)?.takeIf { it.isNotEmpty() },
            ).ok()
        }

        /**
         * A Nowhere 2.x dashboard's `up`/`down`, when they agree. See the class
         * comment; null means "no usable hint", never an error, because these
         * are not v3 parameters and v3 ignores what it does not recognise.
         */
        private fun legacyCarrier(parameters: Map<String, String>): NextHopCarrier? {
            val up = parameters["up"]?.let(NextHopCarrier::fromToken) ?: return null
            val down = parameters["down"]?.let(NextHopCarrier::fromToken) ?: return null
            return up.takeIf { it == down }
        }

        /** `0..=2147483647`, absent meaning 0. Null for a value that is present and wrong. */
        private fun mbps(
            name: String,
            parameters: Map<String, String>,
        ): Int? {
            val raw = parameters[name] ?: return 0
            return raw.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        }

        /**
         * `pin` wins over `sni`, matching upstream's precedence, and the
         * default for both is chain verification against [host].
         */
        private fun verification(
            parameters: Map<String, String>,
            host: String,
        ): DecodeResult<CertificateVerification> {
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
            val rawSni = parameters["sni"]?.let(::percentDecode)
            val sni = rawSni?.takeIf { it.isNotEmpty() && !it.equals(NONE, ignoreCase = true) }
            if (sni != null && !isAsciiDnsName(sni)) return invalid(UrlReason.InvalidSni(sni))
            return CertificateVerification.Sni(sni ?: host).ok()
        }

        /** Letters, digits, hyphens and dots, in non-empty labels; what `ASCII DNS name` admits. */
        private fun isAsciiDnsName(name: String): Boolean =
            name.length <= 253 &&
                name.split('.').all { label ->
                    label.length in 1..63 &&
                        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' } &&
                        !label.startsWith('-') &&
                        !label.endsWith('-')
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
