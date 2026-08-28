// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

/**
 * Finds the library the QUIC bridge lives in.
 *
 * On a device that is `libsomewhere_native`, which also carries lwIP. On a
 * build host it is a smaller library holding only this bridge — lwIP is built
 * NO_SYS against an Android port and has no business running here, while the
 * bridge depends on nothing Android at all.
 *
 * The second exists so that the oracle differential can compare this
 * implementation with the reference one over QUIC without an emulator in the
 * loop. That matters more than convenience: the differential is where a
 * protocol fact that has grown two shapes gets caught, and it caught exactly
 * that when Mux landed.
 *
 * The path is a system property rather than a search, because a search that
 * found the wrong library would produce a differential comparing something
 * nobody meant to compare.
 */
internal object NativeQuic {
    /** Set by `conformance/scripts/build-host-quic.sh` through Gradle. */
    private const val HOST_LIBRARY_PROPERTY = "somewhere.quic.library"

    private val loaded: Unit by lazy {
        val hostLibrary = System.getProperty(HOST_LIBRARY_PROPERTY)
        if (hostLibrary.isNullOrBlank()) {
            System.loadLibrary("somewhere_native")
        } else {
            System.load(hostLibrary)
        }
    }

    fun ensureLoaded() = loaded
}
