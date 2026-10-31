// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.ui.screens.decodeImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A QR code read from a picture, through the platform's own image decoder.
 *
 * The first version returned "no QR code found" for every picture: the bounds
 * pass of `BitmapFactory` returns null by design, and that null was read as a
 * failure. No JVM test could see it, because the JVM has no `BitmapFactory`.
 */
@RunWith(AndroidJUnit4::class)
class PictureDecodeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val link = "nowhere://0123456789abcdef0123456789abcdef@portal.example.net:443?net=udp#Frankfurt"

    private fun picture(
        name: String,
        scale: Int,
    ): Uri {
        val modules = QrCodes.encode(link)
        val side = modules.size * scale
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        for (y in 0 until side) {
            for (x in 0 until side) {
                bitmap.setPixel(x, y, if (modules[x / scale, y / scale]) Color.BLACK else Color.WHITE)
            }
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun aPictureOfACodeReadsBackAsItsLink() {
        assertEquals(link, decodeImage(context, picture("qr.png", 6)))
    }

    @Test
    fun aPictureLargerThanTheDecodeLimitIsScaledAndStillReads() {
        // 82 modules × 30 px is past the 2048 px cap, so the sampled path runs.
        assertEquals(link, decodeImage(context, picture("qr-large.png", 30)))
    }

    @Test
    fun somethingThatIsNotAPictureIsNoCodeRatherThanACrash() {
        val file = File(context.cacheDir, "not-a-picture.png").apply { writeText("plain text") }
        assertNull(decodeImage(context, Uri.fromFile(file)))
        assertNull(decodeImage(context, Uri.fromFile(File(context.cacheDir, "absent.png"))))
    }
}
