// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

/**
 * The QUIC transport this client links against.
 *
 * ## Why this is not in `protocol.quic`
 *
 * `protocol.quic` is Nowhere's own wire format over QUIC — the DATAGRAM
 * frame headers, the fragmentation arithmetic, the reassembler. All of it is
 * Kotlin, all of it is testable on a JVM, and all of it is inside the 90%
 * coverage gate.
 *
 * This package is RFC 9000, which is **not** the Nowhere protocol. Choosing a
 * library to implement it does not weaken "engine hand-written in Kotlin": the
 * AuthFrame, the FlowHeader, the Target, the SetupResult and every codec stay
 * where they are. What lives here is the binding to the thing underneath them.
 *
 * It is deliberately outside the coverage gate, and that is a decision rather
 * than an omission: a JNI shim cannot be exercised by a JVM unit test, so
 * including it would move the gate's number without moving what the gate is
 * for. It is covered by instrumentation instead, where the native library
 * actually exists.
 *
 * ## What is here now, and why so little
 *
 * Only the two version calls. A static archive contributes nothing to a shared
 * library until some symbol in it is referenced, so a build that names ngtcp2
 * and aws-lc on its link line and calls neither produces a byte-identical `.so`
 * — and every claim about size, symbols and alignment would then be a claim
 * about an unused link line. These are the first real references.
 *
 * The connection, the socket and the exporter are C2.
 *
 * @see <a href="../../../../../../../../tools/quic/DEPENDENCIES">tools/quic/DEPENDENCIES</a>
 */
object QuicStack {
    init {
        // The same library lwIP is in: one shared object, loaded once. Loading
        // it here as well is harmless — the JVM's own loader is idempotent —
        // and it means this object works without a tunnel being up.
        System.loadLibrary("somewhere_native")
    }

    /**
     * The linked ngtcp2's own version string, from the archive rather than
     * from a header this file could have been compiled against separately.
     */
    val ngtcp2Version: String
        get() = nativeNgtcp2Version() ?: error("ngtcp2 reported no version")

    /**
     * The linked TLS backend's version string. This is the library that
     * supplies the RFC 5705 exporter NW-P-01 authenticates with, so knowing
     * which one is running is not trivia.
     */
    val cryptoVersion: String
        get() = nativeCryptoVersion() ?: error("the TLS backend reported no version")

    @JvmStatic
    private external fun nativeNgtcp2Version(): String?

    @JvmStatic
    private external fun nativeCryptoVersion(): String?
}
