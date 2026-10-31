// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.update

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class UpdateCheckerTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }

    @After
    fun stop() = server.stop(0)

    private fun serve(
        status: Int,
        body: String,
    ): String {
        server.createContext("/latest") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return "http://127.0.0.1:${server.address.port}/latest"
    }

    private fun release(
        tag: String,
        extra: String = "",
    ) = """{"tag_name":"$tag","html_url":"https://github.com/NodePassProject/Somewhere-Android/releases/tag/$tag",
           "body":"What changed","draft":false,"prerelease":false$extra,
           "assets":[{"name":"somewhere-$tag.apk","browser_download_url":"https://github.com/NodePassProject/Somewhere-Android/releases/download/$tag/somewhere.apk"}]}"""

    @Test
    fun aNewerReleaseIsOfferedWithItsPageNotesAndApk() {
        val found = UpdateChecker("0.1.0", serve(200, release("v0.2.0"))).check()
        val release = (found as UpdateCheck.Available).release
        assertEquals("v0.2.0", release.tag)
        assertEquals("What changed", release.notes)
        assertTrue(release.page.endsWith("/tag/v0.2.0"))
        assertTrue(release.apk!!.endsWith(".apk"))
    }

    @Test
    fun theSameOrAnOlderReleaseIsCurrent() {
        assertEquals(UpdateCheck.Current, UpdateChecker("0.2.0", serve(200, release("v0.2.0"))).check())
    }

    @Test
    fun noPublishedReleaseIsSaidAsSuch() {
        assertEquals(UpdateCheck.NoRelease, UpdateChecker("0.1.0", serve(404, "")).check())
    }

    @Test
    fun draftsPreReleasesAndForeignLinksAreNotOffered() {
        assertNull(UpdateChecker.parse(release("v9.0.0").replace("\"draft\":false", "\"draft\":true")))
        assertNull(UpdateChecker.parse(release("v9.0.0").replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(UpdateChecker.parse(release("v9.0.0").replace("https://github.com/NodePassProject", "https://example.com/x")))
        assertNull(UpdateChecker.parse("not json"))
    }

    @Test
    fun versionsCompareNumericallyAndAPreReleaseComesBeforeItsRelease() {
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.9")!!)
        assertTrue(Version.parse("v1.0.0")!! > Version.parse("1.0.0-rc.2")!!)
        assertEquals(0, Version.parse("1.0")!!.compareTo(Version.parse("1.0.0")!!))
        assertNull(Version.parse("latest"))
    }
}
