// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * The app's own language, where the platform lets an app have one.
 *
 * Android 13 introduced per-app languages, and `locales_config.xml` already
 * offers this app's three in the system's settings. This is the same switch
 * from inside the app, through the same platform call — so the system's page
 * and this one always agree, and there is no second store of the choice.
 *
 * Below 13 there is no supported way without AppCompat, which this app does
 * not carry; the row is not shown there and the device's language applies.
 */
object AppLanguage {
    /** The three locales `docs/i18n.md` ships, by tag. Empty is the device's own. */
    val tags: List<String> = listOf("", "en", "zh-Hans", "zh-Hant")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * A language's name in that language — "简体中文", not "Simplified
     * Chinese" — because the person looking for it may not read the current
     * one. Not a translated string, deliberately.
     */
    fun endonym(tag: String): String =
        when (tag) {
            "en" -> "English"
            "zh-Hans" -> "简体中文"
            "zh-Hant" -> "繁體中文"
            else -> tag
        }

    fun current(context: Context): String {
        if (!supported) return ""
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return ""
        if (locales.isEmpty) return ""
        val first = locales[0]
        return when {
            first.language == "zh" && (first.script == "Hant" || first.country in setOf("TW", "HK", "MO")) -> "zh-Hant"
            first.language == "zh" -> "zh-Hans"
            first.language == "en" -> "en"
            else -> ""
        }
    }

    /** Sets the app's language; the system recreates the activity to apply it. */
    fun set(
        context: Context,
        tag: String,
    ) {
        if (!supported) return
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
