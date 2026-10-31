// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What became of one query.
 *
 * [identifier] is what the screen and the export both print: a machine
 * identifier, untranslated, so a pasted line matches someone else's.
 */
enum class DnsOutcome(
    val identifier: String,
) {
    /** Answered here with a synthetic address; the name goes to the Portal. */
    FakeIp("FAKE-IP"),

    /**
     * Answered here with no records: IPv6 on a tunnel that does not carry it,
     * or a service binding (HTTPS, SVCB) for a name routed by name.
     */
    NoData("NODATA"),

    /** Passed on to a resolver, which answered or did not. */
    Relayed("RELAYED"),

    /** Neither answerable nor forwardable, so answered SERVFAIL. */
    Failed("SERVFAIL"),
}

data class DnsLogEntry(
    val timestamp: String,
    /** Through [TargetText.sanitise]; empty when the query could not be read. */
    val name: String,
    /** The record type's mnemonic, or its number when it has none here. */
    val type: String,
    val outcome: DnsOutcome,
)

/**
 * The names the device asked for, most recent first.
 *
 * Bounded like [ConnectionLog], for the same reason: a device asks for names
 * all the time, and a log that grew with the tunnel would be a leak with a
 * screen on it. Written from the lwIP thread, so a record is a deque push and
 * nothing more.
 */
class DnsLog(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<DnsLogEntry>()
    private val mutable = MutableStateFlow<List<DnsLogEntry>>(emptyList())

    val recent: StateFlow<List<DnsLogEntry>> = mutable.asStateFlow()

    fun record(
        name: String?,
        type: Int?,
        outcome: DnsOutcome,
    ) {
        val entry =
            DnsLogEntry(
                timestamp = synchronized(FORMAT) { FORMAT.format(Date(now())) },
                name = name?.let { TargetText.sanitise(it, underscore = true) }.orEmpty(),
                type = type?.let(::typeName).orEmpty(),
                outcome = outcome,
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

    private fun typeName(type: Int): String = TYPES[type] ?: type.toString()

    private companion object {
        const val CAPACITY = 300
        val FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
        val TYPES =
            mapOf(
                1 to "A",
                2 to "NS",
                5 to "CNAME",
                6 to "SOA",
                12 to "PTR",
                15 to "MX",
                16 to "TXT",
                28 to "AAAA",
                33 to "SRV",
                64 to "SVCB",
                65 to "HTTPS",
            )
    }
}
