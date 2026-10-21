// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.vpn

import eu.nodepass.somewhere.ui.state.ConnectionLogEntry
import eu.nodepass.somewhere.ui.state.identifier

/**
 * The three activity logs as one plain-text document, for an issue or a chat.
 *
 * Everything in it has already been through [TargetText] on its way into the
 * logs, so nothing here can carry a node key or a subscription token: there is
 * no field for either. What it does carry is where the device went and which
 * apps went there, and the screen that offers the export says so.
 *
 * English and fixed-format on purpose (`docs/i18n.md`): it is pasted into
 * issues and compared with other people's, so a SetupResult name or a column
 * order that changed with the reader's language would be noise.
 */
object ActivityExport {
    fun render(
        appVersion: String,
        protocolBaseline: String,
        exportedAt: String,
        portalAnswers: List<ConnectionLogEntry>,
        dns: List<DnsLogEntry>,
        live: List<LiveConnection>,
        nowElapsedMillis: Long,
    ): String =
        buildString {
            appendLine("Somewhere $appVersion · $protocolBaseline · exported $exportedAt")
            appendLine()
            appendLine("## Portal answers (${portalAnswers.size}, newest first)")
            portalAnswers.forEach { entry ->
                append(entry.timestamp).append("  ").append(entry.result.identifier)
                entry.target?.let { append("  ").append(it) }
                entry.carrier?.let { append("  ").append(it) }
                entry.flowId?.let { append("  flow ").append(it) }
                entry.setupMillis?.let { append("  ").append(it).append(" ms") }
                appendLine()
            }
            appendLine()
            appendLine("## DNS (${dns.size}, newest first)")
            dns.forEach { entry ->
                appendLine("${entry.timestamp}  ${entry.type.ifEmpty { "?" }}  ${entry.name.ifEmpty { "?" }}  ${entry.outcome.identifier}")
            }
            appendLine()
            appendLine("## Open connections (${live.size})")
            live.forEach { connection ->
                val age = ((nowElapsedMillis - connection.openedAtElapsedMillis) / 1000).coerceAtLeast(0)
                appendLine(
                    "${connection.protocol.name.uppercase()}  ${connection.target}  ${connection.route.name}  " +
                        "${connection.app ?: "?"}  up ${connection.upstreamBytes} B  down ${connection.downstreamBytes} B  ${age}s",
                )
            }
        }
}
