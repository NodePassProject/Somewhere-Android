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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.NextHopCarrier
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.FieldBox
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.QrImage
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.components.Segmented
import eu.nodepass.somewhere.ui.components.SomewhereSwitch
import eu.nodepass.somewhere.ui.copyNodeLink
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.shareNodeLink
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/**
 * Editing one node.
 *
 * One carrier control and one Morph switch, because that is what the protocol
 * offers a client: v3 carries both directions on the carrier `net` names, and
 * Morph has to match the Portal, so a user who changes either is choosing to
 * make this node agree with one.
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
    val context = LocalContext.current
    var net by remember { mutableStateOf(node.net) }
    var morph by remember { mutableStateOf(node.morph) }
    var showingQr by remember { mutableStateOf(false) }
    // The node as it is stored, not as edited: what is shared is a node that
    // exists, and an unsaved change is not one yet.
    val link = node.toUrl()

    // The header stays put and the form scrolls under it. Scrolled with the
    // form, the header slid up beneath the status bar, whose icons then sat
    // on top of the title.
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.editor_title),
            onBack = onBack,
            trailing = {
                Text(
                    text = stringResource(R.string.action_save),
                    fontFamily = SomewhereType.Display,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = colors.brand,
                    modifier =
                        Modifier.clickable {
                            // Only the carrier and Morph are editable here. The
                            // rest of the node is what the parser produced, and
                            // rebuilding it field by field would be a second
                            // place for a node's shape to be defined.
                            onSave(node.copy(net = net, morph = morph))
                        },
                )
            },
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
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
                // One carrier for the whole node: v3 removed split flows, so
                // there is no longer a direction to choose a carrier for.
                Segmented(
                    options = NextHopCarrier.entries.map { carrierLabel(it) },
                    selectedIndex = NextHopCarrier.entries.indexOf(net),
                    onSelect = { net = NextHopCarrier.entries[it] },
                    mono = true,
                )
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
                            text = stringResource(R.string.editor_morph),
                            fontFamily = SomewhereType.Body,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = colors.ink,
                        )
                        Text(
                            text = stringResource(R.string.editor_morph_detail),
                            fontFamily = SomewhereType.Body,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = colors.faint,
                        )
                    }
                    SomewhereSwitch(checked = morph, onCheckedChange = { morph = it })
                }
            }

            Labelled(stringResource(R.string.field_certificate)) {
                CertificatePanel(node.certificateVerification)
            }

            Labelled(stringResource(R.string.editor_share)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Said before the buttons rather than after a tap: the link
                    // is a credential, and the moment to know that is before
                    // it leaves the device.
                    Text(
                        text = stringResource(R.string.editor_share_warning),
                        fontFamily = SomewhereType.Body,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = colors.warn,
                    )
                    Panel {
                        ShareRow(stringResource(R.string.editor_copy_link)) { context.copyNodeLink(link) }
                        PanelDivider()
                        ShareRow(stringResource(R.string.editor_share_link)) { context.shareNodeLink(link) }
                        PanelDivider()
                        ShareRow(stringResource(R.string.editor_show_qr)) { showingQr = true }
                    }
                }
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

    if (showingQr) {
        QrDialog(title = node.displayName ?: "${node.host}:${node.port}", link = link, onDismiss = { showingQr = false })
    }
}

@Composable
private fun ShareRow(
    label: String,
    onClick: () -> Unit,
) {
    PanelRow(onClick = onClick) {
        Text(
            text = label,
            fontFamily = SomewhereType.Body,
            fontWeight = FontWeight.Medium,
            fontSize = 13.5.sp,
            color = SomewhereTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        Icon(SomewhereIcons.ChevronRight, null, Modifier.size(16.dp), tint = SomewhereTheme.colors.faint)
    }
}

/** The node's link as a QR code, for another device's camera. */
@Composable
private fun QrDialog(
    title: String,
    link: String,
    onDismiss: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(14.dp),
        title = { Text(title, style = SomewhereType.rowHeading, color = colors.ink) },
        text = { QrImage(link, stringResource(R.string.editor_show_qr), Modifier.fillMaxWidth()) },
        // The code is the node's key, readable by any camera — and by the
        // screenshot and recent-apps thumbnail a dialog would otherwise allow.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = colors.inkMuted) }
        },
    )
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

/**
 * How this node's certificate is checked — stated, not warned about.
 *
 * v1 had a third state, "not checked at all", and this panel used to be a red
 * warning about it. v3 has no such state: every node is either pinned or held
 * to this device's trust store under a name, so the panel says which, and
 * names the fingerprint or the name so it can be compared with the Portal.
 */
@Composable
private fun CertificatePanel(policy: CertificateVerification) {
    val colors = SomewhereTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                when (policy) {
                    is CertificateVerification.Pin -> stringResource(R.string.cert_pinned)
                    is CertificateVerification.Sni -> stringResource(R.string.cert_trusted_as, policy.host)
                },
            fontFamily = SomewhereType.Body,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = colors.ink,
        )
        when (policy) {
            is CertificateVerification.Pin -> {
                MonoText(policy.sha256, colors.inkMuted, fontSize = 11.sp)
                Text(
                    text = stringResource(R.string.cert_pinned_detail),
                    fontFamily = SomewhereType.Body,
                    fontSize = 12.sp,
                    lineHeight = 17.4.sp,
                    color = colors.inkMuted,
                )
            }
            is CertificateVerification.Sni ->
                Text(
                    text = stringResource(R.string.cert_trusted_detail, policy.host),
                    fontFamily = SomewhereType.Body,
                    fontSize = 12.sp,
                    lineHeight = 17.4.sp,
                    color = colors.inkMuted,
                )
        }
    }
}
