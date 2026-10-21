// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.qr.QrCodes
import eu.nodepass.somewhere.ui.components.SmallButton
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scanning a QR code, from the camera or from a picture.
 *
 * The decoded text goes back to the import screen as if it had been pasted,
 * and is judged there. Nothing here knows what a node is.
 *
 * The camera is asked for when this screen opens and not before: a client that
 * wanted the camera at install time, for a feature most people use once, would
 * be asking for more than it needs.
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val colors = SomewhereTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cameraAllowed by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraAllowed = granted
            asked = true
        }
    LaunchedEffect(Unit) {
        if (!cameraAllowed) permission.launch(Manifest.permission.CAMERA)
    }

    // Exactly once: the analyser keeps delivering frames after the first
    // success, and a second navigation would pop a screen that is not this one.
    val delivered = remember { AtomicBoolean(false) }
    val deliver: (String) -> Unit = { text ->
        if (delivered.compareAndSet(false, true)) onScanned(text)
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text = withContext(Dispatchers.Default) { decodeImage(context, uri) }
                if (text != null) deliver(text) else notFound = true
            }
        }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.scan_title), onBack = onBack)

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceAlt)
                        .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (cameraAllowed) {
                    CameraPreview(onDecoded = deliver)
                } else if (asked) {
                    Text(
                        text = stringResource(R.string.scan_no_camera),
                        style = SomewhereType.bodySmall,
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            Text(
                text = stringResource(if (notFound) R.string.scan_not_found else R.string.scan_hint),
                style = SomewhereType.bodySmall,
                color = if (notFound) colors.critical else colors.muted,
                textAlign = TextAlign.Center,
            )

            if (!cameraAllowed && asked) {
                SmallButton(
                    label = stringResource(R.string.scan_allow_camera),
                    onClick = { permission.launch(Manifest.permission.CAMERA) },
                    fill = colors.surface,
                    contentColor = colors.ink,
                    height = 40.dp,
                )
            }
            SmallButton(
                label = stringResource(R.string.scan_choose_image),
                onClick = {
                    notFound = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                fill = colors.primaryAction,
                contentColor = colors.onPrimaryAction,
                height = 40.dp,
            )
        }
    }
}

/**
 * The camera, feeding frames to the decoder until one of them reads.
 *
 * Frames are analysed on their own thread and the latest one wins: a decoder
 * that fell behind would otherwise work through a queue of frames the user has
 * long since moved the phone away from.
 */
@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val analysis = remember { Executors.newSingleThreadExecutor() }
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val cameras = future.get()
            provider = cameras
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analyser =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analyser ->
                        val main = ContextCompat.getMainExecutor(context)
                        analyser.setAnalyzer(analysis) { frame ->
                            // Decoded here, delivered on the main thread: what
                            // happens next is navigation.
                            frame.use { decodeFrame(it) }?.let { text -> main.execute { onDecoded(text) } }
                        }
                    }
            // A device with no back camera — a tablet, a TV, an emulator
            // without one — gets the picture button rather than a crash.
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyser)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analysis.shutdown()
        }
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** The frame's Y plane, which is all a QR decoder needs. */
private fun decodeFrame(frame: ImageProxy): String? {
    val plane = frame.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    // The last row of a padded plane is often shorter than the stride, so the
    // buffer can be a few bytes short of stride × height. Padding it out is
    // cheaper than teaching the decoder about a ragged final row.
    val needed = plane.rowStride * frame.height
    val luminance = if (bytes.size >= needed) bytes else bytes.copyOf(needed)
    return QrCodes.decodeLuminance(luminance, frame.width, frame.height, plane.rowStride)
}

/**
 * A picture the user chose, decoded at a size a QR code survives.
 *
 * Scaled down first: a 48-megapixel photo decoded at full size is a quarter of
 * a gigabyte of pixels to find one code in, and a code that fills any useful
 * part of the frame is still legible at a fraction of that.
 */
private fun decodeImage(
    context: Context,
    uri: Uri,
): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_IMAGE_SIDE || bounds.outHeight / sample > MAX_IMAGE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap: Bitmap =
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        QrCodes.decodeArgb(pixels, bitmap.width, bitmap.height)
    } finally {
        bitmap.recycle()
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** The longest side a chosen picture is decoded at. */
private const val MAX_IMAGE_SIDE = 2048
