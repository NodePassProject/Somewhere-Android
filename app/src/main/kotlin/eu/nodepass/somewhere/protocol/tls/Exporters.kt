// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol.tls

import android.os.Build

/**
 * Picks the exporter for this device: the platform call where it exists,
 * Conscrypt below it.
 *
 * Both paths were verified to produce bytes a real Portal accepts — see
 * `ExporterAgainstPortalTest` for the Conscrypt one, which is what the `minSdk`
 * 26 decision rests on.
 *
 * **The rule and the construction are deliberately separate.** [usesPlatform] is
 * pure arithmetic over an API level and is unit-tested at every level from 26 up.
 * [forDevice] takes no parameter and reads `Build.VERSION.SDK_INT` directly, so
 * the version check is one the compiler and lint can both see. An earlier version
 * let a caller pass the level in, which was convenient to test and would have
 * allowed `forDevice(31)` on an API 26 device — a crash reachable only on the
 * oldest phones, which is the worst place for one. Lint caught it.
 */
object Exporters {
    /**
     * The API level at which `SSLSockets.exportKeyingMaterial` became public.
     *
     * Lives here rather than on [PlatformExporter], because that class requires
     * API 31 and so does reading a constant off it — which would make the
     * version check itself need the version it is checking for.
     */
    const val PLATFORM_MIN_API: Int = Build.VERSION_CODES.S

    /**
     * Whether [sdkInt] has the platform exporter. Pure; safe to call anywhere.
     */
    fun usesPlatform(sdkInt: Int): Boolean = sdkInt >= PLATFORM_MIN_API

    /** The exporter for the device this is running on. */
    fun forDevice(): KeyingMaterialExporter =
        if (Build.VERSION.SDK_INT >= PLATFORM_MIN_API) {
            PlatformExporter()
        } else {
            ConscryptExporter()
        }
}
