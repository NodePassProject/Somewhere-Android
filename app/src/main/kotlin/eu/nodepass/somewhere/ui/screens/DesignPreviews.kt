// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import eu.nodepass.somewhere.ui.state.SampleState
import eu.nodepass.somewhere.ui.theme.SomewhereTheme

/**
 * The populated design, without a device or a node list.
 *
 * The screens themselves read the real repository, so a fresh install shows
 * empty states — correctly, because a fresh install has no nodes. That leaves
 * the designed screens with nowhere to be seen, which is what these are for:
 * `SampleState` feeds the layout-only halves of each screen so the design stays
 * reviewable against `docs/design-system.md` without inventing runtime state.
 *
 * Both themes on every preview, because the light palette is designed rather
 * than derived and has failed independently before.
 */
@Composable
private fun Framed(content: @Composable () -> Unit) {
    SomewhereTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(SomewhereTheme.colors.ground),
        ) { content() }
    }
}

@Preview(name = "Home · dark", widthDp = 390, heightDp = 844, uiMode = 0x21)
@Preview(name = "Home · light", widthDp = 390, heightDp = 844, uiMode = 0x11)
@Composable
private fun HomePreview() {
    Framed {
        Home(
            node = SampleState.frankfurt,
            session = SampleState.session,
            onOpenNodes = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Node editor · dark", widthDp = 390, heightDp = 844, uiMode = 0x21)
@Preview(name = "Node editor · light", widthDp = 390, heightDp = 844, uiMode = 0x11)
@Composable
private fun NodeEditorPreview() {
    Framed {
        NodeEditor(node = SampleState.frankfurt.url, onSave = {}, onBack = {})
    }
}

@Preview(name = "Diagnostics · dark", widthDp = 390, heightDp = 844, uiMode = 0x21)
@Preview(name = "Diagnostics · light", widthDp = 390, heightDp = 844, uiMode = 0x11)
@Composable
private fun DiagnosticsPreview() {
    Framed { DiagnosticsScreen(entries = SampleState.connectionLog) }
}
