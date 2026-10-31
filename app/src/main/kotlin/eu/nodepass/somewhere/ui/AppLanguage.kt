// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.io.File
import java.util.Locale

/**
 * The app's own language: the device's by default, or one the user picks.
 *
 * **Android 13 and later** keep the choice themselves — per-app languages,
 * offered from `locales_config.xml` on the system's own settings page — and
 * this goes through the same platform call, so that page and this one always
 * agree and there is no second store of the choice.
 *
 * **Below 13** there is no platform store, so the choice is a one-line file
 * and every component that draws text — the activities and the VPN service,
 * whose notification speaks too — wraps its context in it ([wrap]). That is
 * what AppCompat does internally; this app does not carry AppCompat, and one
 * function is cheaper than a library.
 *
 * ## Adding a language
 *
 * Add it to [LANGUAGES], add `res/values-b+<lang>[+<Script>]/strings.xml`, and
 * add it to `res/xml/locales_config.xml`. `StringResourceTest` fails until the
 * three agree and every string is translated. See `docs/i18n.md`.
 */
object AppLanguage {
    /**
     * Every shipping language, by BCP 47 tag, with its name in itself.
     *
     * The name is the language's own — "简体中文", not "Simplified Chinese" —
     * because the person looking for it may not read the current one. A
     * contributor who leaves it empty gets the platform's own name for it.
     */
    val LANGUAGES: List<Pair<String, String>> =
        listOf(
            "en" to "English",
            "zh-Hans" to "简体中文",
            "zh-Hant" to "繁體中文",
        )

    /** The choices in the language dialog, in order. Empty is the device's own. */
    val tags: List<String> = listOf("") + LANGUAGES.map { it.first }

    private val platformStore: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun endonym(tag: String): String =
        LANGUAGES.firstOrNull { it.first == tag }?.second?.takeIf { it.isNotEmpty() }
            ?: Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    /** The chosen tag, or empty for the device's own. */
    fun current(context: Context): String {
        if (!platformStore) return stored(context)
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return ""
        if (locales.isEmpty) return ""
        return closest(locales[0])
    }

    /**
     * The shipping tag a locale reads as, or empty.
     *
     * Chinese by script first and region second — a phone set to `zh-TW`
     * carries no script, and reads Traditional — and every other language by
     * its language alone.
     */
    internal fun closest(locale: Locale): String {
        val shipping = LANGUAGES.map { it.first }
        if (locale.language == "zh") {
            val script =
                locale.script.ifEmpty { if (locale.country in setOf("TW", "HK", "MO")) "Hant" else "Hans" }
            return "zh-$script".takeIf { it in shipping } ?: ""
        }
        return shipping.firstOrNull { Locale.forLanguageTag(it).language == locale.language } ?: ""
    }

    /** Sets the app's language and redraws [context]'s activity in it. */
    fun set(
        context: Context,
        tag: String,
    ) {
        if (platformStore) {
            // The system recreates the activity itself.
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        runCatching {
            file(context).apply { parentFile?.mkdirs() }.writeText(tag)
        }
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.recreate()
    }

    /**
     * [base] in the chosen language, for `attachBaseContext`.
     *
     * A no-op from 13, where the platform applies the choice to every context
     * the app has, and when the choice is the device's own.
     */
    fun wrap(base: Context): Context {
        if (platformStore) return base
        val tag = stored(base)
        if (tag.isEmpty()) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }

    private fun stored(context: Context): String =
        runCatching { file(context).takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it in tags }
            .orEmpty()

    private fun file(context: Context): File = File(context.applicationContext?.filesDir ?: context.filesDir, "settings/language.txt")
}
