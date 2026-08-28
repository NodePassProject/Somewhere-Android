// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.nodepass.somewhere.vpn.E2eEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The QUIC transport runs inside the app process and talks to a real Portal.
 *
 * `QuicStackVersionTest` proves the right library is linked. This proves it
 * works: a handshake is several round trips over a socket this class owns, and
 * every part of the bridge — the path built from raw address bytes, the
 * datagrams crossing in both directions, the timer, the TLS backend — has to be
 * right for it to finish.
 *
 * ## What the exporter is and is not checked against
 *
 * The run PRD said the exporter should "match this suite's fixed vector", and
 * that is not a thing that can be true. An RFC 5705 exporter is derived from
 * the handshake, so it is different on every connection by design; the fixed
 * vector this suite carries is `derive_auth_key("secret")`, which is derived
 * from the *shared key* and is already checked by `verify-vectors.py`.
 *
 * So what is checked here is what is checkable at this layer: that keying
 * material comes out at all, that it is the length asked for, and that it
 * changes with the label — which is the property the label exists for, and the
 * one that would silently not hold if the context arguments were wrong.
 * Whether a Portal *accepts* what is derived from it is authentication, and
 * that is the next task.
 */
@RunWith(AndroidJUnit4::class)
class QuicHandshakeTest {
    @Test
    fun theHandshakeCompletesAgainstALivePortal() {
        val portal = E2eEnvironment.requirePortal()
        connect(portal).use { connection ->
            connection.completeHandshake()
            assertTrue("the handshake did not complete", connection.handshakeCompleted)
        }
    }

    @Test
    fun keyingMaterialComesOutAndDependsOnItsLabel() {
        val portal = E2eEnvironment.requirePortal()
        connect(portal).use { connection ->
            connection.completeHandshake()

            val authentication = connection.exportKeyingMaterial(NOWHERE_LABEL, 32)
            val other = connection.exportKeyingMaterial("EXPORTER-Something-Else", 32)

            assertEquals("the exporter returned the wrong length", 32, authentication.size)
            assertFalse("the exporter returned zeros", authentication.all { it == 0.toByte() })
            assertNotEquals(
                "two labels produced the same keying material, so the label is not reaching the derivation",
                authentication.toList(),
                other.toList(),
            )
        }
    }

    @Test
    fun theSameLabelTwiceOnOneConnectionIsStable() {
        val portal = E2eEnvironment.requirePortal()
        connect(portal).use { connection ->
            connection.completeHandshake()
            assertEquals(
                connection.exportKeyingMaterial(NOWHERE_LABEL, 32).toList(),
                connection.exportKeyingMaterial(NOWHERE_LABEL, 32).toList(),
            )
        }
    }

    /**
     * Many threads may use one connection, and none of them touches the native
     * side.
     *
     * This assertion replaced its opposite. `ngtcp2_conn` is not thread-safe
     * and the bridge enforces that by comparing `pthread_self()` against the
     * thread that opened the connection — a check a lock cannot satisfy,
     * because it is about identity rather than exclusion. The first version of
     * this class therefore refused foreign callers outright, and this test
     * asserted the refusal.
     *
     * That could not survive C4. A QUIC connection carries many flows at once,
     * which is the entire reason to have one, and `NowhereSession.openFlow` is
     * documented as safe from several threads. So the connection grew a thread
     * of its own, and callers queue work to it instead of being turned away.
     *
     * **The bridge's check is still there and still refuses**, which is what
     * makes this test meaningful rather than vacuous: if any of these threads
     * reached the native side directly, it would come back as a refusal naming
     * the thread, not as a wrong answer. Sixteen threads getting the same
     * exporter is evidence that all sixteen went through the owner.
     */
    @Test
    fun manyThreadsShareOneConnectionWithoutReachingTheNativeSide() {
        val portal = E2eEnvironment.requirePortal()
        connect(portal).use { connection ->
            connection.completeHandshake()
            val expected = connection.exportKeyingMaterial(NOWHERE_LABEL, 32).toList()

            val outcomes = ArrayBlockingQueue<Result<List<Byte>>>(THREADS)
            val start = CountDownLatch(1)
            repeat(THREADS) {
                Thread {
                    start.await()
                    outcomes.add(runCatching { connection.exportKeyingMaterial(NOWHERE_LABEL, 32).toList() })
                }.start()
            }
            start.countDown()

            repeat(THREADS) { index ->
                val result = outcomes.poll(20, TimeUnit.SECONDS)
                assertTrue("thread $index never answered", result != null)
                val failure = result!!.exceptionOrNull()
                assertTrue(
                    "a thread was refused rather than routed: ${failure?.message}",
                    failure == null,
                )
                assertEquals("thread $index got different keying material", expected, result.getOrNull())
            }
        }
    }

    /**
     * Opening and closing many connections leaves none behind.
     *
     * A native leak is invisible to the JVM: nothing in a heap dump, nothing in
     * a finalizer, and the process simply grows. Counting what the bridge holds
     * is a deterministic statement about its own bookkeeping, which is the part
     * this file is responsible for.
     */
    @Test
    fun openingAndClosingManyConnectionsLeavesNoneLive() {
        val before = QuicConnection.liveConnections()
        repeat(200) { connect("127.0.0.1:1").close() }
        assertEquals("connections were left open", before, QuicConnection.liveConnections())
    }

    private fun connect(portal: String): QuicConnection {
        val host = portal.substringBeforeLast(':')
        val port = portal.substringAfterLast(':').toInt()
        return QuicConnection.open(
            remote = InetSocketAddress(host, port),
            alpn = ALPN,
            serverName = null,
            // No tunnel is up in this test, so there is nothing to protect
            // against. Stated as a lambda that says so rather than as a
            // default, because a default would hide the rule at every call
            // site that does need it.
            protect = { true },
        )
    }

    private companion object {
        const val NOWHERE_LABEL = "EXPORTER-Nowhere-Auth"

        /** Enough to be a burst rather than a sequence. */
        const val THREADS = 16

        /**
         * The protocol's default ALPN. Passed rather than compiled in
         * anywhere below this test: a client that embedded it would be
         * shipping one deployment's choice to every installation.
         */
        const val ALPN = "now/1"
    }
}
