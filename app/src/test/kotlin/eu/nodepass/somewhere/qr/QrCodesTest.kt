// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round trips through rendered pixels, so the decoder is tested against what a
 * camera or a screenshot would actually hand it rather than against ZXing's own
 * bit matrix.
 */
class QrCodesTest {
    private val node =
        "nowhere://0123456789abcdef0123456789abcdef@portal.example.net:443?net=udp&morph=0&sni=portal.example.net#Frankfurt%20%E2%80%94%20%E4%B8%BB"

    /** Renders modules at [scale] pixels each, dark on light or the reverse. */
    private fun render(
        modules: QrCodes.Modules,
        scale: Int,
        inverted: Boolean = false,
    ): Triple<IntArray, Int, Int> {
        val side = modules.size * scale
        val dark = if (inverted) LIGHT else DARK
        val light = if (inverted) DARK else LIGHT
        val pixels = IntArray(side * side) { i -> if (modules[(i % side) / scale, (i / side) / scale]) dark else light }
        return Triple(pixels, side, side)
    }

    @Test
    fun `a node link survives being drawn and read back`() {
        val (pixels, width, height) = render(QrCodes.encode(node), scale = 4)
        assertEquals(node, QrCodes.decodeArgb(pixels, width, height))
    }

    @Test
    fun `a camera frame is read from its luminance plane, padded rows included`() {
        val (pixels, width, height) = render(QrCodes.encode(node), scale = 3)
        // A camera's Y plane commonly pads each row; the reader must honour the
        // stride rather than assume the width.
        val stride = width + 64
        val luminance = ByteArray(stride * height) { 0x7f }
        for (y in 0 until height) {
            for (x in 0 until width) {
                luminance[y * stride + x] = if (pixels[y * width + x] == DARK) 0 else 0xff.toByte()
            }
        }
        assertEquals(node, QrCodes.decodeLuminance(luminance, width, height, stride))
    }

    @Test
    fun `light on dark is read too`() {
        val (pixels, width, height) = render(QrCodes.encode(node), scale = 4, inverted = true)
        assertEquals(node, QrCodes.decodeArgb(pixels, width, height))
    }

    @Test
    fun `a picture with no code in it is nothing, not an error`() {
        val side = 200
        assertNull(QrCodes.decodeArgb(IntArray(side * side) { LIGHT }, side, side))
        assertNull(QrCodes.decodeLuminance(ByteArray(side * side) { (it % 251).toByte() }, side, side))
    }

    @Test
    fun `the code carries a quiet zone`() {
        val modules = QrCodes.encode("x")
        for (i in 0 until modules.size) {
            for (edge in 0 until QrCodes.QUIET_ZONE) {
                assertEquals(false, modules[i, edge])
                assertEquals(false, modules[edge, i])
            }
        }
    }

    private companion object {
        const val DARK = 0xff000000.toInt()
        const val LIGHT = 0xffffffff.toInt()
    }
}
