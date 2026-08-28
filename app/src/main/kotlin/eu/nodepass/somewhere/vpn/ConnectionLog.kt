// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.protocol.frame.SetupResult
import eu.nodepass.somewhere.protocol.target.Target
import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Portal answered, flow by flow (NW-A-06).
 *
 * ## Bounded, and what that costs
 *
 * A tunnel opens a flow per connection and a busy device opens hundreds a
 * minute, so this keeps the most recent [CAPACITY] and drops the rest. A log
 * that grew with the tunnel would be a memory leak with a user interface.
 *
 * ## What must never appear here, and why it is checked
 *
 * People paste logs into issues. This repository is public, and a shared key or
 * a subscription token in a pasted log is a credential published by someone
 * trying to be helpful — which is exactly how this project already shipped one
 * defect, through a subscription title that could rewrite the stored URL.
 *
 * So an entry carries a `SetupResult`, a timestamp, a flow id, a carrier name
 * and **a target that has been reduced to a host and port**. No URL, no key, no
 * subscription. That is not enforced by care; `ConnectionLogTest` fuzzes
 * credential-shaped values through every field and fails if one comes out.
 */
class ConnectionLog(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<ConnectionLogEntry>()
    private val mutable = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())

    /** Most recent first, which is the order a reader wants and the screen draws. */
    val recent: StateFlow<List<ConnectionLogEntry>> = mutable.asStateFlow()

    fun record(
        result: SetupResult,
        target: Target?,
        flowId: UInt?,
        carrier: String,
    ) {
        val entry =
            ConnectionLogEntry(
                result = result,
                timestamp = FORMAT.format(Date(now())),
                target = target?.let(::describe),
                flowId = flowId?.toInt(),
                carrier = carrier,
            )
        synchronized(entries) {
            entries.addFirst(entry)
            while (entries.size > CAPACITY) entries.removeLast()
            mutable.value = entries.toList()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            mutable.value = emptyList()
        }
    }

    /**
     * A target as `host:port`, and nothing else.
     *
     * A domain target already is one. An address target is rendered from its
     * octets rather than from anything a peer supplied, so a name that arrived
     * carrying punctuation cannot reach a log line at all.
     */
    private fun describe(target: Target): String =
        when (target) {
            is Target.Domain -> "${sanitise(target.host)}:${target.port}"
            is Target.Ip ->
                if (target.octets.size == 4) {
                    target.octets.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":${target.port}"
                } else {
                    target.octets.joinToString(":") { "%02x".format(it) } + " port ${target.port}"
                }
        }

    /**
     * Strips what a log line must not carry.
     *
     * A hostname cannot legally contain any of this, so removing it costs a
     * correct name nothing — and a value that arrived from a peer carrying a
     * newline, a userinfo separator or a percent-encoded byte is precisely the
     * thing that must not reach a line someone copies.
     */
    private fun sanitise(value: String): String =
        value
            .asSequence()
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' }
            .take(MAX_HOST_LENGTH)
            .joinToString("")

    private companion object {
        const val CAPACITY = 200
        const val MAX_HOST_LENGTH = 253
        val FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
