// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.subscription.SubscriptionUsage
import java.io.File
import java.io.IOException

/**
 * The subscription: its URL, and what the dashboard last said about it.
 *
 * ## The URL is the credential
 *
 * There is no account and no password. The subscription URL *is* the bearer
 * token — anyone holding it can fetch the node list and the traffic figures.
 * That governs everything here:
 *
 * - It is stored in app-private storage, and `allowBackup="false"` keeps it out
 *   of cloud backups.
 * - It is never rendered into a log, a share sheet, or an error message.
 *   `SubscriptionEndpoint.redactForLogging` is what to use when a URL genuinely
 *   has to be mentioned.
 * - It is kept in its own file rather than alongside the node list, so that a
 *   future "export my nodes" cannot sweep it up by accident.
 *
 * ## Why the last figures are kept
 *
 * Quota and expiry are cached so the node list can show them before a refresh
 * completes, and offline. That is honest only because the screen also shows how
 * old they are — a figure with its age attached is a measurement; the same
 * figure presented as current is a claim.
 */
class SubscriptionStore(
    private val file: File,
) {
    data class Record(
        val url: String,
        val title: String?,
        val usage: SubscriptionUsage?,
        val fetchedAtEpochMillis: Long?,
    )

    fun load(): Record? {
        if (!file.exists()) return null
        return try {
            val fields =
                file
                    .readLines()
                    .mapNotNull { line ->
                        val index = line.indexOf('=')
                        if (index <= 0) null else line.take(index) to line.substring(index + 1)
                    }.toMap()

            val url = fields["url"]?.takeIf { it.isNotBlank() } ?: return null
            val download = fields["download"]?.toLongOrNull()
            Record(
                url = url,
                title = fields["title"]?.takeIf { it.isNotBlank() },
                usage =
                    download?.let {
                        SubscriptionUsage(
                            downloadBytes = it,
                            totalBytes = fields["total"]?.toLongOrNull(),
                            expiresAtEpochSeconds = fields["expires"]?.toLongOrNull(),
                        )
                    },
                fetchedAtEpochMillis = fields["fetched"]?.toLongOrNull(),
            )
        } catch (_: IOException) {
            null
        }
    }

    fun save(record: Record): Boolean =
        write(
            buildList {
                // The URL goes first and on its own line so that a truncated
                // write loses the figures rather than the credential.
                add("url=${record.url}")
                record.title?.let { add("title=$it") }
                record.usage?.let { usage ->
                    add("download=${usage.downloadBytes}")
                    usage.totalBytes?.let { add("total=$it") }
                    usage.expiresAtEpochSeconds?.let { add("expires=$it") }
                }
                record.fetchedAtEpochMillis?.let { add("fetched=$it") }
            }.joinToString("\n"),
        )

    /** Forgets the subscription entirely, credential included. */
    fun clear(): Boolean =
        try {
            !file.exists() || file.delete()
        } catch (_: IOException) {
            false
        }

    private fun write(body: String): Boolean =
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(body + "\n")
            if (!temporary.renameTo(file)) {
                file.writeText(body + "\n")
                temporary.delete()
            }
            true
        } catch (_: IOException) {
            false
        }
}
