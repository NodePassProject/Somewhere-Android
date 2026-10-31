// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.net

import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.morph.AuthenticatedPrelude
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The Authenticated Prelude against a live v3.1 Portal.
 *
 * Connecting proves nothing on its own: a Portal that cannot match a Tag takes
 * the ordinary path and accepts the connection anyway. What only a matching
 * Tag produces is a **replay refusal** — a Portal that verified the Tag
 * commits its Counter, and the same Counter a second time is discarded
 * silently, before any TLS. So: one Counter twice, and the second must fail.
 * A Portal that predates the extension accepts both, and this test then says
 * so rather than passing.
 *
 * Requires a Portal (`conformance/scripts/portal-for-tests.sh`) at 3.1.0 or
 * later.
 */
class AuthenticatedPreludeAgainstPortalTest {
    private val portalEnv: String? = System.getenv("NOWHERE_E2E_PORTAL")
    private val keyEnv: String? = System.getenv("NOWHERE_E2E_KEY")
    private val pinEnv: String? = System.getenv("NOWHERE_E2E_PIN")

    private fun node(): NowhereUrl {
        assumeTrue("NOWHERE_E2E_PORTAL is not set", portalEnv != null && keyEnv != null && pinEnv != null)
        return (NowhereUrl.parse("nowhere://$keyEnv@$portalEnv?pin=$pinEnv") as DecodeResult.Ok).value
    }

    /** A dialer whose every connection carries [fields]. Short deadline: a refusal is silence. */
    private fun dialer(fields: AuthenticatedPrelude.Fields) = NowhereDialer(readTimeoutMillis = 8_000, preludeFields = { fields })

    @Test
    fun aReplayedCounterIsRefusedAndAFreshOneIsNot() {
        val node = node()
        // A fresh Identity per run, so a Portal left running between runs has
        // no history for it.
        val identity = SecureRandom().nextLong()

        val first = dialer(AuthenticatedPrelude.Fields(identity, 1)).connect(node)
        assertTrue(
            "the first use of a Counter should connect: ${(first as? DecodeResult.Invalid)?.reason?.detail}",
            first is DecodeResult.Ok,
        )
        (first as DecodeResult.Ok).value.close()

        val replay = dialer(AuthenticatedPrelude.Fields(identity, 1)).connect(node)
        assertTrue(
            "the same Counter a second time connected: the Portal did not verify our Tag (a Portal before 3.1.0?)",
            replay is DecodeResult.Invalid,
        )

        val next = dialer(AuthenticatedPrelude.Fields(identity, 2)).connect(node)
        assertTrue("the next Counter should connect: ${(next as? DecodeResult.Invalid)?.reason?.detail}", next is DecodeResult.Ok)
        (next as DecodeResult.Ok).value.close()
    }
}
