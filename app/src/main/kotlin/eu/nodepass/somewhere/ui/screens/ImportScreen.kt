// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.data.ImportBatch
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.protocol.DecodeResult
import eu.nodepass.somewhere.protocol.url.CertificateVerification
import eu.nodepass.somewhere.protocol.url.ImportLink
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.ui.components.Card
import eu.nodepass.somewhere.ui.components.MonoChip
import eu.nodepass.somewhere.ui.components.MonoText
import eu.nodepass.somewhere.ui.components.Panel
import eu.nodepass.somewhere.ui.components.PanelDivider
import eu.nodepass.somewhere.ui.components.PanelRow
import eu.nodepass.somewhere.ui.components.SectionLabel
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType

/**
 * Adding nodes: scan, subscribe, or paste.
 *
 * The pasted link is parsed and shown back **before** anything is committed, so
 * a bad paste is caught here rather than as a failed connection later — and so
 * the certificate state is visible at the moment of the decision, which is what
 * D-11's third appearance is for.
 */
@Composable
fun ImportScreen(
    nodes: NodeRepository,
    link: String?,
    onClose: () -> Unit,
    onScan: () -> Unit = {},
) {
    val colors = SomewhereTheme.colors
    val clipboard = LocalClipboardManager.current
    var typed by remember(link) { mutableStateOf(link.orEmpty()) }

    // One field, two outcomes, and the parser decides which. A person holding a
    // link does not necessarily know whether it is a node or a subscription —
    // asking them to pick the right box first is asking them to answer a
    // question the app can answer itself.
    //
    // A link that arrived through the deep link and one the user pasted go down
    // exactly the same path. There is no second, more forgiving reader for text
    // the system handed us.
    // A dashboard's "add to app" button hands over a link wrapped in another
    // link — `anywhere://add-proxy?link=…` — so the payload is unwrapped before
    // either parser sees it. The field keeps showing what the user actually
    // gave; what was understood is shown below it, which is the honest way
    // round when the two differ.
    // More than one line is a batch, and every line is judged on its own. The
    // single-link reading below would otherwise call the whole paste one
    // malformed link.
    val batch = remember(typed) { if (ImportBatch.isBatch(typed)) ImportBatch.read(typed) else null }
    val effective = remember(typed, batch) { if (batch != null) "" else ImportLink.unwrap(typed.trim()) }
    val parsed = remember(effective) { effective.takeIf { it.isNotEmpty() }?.let { NowhereUrl.parse(it) } }
    val node = (parsed as? DecodeResult.Ok)?.value
    val subscriptionUrl = remember(effective) { effective.takeIf { it.looksLikeSubscription() } }
    val plaintext = subscriptionUrl?.startsWith("http://", ignoreCase = true) == true

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.import_title),
            onBack = onClose,
            backIcon = SomewhereIcons.Close,
            backDescription = stringResource(R.string.action_close),
        )

        // Scrolls: a pasted batch can list more lines than a screen holds, and
        // the ones it could not read are the ones the user has to see.
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceCard(
                icon = SomewhereIcons.QrCode,
                title = stringResource(R.string.import_scan_qr),
                detail = stringResource(R.string.import_scan_qr_detail),
                onClick = onScan,
            )
            // Pastes rather than opening a second input: the field below is
            // already where a link goes, and the card's job is to save the
            // long-press that putting one there would otherwise take.
            SourceCard(
                icon = SomewhereIcons.Link,
                title = stringResource(R.string.import_subscription_link),
                detail = stringResource(R.string.import_subscription_link_detail),
                onClick = {
                    clipboard
                        .getText()
                        ?.text
                        ?.takeIf { it.isNotBlank() }
                        ?.let { typed = it }
                },
            )

            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                SectionLabel(stringResource(R.string.import_paste_label))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(
                            1.dp,
                            if (parsed is DecodeResult.Invalid) colors.criticalLine else colors.goodLine,
                            RoundedCornerShape(10.dp),
                        ).padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    if (node != null) {
                        // Once it parses, the key is masked. The field stops
                        // being an input and becomes a statement of what was
                        // understood — and a shared key must not sit on screen
                        // waiting to be screenshotted.
                        Text(
                            text = maskedUrl(node),
                            fontFamily = SomewhereType.Mono,
                            fontSize = 12.sp,
                            lineHeight = 19.2.sp,
                            color = colors.inkMuted,
                        )
                    } else {
                        BasicTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            textStyle =
                                TextStyle(
                                    fontFamily = SomewhereType.Mono,
                                    fontSize = 12.sp,
                                    lineHeight = 19.2.sp,
                                    color = colors.inkMuted,
                                ),
                            cursorBrush = SolidColor(colors.brand),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (typed.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.import_paste_hint),
                                        fontFamily = SomewhereType.Mono,
                                        fontSize = 12.sp,
                                        color = colors.faint,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                }

                batch?.let { BatchSummary(it) }

                // The parser's own reason, verbatim. It distinguishes a wrong
                // scheme from a bad port from a key with a password component,
                // and a screen that replaced all three with "invalid link"
                // would be throwing away the only useful part.
                if (subscriptionUrl != null) {
                    Text(
                        text = stringResource(R.string.import_subscription_detected),
                        fontFamily = SomewhereType.Body,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = colors.inkMuted,
                    )
                    if (plaintext) {
                        // Refused, not warned about. The URL is the credential:
                        // over http it is handed to every device between here
                        // and the dashboard, and the platform blocks the request
                        // anyway on this target SDK. Warning and then leaving
                        // the button enabled produced the worst outcome of the
                        // three — a tap that silently did nothing at all.
                        Text(
                            text = stringResource(R.string.import_subscription_plaintext),
                            fontFamily = SomewhereType.Body,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = colors.critical,
                        )
                        Text(
                            text = stringResource(R.string.import_subscription_needs_https),
                            fontFamily = SomewhereType.Body,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = colors.inkMuted,
                        )
                    }
                }

                (parsed as? DecodeResult.Invalid)?.takeIf { subscriptionUrl == null }?.let { failure ->
                    Text(
                        text = stringResource(R.string.import_invalid),
                        fontFamily = SomewhereType.Body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        color = colors.critical,
                    )
                    Text(
                        text = failure.reason.detail,
                        fontFamily = SomewhereType.Body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = colors.inkMuted,
                    )
                }

                if (node != null) {
                    Panel {
                        PanelRow(Modifier.padding(vertical = 11.dp)) {
                            RowLabel(stringResource(R.string.field_host), Modifier.weight(1f))
                            MonoText("${node.host}:${node.port}", colors.ink, fontSize = 12.sp)
                        }
                        PanelDivider()
                        PanelRow(Modifier.padding(vertical = 11.dp)) {
                            RowLabel(stringResource(R.string.field_carriers), Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                MonoChip(carrierLabel(node.net), colors.muted, colors.surfaceAlt, fontSize = 10.sp)
                                if (node.morph) MonoChip("MORPH", colors.muted, colors.surfaceAlt, fontSize = 10.sp)
                            }
                        }
                        PanelDivider()
                        PanelRow(Modifier.padding(vertical = 11.dp)) {
                            RowLabel(stringResource(R.string.field_certificate), Modifier.weight(1f))
                            // Always a statement, never a warning: v3 verifies
                            // every node one way or the other, so what the
                            // reader needs is which way.
                            Text(
                                text =
                                    when (val policy = node.certificateVerification) {
                                        is CertificateVerification.Pin -> stringResource(R.string.cert_pinned)
                                        is CertificateVerification.Sni -> stringResource(R.string.cert_trusted_as, policy.host)
                                    },
                                fontFamily = SomewhereType.Body,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = colors.good,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // The affordance stays visible when disabled rather than hidden, so the
        // reason the button does nothing is on the screen next to it.
        val enabled = node != null || (subscriptionUrl != null && !plaintext) || batch?.isEmpty == false
        Box(
            Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) colors.primaryAction else colors.surfaceAlt)
                .clickable(enabled = enabled) {
                    when {
                        batch != null -> {
                            nodes.addAll(batch.nodes)
                            // Each on the repository's scope, like a single
                            // subscribe: this screen closes on the next line.
                            batch.subscriptions.forEach(nodes::subscribe)
                            onClose()
                        }

                        node != null -> {
                            nodes.add(node)
                            onClose()
                        }

                        subscriptionUrl != null -> {
                            // Fire and leave, on the repository's scope rather
                            // than the composition's. This screen is about to
                            // close, and a fetch launched from a scope that
                            // closes with it is a fetch that never happens.
                            nodes.subscribe(subscriptionUrl)
                            onClose()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    stringResource(
                        when {
                            batch != null -> R.string.import_add_all
                            subscriptionUrl != null -> R.string.import_subscribe
                            else -> R.string.import_add_node
                        },
                    ),
                fontFamily = SomewhereType.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (enabled) colors.onPrimaryAction else colors.faint,
            )
        }
    }
}

/**
 * What a batch paste was understood as, and every line that was not.
 *
 * The lines that failed are listed by number with the parser's own reason, as
 * a single failed paste is: a batch that said only "3 lines skipped" would
 * leave the user to find them.
 */
@Composable
private fun BatchSummary(batch: ImportBatch) {
    val colors = SomewhereTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.import_batch_summary, batch.nodes.size, batch.subscriptions.size),
            fontFamily = SomewhereType.Body,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            color = if (batch.isEmpty) colors.critical else colors.good,
        )
        if (batch.rejected.isNotEmpty()) {
            Text(
                text = stringResource(R.string.import_batch_rejected),
                fontFamily = SomewhereType.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = colors.critical,
            )
            batch.rejected.forEach { rejected ->
                val why =
                    if (rejected.reason == ImportBatch.PlaintextSubscription) {
                        stringResource(R.string.import_subscription_plaintext)
                    } else {
                        // The parser's own reason, as a single pasted link shows
                        // it: a URL grammar error has no sentence of its own.
                        rejected.reason.detail
                    }
                Text(
                    text = stringResource(R.string.import_batch_line, rejected.line, why),
                    fontFamily = SomewhereType.Body,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

/**
 * Anything `http`/`https` is treated as a subscription.
 *
 * Deliberately shallow: the real validation is `SubscriptionEndpoint.prepare`,
 * which runs on fetch and has its own reasons for refusing. Duplicating that
 * judgement here would give two definitions of a valid subscription URL that
 * can disagree, and the one the user sees would be the weaker of the two.
 */
private fun String.looksLikeSubscription(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

@Composable
private fun RowLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = SomewhereType.Body,
        fontSize = 12.sp,
        color = SomewhereTheme.colors.muted,
    )
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val colors = SomewhereTheme.colors
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        padding = PaddingValues(horizontal = 18.dp, vertical = 17.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.brandTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = colors.brand)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = title, style = SomewhereType.rowHeading, color = colors.ink)
                Text(text = detail, style = SomewhereType.bodySmall, color = colors.muted)
            }
        }
    }
}

/**
 * The pasted link, with the key masked.
 *
 * The shared key is the one value in this project that must not reach a screen,
 * a log or a screenshot — `SharedKey.toString()` already refuses to render it,
 * and this keeps the same promise where the URL is shown back to the user.
 */
@Composable
private fun maskedUrl(url: NowhereUrl): AnnotatedString {
    val colors = SomewhereTheme.colors
    // Everything after the key exactly as the node would be shared, so what is
    // shown is what was understood rather than a second rendering of it.
    val rest = url.toUrl().substringAfter('@')
    return buildAnnotatedString {
        append("nowhere://")
        withMasked(colors.faint)
        append("@$rest")
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.withMasked(color: androidx.compose.ui.graphics.Color) {
    pushStyle(SpanStyle(color = color))
    append("••••••••")
    pop()
}
