// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the conformance vectors that the protocol layer is tested against.
 *
 * These are the same bytes the Python checker in `conformance/scripts/` verifies,
 * loaded from the same file rather than transcribed — a transcribed copy drifts,
 * and a drifted acceptance test is worse than none. The build copies
 * `conformance/vectors/` into the unit-test resources, so tests never reach
 * outside the module.
 *
 * Navigated as [JsonObject] rather than deserialised into data classes: case
 * shapes differ per family — an auth case carries a shared key and an expected
 * key, a target case carries an ATYP and expected hex — and modelling every
 * shape would be more code than the tests consuming them.
 */
object VectorFixture {
    private const val RESOURCE = "/protocol-vectors.json"

    val root: JsonObject by lazy {
        val stream =
            VectorFixture::class.java.getResourceAsStream(RESOURCE)
                ?: error(
                    "Vector fixture $RESOURCE is missing from the test classpath. " +
                        "It is copied from conformance/vectors by the test source set in " +
                        "app/build.gradle.kts.",
                )
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    /** Families that carry `cases` and/or `rejects`, in fixture order. */
    val families: List<String> by lazy {
        root.entries
            .filter { (_, value) ->
                value is JsonObject && ("cases" in value || "rejects" in value)
            }.map { it.key }
    }

    fun family(name: String): JsonObject =
        root[name]?.jsonObject
            ?: error("Vector family '$name' is not in the fixture. Present: $families")

    fun cases(family: String): List<JsonObject> = entries(family, "cases")

    fun rejects(family: String): List<JsonObject> = entries(family, "rejects")

    private fun entries(
        family: String,
        key: String,
    ): List<JsonObject> =
        (family(family)[key] as? JsonArray ?: JsonArray(emptyList()))
            .jsonArray
            .map { it.jsonObject }

    /** The upstream snapshot these vectors were derived from. */
    object Baseline {
        private val node: JsonObject get() = VectorFixture.root["baseline"]!!.jsonObject

        val tag: String get() = node["tag"]!!.jsonPrimitive.content
        val commit: String get() = node["commit"]!!.jsonPrimitive.content
        val repository: String get() = node["upstreamRepo"]!!.jsonPrimitive.content
    }

    /** Reads a hex string field, e.g. `expectedHex`, as bytes. */
    fun JsonObject.hex(field: String): ByteArray = this[field]!!.jsonPrimitive.content.hexToByteArrayCompat()

    fun JsonObject.str(field: String): String = this[field]!!.jsonPrimitive.content

    fun JsonObject.int(field: String): Int = this[field]!!.jsonPrimitive.int

    fun JsonObject.name(): String = str("name")
}

/**
 * Hex decoding without depending on the experimental stdlib API, so the tests do
 * not carry an opt-in that the production code does not need.
 */
internal fun String.hexToByteArrayCompat(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, got $length" }
    return ByteArray(length / 2) { index ->
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "not a hex string: $this" }
        ((high shl 4) or low).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
