// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a subscription from a dashboard. NW-D-01.
 *
 * Uses `HttpURLConnection` rather than an HTTP library: this is one infrequent
 * GET returning a few lines of text, and neither connection pooling, interceptors
 * nor HTTP/2 buys anything here. A dependency on the authentication path would
 * have to earn itself; this one cannot.
 *
 * ## The subscription URL is a password
 *
 * Everything unusual about this class follows from that. The token grants the
 * node list to anyone holding it, so:
 *
 * - No URL is ever put in an exception message, a log line or an error detail —
 *   [SubscriptionReason] carries causes, never the request.
 * - Plaintext transport is reported rather than silently accepted, so a caller
 *   must decide about it. It is not refused outright here, because refusing is a
 *   policy and this is a fetcher.
 * - Redirects are **not followed automatically**: a redirect can move a token
 *   from an HTTPS host to an HTTP one, or to a host the user never agreed to,
 *   and neither should happen without the caller deciding.
 */
class SubscriptionFetcher(
    private val clientVersion: String,
    private val capabilities: Set<Capability> = setOf(Capability.MUX),
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
    private val maxBodyBytes: Int = 512 * 1024,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun fetch(subscriptionUrl: String): DecodeResult<Subscription> {
        val prepared =
            when (val result = SubscriptionEndpoint.prepare(subscriptionUrl, clientVersion, capabilities)) {
                is SubscriptionEndpoint.Result.Rejected ->
                    return invalid(SubscriptionReason.Unusable(result.reason.name))

                is SubscriptionEndpoint.Result.Ready -> result
            }

        val connection =
            try {
                open(URL(prepared.requestUrl)).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "text/plain")
                    setRequestProperty("User-Agent", "Somewhere/$clientVersion")
                }
            } catch (error: IOException) {
                // The URL is deliberately absent from this message.
                return invalid(SubscriptionReason.Transport(error.javaClass.simpleName))
            }

        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                return invalid(SubscriptionReason.HttpStatus(status))
            }

            val body = connection.inputStream.use { stream -> readBounded(stream, maxBodyBytes) }

            Subscription.from(
                body = body,
                usageHeader = connection.getHeaderField(HEADER_USERINFO),
                titleHeader = connection.getHeaderField(HEADER_TITLE),
                plaintext = prepared.plaintextTransport,
            )
        } catch (error: IOException) {
            invalid(SubscriptionReason.Transport(error.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads at most [limit] bytes.
     *
     * Hand-rolled rather than `readNBytes`, which needs API 33 while this app
     * supports 26 — the kind of gap a JVM unit test cannot see, because the JDK
     * has the method and an old phone does not. Lint caught it.
     *
     * The limit is the point: the response is a few lines of text, and a body
     * that keeps coming is either a broken dashboard or something that is not a
     * dashboard at all. Neither should be allowed to exhaust memory.
     */
    private fun readBounded(
        stream: java.io.InputStream,
        limit: Int,
    ): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (buffer.size() < limit) {
            val count = stream.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (count <= 0) break
            buffer.write(chunk, 0, count)
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val HEADER_USERINFO: String = "subscription-userinfo"
        const val HEADER_TITLE: String = "profile-title"
    }
}
