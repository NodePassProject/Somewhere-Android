// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.subscription.SubscriptionUsage
import java.io.File
import java.io.IOException

/**
 * The subscriptions: each one's URL, and what its dashboard last said about it.
 *
 * ## Several, in one file
 *
 * Records are separated by a line holding only `---`. No value can produce
 * that line: every value is written as `key=value` with line breaks stripped,
 * so a separator cannot be smuggled in through a title any more than a second
 * `url=` can. A file written when there could be only one subscription has no
 * separator and no `id=`, and reads back as one record with the empty id — the
 * same id the nodes it fetched were stored under, so nothing has to migrate.
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
    /** Where new ids come from. Injected so a test can name its subscriptions. */
    private val newId: () -> String = ::randomId,
) {
    data class Record(
        val url: String,
        val title: String?,
        val usage: SubscriptionUsage?,
        val fetchedAtEpochMillis: Long?,
        /**
         * Stable for the life of the subscription, and what its nodes point
         * back to. Not the URL: a dashboard that rotates a token changes the
         * URL, and the nodes are still that subscription's.
         */
        val id: String = "",
    )

    /** Every subscription, in the order they were added. */
    fun loadAll(): List<Record> {
        if (!file.exists()) return emptyList()
        return try {
            file
                .readText()
                .split(SEPARATOR_LINE)
                .mapNotNull(::parseRecord)
                // A repeated id would make two subscriptions claim the same
                // nodes. The first one keeps it.
                .distinctBy { it.id }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** The first subscription, or null. */
    fun load(): Record? = loadAll().firstOrNull()

    private fun parseRecord(text: String): Record? {
        // First occurrence wins, not last. `toMap()` keeps the last value
        // for a repeated key, which is precisely what a field-injection
        // attempt needs: append a second `url=` line and the real one is
        // discarded. Taking the first is the second half of the defence,
        // the first being that values are stripped on write.
        val fields = LinkedHashMap<String, String>()
        text.lines().forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) fields.putIfAbsent(line.take(index), line.substring(index + 1))
        }

        val url = fields["url"]?.takeIf { it.isNotBlank() } ?: return null
        val download = fields["download"]?.toLongOrNull()
        return Record(
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
            id = fields["id"].orEmpty().filter(::isIdCharacter),
        )
    }

    /**
     * Strips anything that could end a line or a field.
     *
     * **The title arrives in the `profile-title` response header**, which is the
     * dashboard's to set, and this format is line-based. A title carrying a
     * newline and a `url=` line rewrote the stored credential to point wherever
     * the dashboard liked — and the next refresh would have sent the real token
     * there. Found by fuzzing the store rather than by review.
     */
    private fun oneLine(value: String): String = value.filterNot { it == '\n' || it == '\r' || it.isISOControl() }

    private fun render(record: Record): String =
        buildList {
            // The URL goes first and on its own line so that a truncated
            // write loses the figures rather than the credential.
            add("url=${oneLine(record.url)}")
            if (record.id.isNotEmpty()) add("id=${record.id}")
            record.title?.let { add("title=${oneLine(it)}") }
            record.usage?.let { usage ->
                add("download=${usage.downloadBytes}")
                usage.totalBytes?.let { add("total=$it") }
                usage.expiresAtEpochSeconds?.let { add("expires=$it") }
            }
            record.fetchedAtEpochMillis?.let { add("fetched=$it") }
        }.joinToString("\n")

    /**
     * A new subscription for [url], or the existing one if it is already here.
     *
     * Adding a URL twice is what happens when a dashboard's import button is
     * tapped twice; it must not produce two subscriptions fighting over one
     * set of nodes.
     */
    fun add(url: String): Record {
        val existing = loadAll()
        existing.firstOrNull { it.url == url }?.let { return it }
        val taken = existing.map { it.id }.toSet()
        var id = newId()
        while (id in taken || id.isEmpty()) id = newId()
        val record = Record(url, title = null, usage = null, fetchedAtEpochMillis = null, id = id)
        saveAll(existing + record)
        return record
    }

    /** Replaces the record with [record]'s id, or appends it. */
    fun save(record: Record): Boolean {
        val existing = loadAll()
        val replaced = existing.any { it.id == record.id }
        return saveAll(if (replaced) existing.map { if (it.id == record.id) record else it } else existing + record)
    }

    fun saveAll(records: List<Record>): Boolean =
        if (records.isEmpty()) clear() else write(records.joinToString("\n$SEPARATOR\n") { render(it) })

    /** Forgets one subscription, credential included. */
    fun remove(id: String): Boolean = saveAll(loadAll().filter { it.id != id })

    /** Forgets every subscription, credentials included. */
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

    companion object {
        private const val SEPARATOR = "---"

        /** The separator as a whole line, whatever line ending surrounds it. */
        private val SEPARATOR_LINE = Regex("(?m)^---\\s*$")

        /** Ids are what this store makes them: lowercase hex, nothing else. */
        private fun isIdCharacter(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

        private fun randomId(): String {
            val bytes = ByteArray(4)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
