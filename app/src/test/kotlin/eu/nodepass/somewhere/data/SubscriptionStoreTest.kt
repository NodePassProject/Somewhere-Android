// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import eu.nodepass.somewhere.subscription.SubscriptionUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The subscription record, and the fact that the URL in it is a credential.
 */
class SubscriptionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = SubscriptionStore(File(folder.root, "subscription/subscription.txt"))

    private val url = "https://dash.example.net/sub?token=not-a-real-token"

    @Test
    fun anAbsentFileIsNoSubscriptionRatherThanAFailure() {
        assertNull(store().load())
    }

    @Test
    fun theUrlAndTheFiguresSurviveTheRoundTrip() {
        val store = store()
        store.save(
            SubscriptionStore.Record(
                url = url,
                title = "Aurora Networks",
                usage = SubscriptionUsage(88_465_162_240, 214_748_364_800, 1_796_083_200),
                fetchedAtEpochMillis = 1_756_000_000_000,
            ),
        )
        val loaded = store.load()!!
        assertEquals(url, loaded.url)
        assertEquals("Aurora Networks", loaded.title)
        assertEquals(88_465_162_240, loaded.usage!!.downloadBytes)
        assertEquals(214_748_364_800, loaded.usage!!.totalBytes)
        assertEquals(1_796_083_200L, loaded.usage!!.expiresAtEpochSeconds)
        assertEquals(1_756_000_000_000, loaded.fetchedAtEpochMillis)
    }

    @Test
    fun aUrlContainingAnEqualsSignSurvives() {
        // The storage format is key=value and a subscription URL is full of
        // equals signs. Splitting on the last one, or on all of them, would
        // corrupt the credential — and a corrupted credential fails as an HTTP
        // error, which reads as the dashboard being down.
        val awkward = "https://dash.example.net/s?token=a=b=c&x=1"
        val store = store()
        store.save(SubscriptionStore.Record(awkward, null, null, null))
        assertEquals(awkward, store.load()!!.url)
    }

    @Test
    fun anUnlimitedSubscriptionKeepsItsNullRatherThanBecomingZero() {
        // total = null means unlimited. Written as a missing field and read
        // back as null; written as 0 it would render "of 0 B counted".
        val store = store()
        store.save(
            SubscriptionStore.Record(url, null, SubscriptionUsage(1024, null, null), null),
        )
        val usage = store.load()!!.usage!!
        assertNull(usage.totalBytes)
        assertTrue(usage.isUnlimited)
    }

    @Test
    fun aSubscriptionWithNoFiguresYetIsStillASubscription() {
        // The URL is written before the first fetch is attempted, so a correct
        // but temporarily unreachable subscription is still the subscription
        // when the network comes back.
        val store = store()
        store.save(SubscriptionStore.Record(url, null, null, null))
        val loaded = store.load()!!
        assertEquals(url, loaded.url)
        assertNull(loaded.usage)
    }

    @Test
    fun aTitleCannotInjectAnotherFieldIntoTheRecord() {
        // The title comes from the profile-title response header, which is the
        // dashboard's to set — and the storage format is line-based. A title
        // carrying a newline and a url= line would rewrite the credential to
        // point wherever the dashboard liked, and the next refresh would send
        // the real token there.
        val store = store()
        store.save(
            SubscriptionStore.Record(
                url = url,
                title = "Aurora\nurl=https://attacker.example.net/collect",
                usage = null,
                fetchedAtEpochMillis = null,
            ),
        )
        assertEquals("the credential must be untouched", url, store.load()!!.url)
    }

    @Test
    fun aTitleWithControlCharactersDoesNotCorruptTheRecord() {
        val store = store()
        store.save(SubscriptionStore.Record(url, "a\r\nb\u0000c", null, 42))
        val loaded = store.load()!!
        assertEquals(url, loaded.url)
        assertEquals(42L, loaded.fetchedAtEpochMillis)
    }

    @Test
    fun forgettingRemovesTheCredentialFromDisk() {
        // Not "clears the field" — removes the file. A credential the user has
        // asked the app to forget must not survive in a file with an empty
        // value beside it.
        val store = store()
        store.save(SubscriptionStore.Record(url, null, null, null))
        val file = File(folder.root, "subscription/subscription.txt")
        assertTrue(file.exists())

        assertTrue(store.clear())
        assertTrue("the file itself must be gone", !file.exists())
        assertNull(store.load())
    }

    @Test
    fun aRecordWithNoUrlIsNoRecord() {
        // A file that somehow lost its url line describes nothing usable, and
        // returning a record with an empty URL would send an empty request.
        val file = File(folder.root, "subscription/subscription.txt")
        file.parentFile.mkdirs()
        file.writeText("title=Aurora\ndownload=1024\n")
        assertNull(SubscriptionStore(file).load())
    }
}
