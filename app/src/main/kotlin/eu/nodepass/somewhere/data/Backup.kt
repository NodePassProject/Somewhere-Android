// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Everything the app keeps, as one file to move to another device and back.
 *
 * ## What is in it
 *
 * Each store's own file, verbatim, under the path the store keeps it at. Not a
 * second schema: every store already defines what it reads and defends itself
 * against what it cannot, and a backup that re-described them would be a
 * second definition of each, drifting from the first. Restoring writes the
 * text back and the stores read it as they would after any restart.
 *
 * **It is plaintext and it holds credentials** — every node's shared key and
 * every subscription token. The requester chose plaintext over a passphrase;
 * the screen that exports says so before the file is written.
 *
 * ## What is not
 *
 * The launch guard's crash counter, which describes this device. Nothing
 * that is not in [PATHS] is read from a backup either: the paths are a fixed
 * list rather than whatever the file names, so a crafted backup cannot write
 * anywhere else in the app's storage.
 */
object Backup {
    /** The stores, by their path under the app's files directory. */
    val PATHS: List<String> =
        listOf(
            "nodes/nodes.txt",
            "nodes/choice.txt",
            "subscription/subscription.txt",
            "subscription/refresh.txt",
            "apps/selection.txt",
            "routing/rules.txt",
            "routing/settings.txt",
            "settings/app.txt",
            "network/settings.txt",
        )

    const val FORMAT = "somewhere-backup"
    const val VERSION = 1

    /** Larger than any real backup — a rule set is the bulk — and small enough to refuse a wrong file early. */
    const val MAX_BYTES = 8 * 1024 * 1024

    @Serializable
    private data class Document(
        val format: String,
        val version: Int,
        val app: String = "",
        val files: Map<String, String> = emptyMap(),
    )

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /** Why a file could not be restored. Each has its own message on screen. */
    enum class Refusal {
        TooLarge,
        NotABackup,
        FromNewerVersion,
        Empty,
    }

    sealed interface Read {
        data class Ok(
            val files: Map<String, String>,
        ) : Read

        data class Refused(
            val why: Refusal,
        ) : Read
    }

    /** The stores under [root] as one document. Absent files are absent from it. */
    fun export(
        root: File,
        appVersion: String,
    ): String {
        val files =
            PATHS
                .mapNotNull { path ->
                    val file = File(root, path)
                    try {
                        if (file.isFile) path to file.readText() else null
                    } catch (_: IOException) {
                        null
                    }
                }.toMap()
        return json.encodeToString(Document.serializer(), Document(FORMAT, VERSION, appVersion, files))
    }

    /** Reads a backup without touching anything. */
    fun read(bytes: ByteArray): Read {
        if (bytes.size > MAX_BYTES) return Read.Refused(Refusal.TooLarge)
        val document =
            try {
                json.decodeFromString(Document.serializer(), bytes.decodeToString())
            } catch (_: SerializationException) {
                return Read.Refused(Refusal.NotABackup)
            } catch (_: IllegalArgumentException) {
                return Read.Refused(Refusal.NotABackup)
            }
        if (document.format != FORMAT) return Read.Refused(Refusal.NotABackup)
        // A newer version may have moved or reshaped a store this one would
        // then misread. Refused rather than half-applied.
        if (document.version > VERSION) return Read.Refused(Refusal.FromNewerVersion)
        val known = document.files.filterKeys { it in PATHS }
        if (known.isEmpty()) return Read.Refused(Refusal.Empty)
        return Read.Ok(known)
    }

    /**
     * Replaces the stores under [root] with [files].
     *
     * A store the backup does not have is removed, not kept: restoring is
     * "make this device what the backup describes", and a subscription left
     * behind from before would be a credential the user thought was replaced.
     * Each file is written to a temporary and renamed, as the stores do.
     */
    fun restore(
        root: File,
        files: Map<String, String>,
    ): Boolean {
        var ok = true
        for (path in PATHS) {
            val target = File(root, path)
            val text = files[path]
            try {
                if (text == null) {
                    if (target.exists() && !target.delete()) ok = false
                    continue
                }
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${target.name}.restore")
                temporary.writeText(text)
                if (!temporary.renameTo(target)) {
                    target.writeText(text)
                    temporary.delete()
                }
            } catch (_: IOException) {
                ok = false
            }
        }
        return ok
    }
}
