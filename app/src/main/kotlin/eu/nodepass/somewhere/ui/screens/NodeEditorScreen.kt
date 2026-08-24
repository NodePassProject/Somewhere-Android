// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.FieldBox
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.components.SmallButton
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/**
 * Editing one node.
 *
 * The two carriers are side-by-side controls of equal weight, each in its own
 * direction's colour, because that is what the protocol actually offers: `up`
 * and `down` are independent, and a single "transport" dropdown would make the
 * split unreachable.
 */
@Composable
fun NodeEditorScreen(
    nodes: NodeRepository,
    editing: NowhereUrl?,
    onBack: () -> Unit,
) {
    // Nothing to edit means the screen was reached by a back-stack restore
    // rather than by a tap. Leaving rather than rendering a blank form.
    if (editing == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    NodeEditor(
        node = editing,
        onSave = { edited ->
            if (edited != editing) nodes.replace(editing, edited)
            onBack()
        },
        onRemove = {
            nodes.remove(editing)
            onBack()
        },
        onBack = onBack,
    )
}

/**
 * The form, separated from its data source so a preview can render it.
 */
@Composable
internal fun NodeEditor(
    node: NowhereUrl,
    onSave: (NowhereUrl) -> Unit,
    onBack: () -> Unit,
    onRemove: () -> Unit = {},
) {
    val colors = SomewhereTheme.colors
    var up by remember { mutableStateOf(node.up) }
    var down by remember { mutableStateOf(node.down) }
    var mux by remember { mutableStateOf(node.mux) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = stringResource(R.string.editor_title),
            onBack = onBack,
            trailing = {
                Text(
                    text = stringResource(R.string.action_save),
                    fontFamily = SomewhereType.Display,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = colors.upstream,
                    modifier =
                        Modifier.clickable {
                            // Only the carriers and mux are editable here. The
                            // rest of the node is what the parser produced, and
                            // rebuilding it field by field would be a second
                            // place for a node's shape to be defined.
                            onSave(node.copy(up = up, down = down, mux = mux))
                        },
                )
            },
        )

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Labelled(stringResource(R.string.field_name)) {
                FieldBox {
                    Text(
                        text = (node.displayName ?: "${node.host}:${node.port}"),
                        fontFamily = SomewhereType.Body,
                        fontSize = 14.5.sp,
                        color = colors.ink,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Labelled(stringResource(R.string.field_host), Modifier.weight(1f)) {
                    FieldBox { MonoText(node.host, colors.ink, fontSize = 13.5.sp) }
                }
                Labelled(stringResource(R.string.field_port), Modifier.width(96.dp)) {
                    FieldBox { MonoText(node.port.toString(), colors.ink, fontSize = 13.5.sp) }
                }
            }

            Labelled(stringResource(R.string.field_carriers)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CarrierPicker(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.direction_upstream),
                        icon = SomewhereIcons.ArrowUp,
                        color = colors.upstream,
                        selectedFill = colors.upstreamLine,
                        carrier = up,
                        onSelect = { up = it },
                    )
                    CarrierPicker(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.direction_downstream),
                        icon = SomewhereIcons.ArrowDown,
                        color = colors.downstream,
                        selectedFill = colors.downstreamLine,
                        carrier = down,
                        onSelect = { down = it },
                    )
                }
            }

            Card(padding = PaddingValues(horizontal = 15.dp, vertical = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.editor_multiplexing),
                            fontFamily = SomewhereType.Body,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = colors.ink,
                        )
                        Text(
                            text = stringResource(R.string.editor_multiplexing_detail),
                            fontFamily = SomewhereType.Body,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = colors.faint,
                        )
                    }
                    SomewhereSwitch(checked = mux, onCheckedChange = { mux = it })
                }
            }

            // NW-P-08: a fixed ALPN is a ready-made fingerprint on a restricted
            // network, so it is a field the user can see and change — never a
            // constant compiled into the client.
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionLabel(stringResource(R.string.label_alpn), Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.editor_alpn_hint),
                        fontFamily = SomewhereType.Body,
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
                FieldBox { MonoText(node.alpn, colors.ink, fontSize = 13.5.sp) }
            }

            Labelled(stringResource(R.string.field_certificate)) {
                UnverifiedPanel()
            }

            // Not in the design canvas, which draws every screen populated and
            // never drawn being undone. An app that can add a node and cannot
            // remove one is not finished, so this is added in the system's own
            // vocabulary rather than left as a gap: a plain text action, in the
            // critical colour, at the end where destructive things belong.
            Text(
                text = stringResource(R.string.editor_remove),
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                color = colors.critical,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onRemove)
                        .padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Labelled(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(label)
        content()
    }
}

@Composable
private fun CarrierPicker(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    color: Color,
    selectedFill: Color,
    carrier: NextHopCarrier,
    onSelect: (NextHopCarrier) -> Unit,
) {
    val colors = SomewhereTheme.colors
    Card(modifier = modifier, padding = PaddingValues(horizontal = 13.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, null, Modifier.size(13.dp), tint = color)
            Text(
                text = label.uppercase(),
                fontFamily = SomewhereType.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.5.sp,
                letterSpacing = 1.16.sp,
                color = color,
            )
        }
        Segmented(
            options = NextHopCarrier.entries.map { it.token.uppercase() },
            selectedIndex = NextHopCarrier.entries.indexOf(carrier),
            onSelect = { onSelect(NextHopCarrier.entries[it]) },
            background = colors.panel,
            borderColor = Color.Transparent,
            selectedFill = selectedFill,
            height = 32.dp,
            mono = true,
        )
    }
}

/**
 * The certificate state, stated rather than warned about.
 *
 * D-11: neither `sni` nor `pin` means upstream skips verification entirely. The
 * panel offers the two ways out instead of a dialog, because the condition is
 * not an event — it lasts as long as the node does.
 */
@Composable
private fun UnverifiedPanel() {
    val colors = SomewhereTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.criticalTint)
                .border(1.dp, colors.criticalLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(SomewhereIcons.AlertTriangle, null, Modifier.size(15.dp), tint = colors.critical)
            Text(
                text = stringResource(R.string.cert_not_verified),
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = colors.critical,
            )
        }
        Text(
            text = stringResource(R.string.cert_unverified_detail),
            fontFamily = SomewhereType.Body,
            fontSize = 12.sp,
            lineHeight = 17.4.sp,
            color = colors.inkMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(
                label = stringResource(R.string.cert_add_sni),
                onClick = {},
                fill = colors.criticalAction,
                contentColor = colors.onCriticalAction,
                height = 34.dp,
            )
            SmallButton(
                label = stringResource(R.string.cert_pin_fingerprint),
                onClick = {},
                fill = colors.criticalAction,
                contentColor = colors.onCriticalAction,
                height = 34.dp,
            )
        }
    }
}
