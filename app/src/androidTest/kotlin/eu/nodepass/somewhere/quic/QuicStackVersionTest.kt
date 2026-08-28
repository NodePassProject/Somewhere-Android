// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The QUIC stack in the shipped library is the one `tools/quic/DEPENDENCIES`
 * pins.
 *
 * ## Why this is not two files being compared
 *
 * The obvious version check reads the pin file, reads a build script, and
 * reports that both say `1.68.0`. That compares two pieces of text and proves
 * nothing about the binary a device runs.
 *
 * This asks the linked archives what they are. `BuildConfig` carries the pin —
 * Gradle reads `tools/quic/DEPENDENCIES`, the one file that holds it, and the
 * conformance probe reads the same one — and the native calls return whatever
 * ngtcp2 and aws-lc were actually compiled into `libsomewhere_native.so`. A
 * mismatch means the cache served a stale build, or a fetch was silently
 * satisfied from somewhere else, and either is a thing the size and symbol
 * gates cannot see.
 *
 * ## Why it belongs on a device
 *
 * A JVM unit test cannot load an `.so` built for Android, so on a JVM this
 * question is unanswerable rather than merely untested. Everything about the
 * QUIC stack that can be checked at all is checked here or nowhere.
 *
 * This test is also the reason the version calls exist at all. A static archive
 * contributes nothing to a shared library until a symbol in it is referenced,
 * so without these two calls the build would name ngtcp2 and aws-lc on its link
 * line, produce a byte-identical library, and pass every other check.
 */
@RunWith(AndroidJUnit4::class)
class QuicStackVersionTest {
    @Test
    fun theLinkedTransportIsTheOneThePinNames() {
        assertEquals(
            "the linked ngtcp2 is not the version tools/quic/DEPENDENCIES pins",
            BuildConfig.NGTCP2_VERSION,
            QuicStack.ngtcp2Version,
        )
    }

    @Test
    fun theLinkedTlsBackendIsTheOneThePinNames() {
        // aws-lc reports something like "AWS-LC 1.68.0"; the pin is the bare
        // version, because a library's own phrasing is its business and this
        // test should fail on the number rather than on the wording.
        assertTrue(
            "the linked TLS backend reports '${QuicStack.cryptoVersion}', " +
                "which does not contain the pinned ${BuildConfig.AWSLC_VERSION}",
            QuicStack.cryptoVersion.contains(BuildConfig.AWSLC_VERSION),
        )
    }

    @Test
    fun theTlsBackendIsAwsLcRatherThanWhateverElseWasOnThePath() {
        // ngtcp2 accepts several crypto backends and the exporter NW-P-01
        // depends on comes from this one. A build that quietly satisfied
        // -DENABLE_BORINGSSL against a different BoringSSL would still link.
        assertTrue(
            "expected aws-lc, got '${QuicStack.cryptoVersion}'",
            QuicStack.cryptoVersion.contains("AWS-LC", ignoreCase = true),
        )
    }
}
