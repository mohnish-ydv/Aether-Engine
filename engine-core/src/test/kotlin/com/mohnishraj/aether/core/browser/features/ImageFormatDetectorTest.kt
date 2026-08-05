package com.mohnishraj.aether.core.browser.features

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageFormatDetectorTest {
    @Test fun detectsPngDimensions() {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
        byteArrayOf(0, 0, 1, 64, 0, 0, 0, 90).copyInto(bytes, 16)
        val metadata = ImageFormatDetector.detect(bytes)
        assertEquals(ImageFormat.PNG, metadata.format)
        assertEquals(320, metadata.width)
        assertEquals(90, metadata.height)
    }

    @Test fun detectsGifAndSvgFoundation() {
        val gif = "GIF89a".toByteArray() + byteArrayOf(0x20, 0x01, 0x58, 0x02)
        val gifMetadata = ImageFormatDetector.detect(gif)
        assertEquals(ImageFormat.GIF, gifMetadata.format)
        assertEquals(288, gifMetadata.width)
        assertEquals(600, gifMetadata.height)
        assertTrue(gifMetadata.animated)

        val svg = "<svg width='128' height='96'><rect width='128' height='96'/></svg>".toByteArray()
        val svgMetadata = ImageFormatDetector.detect(svg)
        assertEquals(ImageFormat.SVG, svgMetadata.format)
        assertEquals(128, svgMetadata.width)
        assertEquals(96, svgMetadata.height)
    }
}
