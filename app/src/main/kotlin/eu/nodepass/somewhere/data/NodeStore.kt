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
    /**
     * Where a node came from, which decides what may be done to it.
     *
     * NW-D-04 is the reason this exists: a dashboard removes nodes from the
     * feed when a subscription lapses, and the honest response is to keep
     * showing the node and say why it went — not to delete it silently, and not
     * to report a network error. That is only possible if the app knows which
     * nodes a feed is allowed to speak for. A node the user pasted is theirs
     * and no refresh may touch it.
     */
    enum class Origin(
        val marker: String,
    ) {
        /** Pasted, scanned, or arrived by deep link. Only the user removes it. */
        Manual("manual"),

        /** Currently in the subscription feed. */
        Subscription("sub"),

        /** Was in the feed and is not any more. Kept, and marked. */
        RemovedFromFeed("sub-removed"),
        ;

        companion object {
            fun fromMarker(marker: String): Origin? = entries.firstOrNull { it.marker == marker }
        }
    }

    /**
     * A stored node, and the text it round-trips through.
     *
     * [source] is the id of the subscription a feed node belongs to, and is
     * meaningless for a manual one. With more than one subscription, a refresh
     * of one must not mark the other's nodes as withdrawn — which it would, if
     * all a node knew was that it came from *a* feed. A node written before
     * there could be more than one has the empty id, which is also the id the
     * subscription written then reads back with, so the two still match.
     */
    data class Entry(
        val url: NowhereUrl,
        val line: String,
        val origin: Origin = Origin.Manual,
        val source: String = "",
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
                .mapNotNull(::parseLine)
        } catch (_: IOException) {
            emptyList()
        }
    }

    /**
     * `origin<TAB>url`, or a bare url.
     *
     * A bare line is [Origin.Manual]. That is not only a default — it is the
     * migration: every node written before origins existed was one the user
     * added by hand, and reading them as manual is both correct and free. A
     * marker that is not recognised is treated the same way, because inventing
     * an origin for a node is worse than assuming the one that grants a feed no
     * authority over it.
     */
    private fun parseLine(line: String): Entry? {
        val separator = line.indexOf('\t')
        // `marker` or `marker:source`. The source is a subscription id, which
        // this store generates and which never contains a tab or a colon.
        val tag = if (separator < 0) "" else line.take(separator)
        val origin = if (separator < 0) Origin.Manual else Origin.fromMarker(tag.substringBefore(':')) ?: Origin.Manual
        val source = if (origin == Origin.Manual) "" else tag.substringAfter(':', "")
        val urlText = if (separator < 0) line else line.substring(separator + 1)
        return (NowhereUrl.parse(urlText) as? DecodeResult.Ok)?.value?.let { Entry(it, urlText, origin, source) }
    }

    private fun render(entry: Entry): String =
        when {
            entry.origin == Origin.Manual -> entry.url.toUrl()
            entry.source.isEmpty() -> "${entry.origin.marker}\t${entry.url.toUrl()}"
            else -> "${entry.origin.marker}:${entry.source}\t${entry.url.toUrl()}"
        }

    /** Replaces the list, treating every node as manual. */
    fun save(nodes: List<NowhereUrl>): Boolean = saveEntries(nodes.map { Entry(it, it.toUrl(), Origin.Manual) })

    /** Replaces the list. Each node is written as the parser's own rendering. */
    fun saveEntries(nodes: List<Entry>): Boolean {
        val body = nodes.joinToString("\n") { render(it) }
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
    fun add(
        node: NowhereUrl,
        origin: Origin = Origin.Manual,
    ): List<Entry> {
        val existing = load()
        val rendered = node.toUrl()
        if (existing.any { it.url.toUrl() == rendered }) return existing
        saveEntries(existing + Entry(node, rendered, origin))
        return load()
    }

    /**
     * Lets go of subscription [source]'s nodes when the subscription is
     * forgotten.
     *
     * The nodes it still lists become the user's own, manual, and keep
     * working: they are the user's nodes, not the subscription's, and
     * forgetting a credential is not asking to lose a working list. The ones it
     * had already withdrawn go — nothing vouches for them any more, and as
     * manual nodes they would read as working Portals that simply fail.
     */
    fun releaseSource(source: String): List<Entry> {
        saveEntries(
            load().mapNotNull { entry ->
                when {
                    entry.origin == Origin.Manual || entry.source != source -> entry
                    entry.origin == Origin.Subscription -> entry.copy(origin = Origin.Manual, source = "")
                    else -> null
                }
            },
        )
        return load()
    }

    /**
     * Makes feed nodes whose subscription is gone the user's own.
     *
     * A listed one becomes manual. A withdrawn one is left as it is: it was
     * shown withdrawn before and still is, and it is the user's to delete.
     *
     * @return the list afterwards, written only if something changed.
     */
    fun adoptOrphans(knownSources: Set<String>): List<Entry> {
        val existing = load()
        val adopted =
            existing.map { entry ->
                if (entry.origin == Origin.Subscription && entry.source !in knownSources) {
                    entry.copy(origin = Origin.Manual, source = "")
                } else {
                    entry
                }
            }
        if (adopted == existing) return existing
        saveEntries(adopted)
        return load()
    }

    /** [add] for several nodes, with one write. */
    fun addAll(nodes: List<NowhereUrl>): List<Entry> {
        val existing = load()
        val known = existing.map { it.url.toUrl() }.toMutableSet()
        val added = nodes.filter { known.add(it.toUrl()) }.map { Entry(it, it.toUrl(), Origin.Manual) }
        if (added.isEmpty()) return existing
        saveEntries(existing + added)
        return load()
    }

    fun remove(node: NowhereUrl): List<Entry> {
        val rendered = node.toUrl()
        saveEntries(load().filter { it.url.toUrl() != rendered })
        return load()
    }

    /**
     * Reconciles the stored list against a freshly fetched feed.
     *
     * NW-D-04, in one place:
     *
     * - Manual nodes are untouched. A feed has no authority over them.
     * - A node in the feed is present, whether it is new or was previously
     *   marked as removed — a subscription that comes back is not a new node.
     * - A node the feed no longer lists is **kept and marked**, not deleted.
     *   Deleting it would leave the user with a shorter list and no explanation,
     *   which is the outcome the requirement exists to prevent.
     */
    fun reconcileWithFeed(
        feed: List<NowhereUrl>,
        /** The subscription this feed came from. Only its own nodes are touched. */
        source: String = "",
    ): List<Entry> {
        // Deduplicated first, because dashboards do repeat themselves — the
        // same Portal under two names, or a feed concatenated from two sources.
        // Without this a duplicated entry was added twice on the refresh that
        // introduced it, and a list that grows on its own is the kind of bug
        // nobody reports until they have forty nodes.
        val deduplicated = feed.distinctBy { it.toUrl() }
        val fresh = deduplicated.associateBy { it.toUrl() }
        val existing = load()
        val kept =
            existing.map { entry ->
                val listed = entry.url.toUrl() in fresh
                when {
                    entry.origin == Origin.Manual -> entry
                    entry.source == source && listed -> entry.copy(origin = Origin.Subscription)
                    entry.source == source -> entry.copy(origin = Origin.RemovedFromFeed)
                    // Another subscription's node. Untouched — unless that
                    // subscription has withdrawn it and this one lists it, in
                    // which case it is this one's now: it is live, and leaving
                    // it marked as withdrawn would hide a working node behind
                    // the other subscription's expiry.
                    entry.origin == Origin.RemovedFromFeed && listed -> entry.copy(origin = Origin.Subscription, source = source)
                    else -> entry
                }
            }
        val known = kept.map { it.url.toUrl() }.toSet()
        val added = deduplicated.filter { it.toUrl() !in known }.map { Entry(it, it.toUrl(), Origin.Subscription, source) }
        saveEntries(kept + added)
        return load()
    }

    /** Replaces [old] with [new] in place, keeping its position in the list. */
    fun replace(
        old: NowhereUrl,
        new: NowhereUrl,
    ): List<Entry> {
        val rendered = old.toUrl()
        saveEntries(load().map { if (it.url.toUrl() == rendered) it.copy(url = new, line = new.toUrl()) else it })
        return load()
    }
}
