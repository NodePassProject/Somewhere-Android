// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import eu.nodepass.somewhere.ui.SomewhereApp
import eu.nodepass.somewhere.ui.theme.SomewhereTheme

/**
 * The single activity.
 *
 * Edge to edge on purpose: the design's screens start 52 dp from the top of the
 * display, with the status bar sitting over the app's own ground rather than in
 * a band of its own. A system bar in a different colour would put a seam across
 * every screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SomewhereTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SomewhereTheme.colors.ground),
                ) {
                    SomewhereApp()
                }
            }
        }
    }
}
