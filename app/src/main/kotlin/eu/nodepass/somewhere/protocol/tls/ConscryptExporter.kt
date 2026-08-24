// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import org.conscrypt.Conscrypt
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Conscrypt's exporter, for API 26–30 where the platform has none.
 *
 * Conscrypt is Google's BoringSSL wrapper — the same engine behind the platform
 * implementation on newer releases, so this is not a second, divergent TLS stack
 * so much as the same one carried further back.
 *
 * The socket must be one Conscrypt produced. A platform socket passed here is
 * rejected rather than silently mishandled: [Conscrypt.isConscrypt] is the check,
 * and a mismatch means the caller built the socket from the wrong factory.
 */
class ConscryptExporter : KeyingMaterialExporter {
    override val name: String = "conscrypt"

    override fun export(
        socket: SSLSocket,
        label: String,
        context: ByteArray,
        length: Int,
    ): DecodeResult<ByteArray> {
        if (!Conscrypt.isConscrypt(socket)) {
            return invalid(ExporterReason.Unsupported("socket was not created by Conscrypt"))
        }
        if (socket.session?.isValid != true) {
            return invalid(ExporterReason.NotConnected)
        }
        return try {
            val material =
                Conscrypt.exportKeyingMaterial(socket, label, context, length)
                    ?: return invalid(ExporterReason.Failed("Conscrypt returned no keying material"))
            if (material.size != length) {
                invalid(ExporterReason.WrongLength(material.size, length))
            } else {
                material.ok()
            }
        } catch (error: SSLException) {
            invalid(ExporterReason.Failed(error.javaClass.simpleName))
        } catch (error: IllegalArgumentException) {
            invalid(ExporterReason.Unsupported(error.javaClass.simpleName))
        }
    }
}
