// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import eu.nodepass.somewhere.protocol.DecodeReason
import eu.nodepass.somewhere.protocol.DecodeResult
import javax.net.ssl.SSLSocket

sealed interface ExporterReason : DecodeReason {
    data object NotConnected : ExporterReason {
        override val detail: String = "the TLS handshake has not completed, so there is nothing to export from"
    }

    data class WrongLength(
        val actual: Int,
        val expected: Int,
    ) : ExporterReason {
        override val detail: String = "exporter returned $actual bytes, expected $expected"
    }

    data class Unsupported(
        val cause: String,
    ) : ExporterReason {
        override val detail: String = "this TLS stack cannot export keying material: $cause"
    }

    data class Failed(
        val cause: String,
    ) : ExporterReason {
        override val detail: String = "keying material export failed: $cause"
    }
}

/**
 * Exports RFC 8446 §7.5 keying material from a completed TLS connection.
 *
 * Nowhere binds its authentication tag to this value (NW-P-01), which is what
 * stops a captured AuthFrame from being replayed onto another connection. Without
 * it there is no way to authenticate at all — this interface is the narrowest
 * point in the whole client, and everything above it depends on 32 bytes.
 *
 * It exists as an interface because the platform only offers the call from API
 * 31, and dropping API 26–30 to avoid one 32-byte call is the wrong trade for a
 * compatibility-protocol client. See `docs/adr-0001-tls-exporter.md`.
 *
 * Implementations must not log the exported bytes. They are key material.
 */
interface KeyingMaterialExporter {
    /** A short name for diagnostics, e.g. "platform" or "conscrypt". */
    val name: String

    /**
     * @param label the exporter label; Nowhere uses `EXPORTER-Nowhere-Auth`.
     * @param context the exporter context. Nowhere uses an **empty, present**
     *   context — which is not the same as an absent one, and the two produce
     *   different bytes.
     */
    fun export(
        socket: SSLSocket,
        label: String,
        context: ByteArray,
        length: Int,
    ): DecodeResult<ByteArray>
}
