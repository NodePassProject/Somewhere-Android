// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.ImportLink
import eu.nodepass.somewhere.protocol.url.NowhereUrl

/**
 * Several links pasted at once, one per line, sorted into what they are.
 *
 * Every line goes down the same two parsers a single link does — unwrapped,
 * then read as a node or recognised as a subscription — so a batch reaches no
 * code a single paste cannot. What a batch adds is only bookkeeping: which
 * lines were understood, which were not and why, and no line counted twice.
 */
data class ImportBatch(
    val nodes: List<NowhereUrl>,
    val subscriptions: List<String>,
    val rejected: List<Rejected>,
) {
    /** A line that was neither, with its 1-based number in what was pasted. */
    data class Rejected(
        val line: Int,
        val reason: DecodeReason,
    )

    val isEmpty: Boolean get() = nodes.isEmpty() && subscriptions.isEmpty()

    /**
     * A subscription link that would hand its credential to the network in the
     * clear. [detail] is for logs; the screen renders its own translated
     * sentence for this, the one a single plaintext link already gets.
     */
    data object PlaintextSubscription : DecodeReason {
        override val detail: String = "a subscription link must use https; over http its token is readable on the way"
    }

    companion object {
        /** Whether [text] holds more than one link and should be read as a batch. */
        fun isBatch(text: String): Boolean = text.lineSequence().count { it.isNotBlank() } > 1

        fun read(text: String): ImportBatch {
            val nodes = LinkedHashMap<String, NowhereUrl>()
            val subscriptions = LinkedHashSet<String>()
            val rejected = mutableListOf<Rejected>()
            text.lines().forEachIndexed { index, raw ->
                val line = ImportLink.unwrap(raw.trim())
                if (line.isEmpty()) return@forEachIndexed
                when {
                    line.startsWith("https://", ignoreCase = true) -> subscriptions += line
                    line.startsWith("http://", ignoreCase = true) -> rejected += Rejected(index + 1, PlaintextSubscription)
                    else ->
                        when (val parsed = NowhereUrl.parse(line)) {
                            is DecodeResult.Ok -> nodes.putIfAbsent(parsed.value.toUrl(), parsed.value)
                            is DecodeResult.Invalid -> rejected += Rejected(index + 1, parsed.reason)
                        }
                }
            }
            return ImportBatch(nodes.values.toList(), subscriptions.toList(), rejected)
        }
    }
}
