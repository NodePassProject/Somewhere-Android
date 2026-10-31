// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes in both directions, with nothing Android in it.
 *
 * Decoding hands back the text and nothing else. What the text *is* — a node, a
 * subscription, a dashboard's wrapped link, or nonsense — is decided by the
 * same import path a pasted link goes down, so a QR code cannot become a way
 * to reach code a paste cannot.
 */
object QrCodes {
    /**
     * A camera frame's luminance plane: one byte per pixel, rows [rowStride]
     * bytes apart. The Y plane of a YUV_420_888 image is exactly this.
     */
    fun decodeLuminance(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? =
        decode(
            PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false),
            tryHarder = false,
        )

    /**
     * An image's pixels as packed ARGB, as `Bitmap.getPixels` returns them.
     *
     * Tried harder than a camera frame: a picture is decoded once, so spending
     * longer on it costs nothing, and screenshots of QR codes are often small.
     */
    fun decodeArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): String? = decode(RGBLuminanceSource(width, height, pixels), tryHarder = true)

    private fun decode(
        source: LuminanceSource,
        tryHarder: Boolean,
    ): String? {
        val hints =
            buildMap<DecodeHintType, Any> {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                // Light on dark is common on dashboards with a dark theme.
                put(DecodeHintType.ALSO_INVERTED, true)
                if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
            }
        return try {
            // MultiFormatReader rather than QRCodeReader, even for one format:
            // ALSO_INVERTED is honoured by the former and silently ignored by
            // the latter, which a test of light-on-dark codes found.
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            // A code was found and could not be read: a blurred or partial
            // frame. The next frame is the retry.
            null
        }
    }

    /**
     * [text] as a square of modules, `true` for dark, with a quiet zone.
     *
     * Medium error correction: enough to survive a phone screen photographed
     * at an angle, without making a long node URL denser than a camera can
     * comfortably resolve.
     */
    fun encode(text: String): Modules {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to QUIET_ZONE,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        val size = matrix.width
        return Modules(size, BooleanArray(size * size) { matrix[it % size, it / size] })
    }

    /** A QR code's modules, row by row. */
    class Modules(
        val size: Int,
        private val dark: BooleanArray,
    ) {
        operator fun get(
            x: Int,
            y: Int,
        ): Boolean = dark[y * size + x]
    }

    /** Modules of light border on each side, the minimum the standard asks for. */
    const val QUIET_ZONE: Int = 4
}
