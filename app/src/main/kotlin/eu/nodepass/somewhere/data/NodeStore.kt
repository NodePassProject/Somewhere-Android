// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import java.io.File
import java.io.IOException

/**
 * The nodes this device knows about.
 *
 * ## Stored as URLs, not as fields
 *
 * A node is persisted as the `nowhere://` text it came in as, and re-parsed by
 * the same parser that accepted it. The alternative — a serialised struct with a
 * field per parameter — creates a second definition of what a node is, and the
 * two drift: a parameter added to the URL grammar has to be added again here,
 * and one the parser rejects can still be constructed in storage. Keeping the
 * text means `NowhereUrl.parse` stays the only thing that decides what a valid
 * node is, and a stored node that no longer parses is dropped rather than
 * resurrected in a shape the protocol layer never agreed to.
 *
 * ## About the shared key
 *
 * Each line contains a Portal's shared key. It is stored in app-private
 * storage, in the clear, and that is a deliberate choice rather than an
 * oversight: an always-on VPN has to reconnect after a reboot with no user
 * present, so any key the user would have to unlock is a key the client cannot
 * use for the thing it is for. App-private storage plus `allowBackup="false"`
 * — already set in the manifest — is the realistic posture. It protects the key
 * from other apps and from cloud backup; it does not protect it from someone
 * holding an unlocked, rooted device, and nothing at this layer could.
 *
 * ## Durability
 *
 * Writes go to a temporary file and are renamed over the target, so a process
 * death mid-write leaves the previous list intact rather than a truncated one.
 */
class NodeStore(
    private val file: File,
) {
    /** A stored node, and the text it round-trips through. */
    data class Entry(
        val url: NowhereUrl,
        val line: String,
    )

    /**
     * Reads the list, skipping anything that no longer parses.
     *
     * Skipping rather than failing, for the same reason `Subscription.from`
     * does it: one unreadable line should cost the user that node, not the
     * other nineteen.
     */
    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            file
                .readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    (NowhereUrl.parse(line) as? DecodeResult.Ok)?.value?.let { Entry(it, line) }
                }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** Replaces the list. Each node is written as the parser's own rendering. */
    fun save(nodes: List<NowhereUrl>): Boolean {
        val body = nodes.joinToString("\n") { it.toUrl() }
        return try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(if (body.isEmpty()) "" else body + "\n")
            // renameTo rather than a second write: the point is that the target
            // is either the old list or the new one, never half of either.
            if (!temporary.renameTo(file)) {
                file.writeText(if (body.isEmpty()) "" else body + "\n")
                temporary.delete()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Adds [node] unless an identical one is already stored.
     *
     * Identity is the rendered URL, so re-importing the same link twice — which
     * is exactly what happens when someone taps a dashboard's import button
     * again — does not produce a second copy. A node that differs in any
     * parameter is a different node, because on this protocol it is.
     *
     * @return the list as it stands afterwards.
     */
    fun add(node: NowhereUrl): List<Entry> {
        val existing = load()
        val rendered = node.toUrl()
        if (existing.any { it.url.toUrl() == rendered }) return existing
        save(existing.map { it.url } + node)
        return load()
    }

    fun remove(node: NowhereUrl): List<Entry> {
        val rendered = node.toUrl()
        save(load().map { it.url }.filter { it.toUrl() != rendered })
        return load()
    }

    /** Replaces [old] with [new] in place, keeping its position in the list. */
    fun replace(
        old: NowhereUrl,
        new: NowhereUrl,
    ): List<Entry> {
        val rendered = old.toUrl()
        save(load().map { it.url }.map { if (it.toUrl() == rendered) new else it })
        return load()
    }
}
