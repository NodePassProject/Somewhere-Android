// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalSomewhereColors =
    staticCompositionLocalOf<SomewhereColors> {
        error("SomewhereColors requested outside SomewhereTheme")
    }

/** The app's own tokens. `MaterialTheme` remains available for stock components. */
object SomewhereTheme {
    val colors: SomewhereColors
        @Composable @ReadOnlyComposable
        get() = LocalSomewhereColors.current
}

/**
 * The app theme.
 *
 * Follows the system light/dark setting. On API 31+ Material You may tint the
 * *neutral* surfaces from the wallpaper, and [SomewhereColors] is layered on top
 * of that unchanged — because of the one rule this file exists to enforce:
 *
 * **Direction colour never comes from dynamic colour.** `upstream` and
 * `downstream` encode which way traffic goes. A wallpaper that resolved both to
 * the same hue would erase the only thing the home screen exists to show, and it
 * would do so silently, on somebody else's device, months later. So the two are
 * read from [SomewhereColors] and never from the Material scheme.
 *
 * @param dynamicNeutrals allow the wallpaper to tint neutral surfaces on 31+.
 */
@Composable
fun SomewhereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicNeutrals: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current

    val materialScheme =
        when {
            dynamicNeutrals && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            darkTheme ->
                darkColorScheme(
                    background = colors.ground,
                    surface = colors.surface,
                    onBackground = colors.ink,
                    onSurface = colors.ink,
                    outline = colors.line,
                )

            else ->
                lightColorScheme(
                    background = colors.ground,
                    surface = colors.surface,
                    onBackground = colors.ink,
                    onSurface = colors.ink,
                    outline = colors.line,
                )
        }

    CompositionLocalProvider(LocalSomewhereColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = SomewhereType.material,
            content = content,
        )
    }
}
