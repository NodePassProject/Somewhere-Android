// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published release newer than this build. */
data class Release(
    /** The tag, as published: `v0.2.0`. */
    val tag: String,
    val version: Version,
    /** The release page, which is what a user is sent to. */
    val page: String,
    /** The release notes, as written. Markdown; shown as text. */
    val notes: String,
    /** The APK's direct link, when the release carries one. */
    val apk: String?,
)

/** What a check found. */
sealed interface UpdateCheck {
    data class Available(
        val release: Release,
    ) : UpdateCheck

    /** The newest release is this build, or older. */
    data object Current : UpdateCheck

    /** No release has been published yet. GitHub answers 404 for that. */
    data object NoRelease : UpdateCheck

    data class Failed(
        val reason: String,
    ) : UpdateCheck
}

/**
 * `MAJOR.MINOR.PATCH`, compared numerically.
 *
 * A pre-release suffix (`-rc.1`) is kept and sorts before the release it
 * precedes, which is all the order this needs: GitHub's `latest` endpoint
 * never returns a pre-release, so a suffix only ever appears on this build.
 */
data class Version(
    val parts: List<Int>,
    val suffix: String = "",
) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val order = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (order != 0) return order
        }
        return when {
            suffix == other.suffix -> 0
            suffix.isEmpty() -> 1
            other.suffix.isEmpty() -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun toString(): String = parts.joinToString(".") + if (suffix.isEmpty()) "" else "-$suffix"

    companion object {
        /** `v0.2.0`, `0.2.0`, `0.2.0-rc.1`; null for anything else. */
        fun parse(text: String): Version? {
            val bare = text.trim().removePrefix("v").removePrefix("V")
            val core = bare.substringBefore('-').substringBefore('+')
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.isEmpty() || parts.size > 4) return null
            val suffix = bare.substringAfter('-', "").substringBefore('+')
            return Version(parts, suffix)
        }
    }
}

/**
 * Asks GitHub Releases whether there is a newer build, as FlClash does.
 *
 * One `GET` to the public releases API of this repository, with nothing in it
 * that identifies the user, the device or the installation — there is no
 * identifier to send. It reaches GitHub, which is a third party, and so it is
 * off unless the user turns it on (`docs/privacy.md`); a check the user asks
 * for from Settings runs once, when they ask.
 *
 * It finds a release and says where it is. It does not download or install
 * anything: an APK replacing this one is verified by Android against the
 * signing key, and the user, not this class, decides to fetch it.
 *
 * Runs in the app's own process, which is outside its own tunnel in every
 * mode, so the request goes over the device's ordinary network.
 */
class UpdateChecker(
    private val currentVersion: String,
    private val endpoint: String = LATEST_RELEASE,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun check(): UpdateCheck {
        val current = Version.parse(currentVersion) ?: return UpdateCheck.Failed("this build's version '$currentVersion' is not a version")
        val connection =
            try {
                open(URL(endpoint)).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Somewhere/$currentVersion")
                }
            } catch (error: IOException) {
                return UpdateCheck.Failed(error.javaClass.simpleName)
            }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.use { it.readBytes().decodeToString() }
                    val release = parse(body) ?: return UpdateCheck.Failed("the release could not be read")
                    if (release.version > current) UpdateCheck.Available(release) else UpdateCheck.Current
                }

                HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheck.NoRelease
                else -> UpdateCheck.Failed("HTTP $status")
            }
        } catch (error: IOException) {
            UpdateCheck.Failed(error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/NodePassProject/Somewhere-Android/releases/latest"
        private const val TIMEOUT_MILLIS = 15_000
        private val json = Json { ignoreUnknownKeys = true }

        /** A release from the API's JSON, or null. Drafts and pre-releases are not offered. */
        internal fun parse(body: String): Release? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root.flag("draft") || root.flag("prerelease")) return null
            val tag = root.text("tag_name") ?: return null
            val version = Version.parse(tag) ?: return null
            val page = root.text("html_url")?.takeIf { it.startsWith("https://github.com/") } ?: return null
            val apk =
                runCatching { root["assets"]!!.jsonArray }
                    .getOrNull()
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it.text("name")?.endsWith(".apk") == true }
                    ?.text("browser_download_url")
                    ?.takeIf { it.startsWith("https://github.com/") }
            return Release(tag, version, page, root.text("body").orEmpty().trim(), apk)
        }

        private fun JsonObject.text(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

        private fun JsonObject.flag(key: String): Boolean = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
    }
}
