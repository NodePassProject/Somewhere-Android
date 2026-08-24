// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.RequiresApi
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.invalid
import eu.nodepass.somewhere.protocol.ok
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * The platform's own exporter, `android.net.ssl.SSLSockets`.
 *
 * Public API from **API 31** — verified with `javap` against the platform jars:
 * the class is absent at 28, present at 29 without the method, and the method
 * appears at 31. The class `SSLSessions` does not exist; do not look for it.
 *
 * Preferred wherever available: no dependency, no bundled cryptography.
 */
@RequiresApi(Build.VERSION_CODES.S)
class PlatformExporter : KeyingMaterialExporter {
    override val name: String = "platform"

    override fun export(
        socket: SSLSocket,
        label: String,
        context: ByteArray,
        length: Int,
    ): DecodeResult<ByteArray> {
        if (!SSLSockets.isSupportedSocket(socket)) {
            return invalid(ExporterReason.Unsupported("socket is not a platform SSLSocket"))
        }
        if (socket.session?.isValid != true) {
            return invalid(ExporterReason.NotConnected)
        }
        return try {
            // The platform stub declares this nullable. Treated as a failure
            // rather than asserted away: authentication is impossible without
            // these bytes, and a null-pointer crash would be a worse way to
            // learn that than a named reason.
            val material =
                SSLSockets.exportKeyingMaterial(socket, label, context, length)
                    ?: return invalid(ExporterReason.Failed("platform returned no keying material"))
            if (material.size != length) {
                invalid(ExporterReason.WrongLength(material.size, length))
            } else {
                material.ok()
            }
        } catch (error: SSLException) {
            invalid(ExporterReason.Failed(error.javaClass.simpleName))
        } catch (error: UnsupportedOperationException) {
            invalid(ExporterReason.Unsupported(error.javaClass.simpleName))
        }
    }
}
