// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The rules in `docs/i18n.md`, enforced against the actual resource files.
 *
 * These are exactly the rules nothing enforces on its own: a translator can
 * localise an identifier, a locale can silently fall behind, and a sentence can
 * be assembled from fragments — all of which compile, ship, and are found later
 * by a confused user.
 */
class StringResourceTest {
    private companion object {
        val LOCALES = listOf("values", "values-b+zh+Hans", "values-b+zh+Hant")

        /**
         * Values, not prose. A user pasting one of these into an issue must
         * match the specification, the source, and someone else's log from a
         * different locale.
         */
        val NEVER_TRANSLATED =
            listOf(
                "READY",
                "INVALID_REQUEST",
                "METADATA_CONFLICT",
                "PAIR_TIMEOUT",
                "FLOW_LIMIT",
                "DIAL_FAILED",
                "SESSION_REPLACED",
                "INTERNAL_ERROR",
            )
    }

    private fun resourceDir(locale: String) = File("src/main/res/$locale")

    private fun strings(locale: String): Map<String, String> {
        val file = File(resourceDir(locale), "strings.xml")
        if (!file.exists()) return emptyMap()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            out[name] = node.textContent
        }
        return out
    }

    private fun translatableStrings(locale: String): Map<String, String> {
        val file = File(resourceDir(locale), "strings.xml")
        if (!file.exists()) return emptyMap()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            val translatable = node.attributes.getNamedItem("translatable")?.nodeValue
            if (translatable == "false") continue
            out[name] = node.textContent
        }
        return out
    }

    @Test
    fun allDeclaredLocalesExist() {
        LOCALES.forEach { locale ->
            assertTrue(
                "$locale/strings.xml is missing — locales_config declares it",
                File(resourceDir(locale), "strings.xml").exists(),
            )
        }
    }

    @Test
    fun noStringLocalisesAProtocolIdentifier() {
        // The rule this file exists for. An identifier inside a translatable
        // string is an invitation to translate it.
        LOCALES.forEach { locale ->
            translatableStrings(locale).forEach { (name, value) ->
                NEVER_TRANSLATED.forEach { identifier ->
                    assertTrue(
                        "$locale/$name contains the protocol identifier '$identifier'. " +
                            "Identifiers belong in the layout beside the message, never inside it, " +
                            "so a translator cannot localise them — see docs/i18n.md.",
                        !value.contains(identifier),
                    )
                }
            }
        }
    }

    @Test
    fun everyTranslationHasAKeyInTheDefaultLocale() {
        // A translation for a key that no longer exists is dead weight, and
        // usually means a rename happened in one place only.
        val base = strings("values").keys
        LOCALES.filter { it != "values" }.forEach { locale ->
            strings(locale).keys.forEach { key ->
                assertTrue("$locale defines '$key', which no longer exists in values/", key in base)
            }
        }
    }

    @Test
    fun theSevenRejectionsAllHaveAnExplanation() {
        // NW-P-06: seven distinct rejections, seven distinct messages. Nothing
        // else notices if one silently goes missing.
        val expected =
            listOf(
                "setup_invalid_request",
                "setup_metadata_conflict",
                "setup_pair_timeout",
                "setup_flow_limit",
                "setup_dial_failed",
                "setup_session_replaced",
                "setup_internal_error",
            )
        assertEquals("there are exactly seven rejection reasons", 7, expected.size)
        LOCALES.forEach { locale ->
            val present = strings(locale)
            expected.forEach { key ->
                assertTrue("$locale is missing $key", key in present)
                assertTrue("$locale/$key is empty", present.getValue(key).isNotBlank())
            }
            assertEquals(
                "$locale: the seven explanations must all differ from one another",
                7,
                expected.map { present.getValue(it) }.toSet().size,
            )
        }
    }

    @Test
    fun placeholdersMatchAcrossLocales() {
        // A translation that drops %1$s crashes at format time, in that locale
        // only, on somebody else's phone.
        val base = strings("values")
        val placeholder = Regex("""%\d+\$[sd]|%[sd]""")
        LOCALES.filter { it != "values" }.forEach { locale ->
            strings(locale).forEach { (key, value) ->
                val expected = placeholder.findAll(base[key].orEmpty()).map { it.value }.toSet()
                val actual = placeholder.findAll(value).map { it.value }.toSet()
                assertEquals("$locale/$key placeholders differ from the default locale", expected, actual)
            }
        }
    }

    @Test
    fun theTraditionalTranslationIsNotJustConvertedSimplified() {
        // zh-Hant takes Taiwan vocabulary, not Simplified with swapped glyphs.
        // These pairs are where the two genuinely differ; if they ever match,
        // someone ran a conversion tool.
        val hans = strings("values-b+zh+Hans")
        val hant = strings("values-b+zh+Hant")
        val shared = hans.keys intersect hant.keys
        assertTrue("both Chinese locales should carry strings", shared.isNotEmpty())
        assertTrue(
            "zh-Hant should use 連線 where zh-Hans uses 连接",
            hant.values.any { it.contains("連線") },
        )
        assertTrue(
            "zh-Hant should use 設定 where zh-Hans uses 配置",
            hant.values.any { it.contains("設定") },
        )
    }

    @Test
    fun theBrandNameIsNotTranslatable() {
        // Found by lint asking for a Chinese app_name. The brand does not
        // translate — Chrome is Chrome in every locale — so it is marked rather
        // than left for someone to "fix" by inventing one.
        val file = File(resourceDir("values"), "strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        var found = false
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.attributes.getNamedItem("name")?.nodeValue != "app_name") continue
            found = true
            assertEquals(
                "app_name must be translatable=\"false\"",
                "false",
                node.attributes.getNamedItem("translatable")?.nodeValue,
            )
        }
        assertTrue("app_name is missing from the default locale", found)
    }

    @Test
    fun protocolConstantsAreMarkedUntranslatable() {
        // alpn_default and the scheme are wire values. A translator offered them
        // has been set up to fail.
        val file = File(resourceDir("values"), "strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val required = mutableSetOf("alpn_default", "scheme_client")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            if (name !in required) continue
            required.remove(name)
            assertEquals(
                "$name is a wire value and must be translatable=\"false\"",
                "false",
                node.attributes.getNamedItem("translatable")?.nodeValue,
            )
        }
        assertTrue("missing protocol constants: $required", required.isEmpty())
    }

    @Test
    fun localesConfigListsTheShippingLocales() {
        // Parsed rather than grepped: the file's own comment names zh-rCN as the
        // thing not to do, and a text search cannot tell a counter-example in a
        // comment from a real declaration.
        val config = File("src/main/res/xml/locales_config.xml")
        assertTrue("locales_config.xml is missing", config.exists())

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val nodes = document.getElementsByTagName("locale")
        val declared =
            buildList {
                for (index in 0 until nodes.length) {
                    nodes
                        .item(index)
                        .attributes
                        .getNamedItem("android:name")
                        ?.nodeValue
                        ?.let(::add)
                }
            }

        assertEquals("locales_config must declare exactly the shipping locales", listOf("en", "zh-Hans", "zh-Hant"), declared)
        declared.forEach { locale ->
            assertTrue(
                "'$locale' matches by region; locales must match by script — see docs/i18n.md",
                !locale.contains("-r") && locale !in listOf("zh-CN", "zh-TW", "zh-HK"),
            )
        }
    }
}
