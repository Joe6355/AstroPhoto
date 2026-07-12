package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroStretchTest {
    private val profileStretch = AstroStretch()

    @Test
    fun blackRemainsBlackInManualAndProfileStretch() {
        val manual = imageOf(0, 0, 0, 0)
        val profile = manual.copy(pixels = manual.pixels.copyOf())
        applyManualAstroStretchInPlace(manual)
        profileStretch.applyInPlace(profile, AstroStretchMode.MEDIUM)
        assertTrue(manual.pixels.all { channel(it) == 0 })
        assertTrue(profile.pixels.all { channel(it) == 0 })
    }

    @Test
    fun whiteRemainsValidWhite() {
        val manual = imageOf(255, 255, 255, 255)
        val profile = manual.copy(pixels = manual.pixels.copyOf())
        applyManualAstroStretchInPlace(manual)
        profileStretch.applyInPlace(profile, AstroStretchMode.STRONG)
        assertTrue(manual.pixels.all { channel(it) == 255 })
        assertTrue(profile.pixels.all { channel(it) == 255 })
    }

    @Test
    fun flatImageDoesNotDivideByZero() {
        AstroStretchMode.entries.filterNot { it == AstroStretchMode.OFF }.forEach { mode ->
            val image = imageOf(40, 40, 40, 40)
            profileStretch.applyInPlace(image, mode)
            assertValid(image)
        }
        val manual = imageOf(40, 40, 40, 40)
        applyManualAstroStretchInPlace(manual)
        assertValid(manual)
    }

    @Test
    fun manualStretchIsMonotonic() {
        val image = rampImage()
        applyManualAstroStretchInPlace(image)
        assertMonotonic(image)
    }

    @Test
    fun everyProfileModeIsMonotonic() {
        AstroStretchMode.entries.filterNot { it == AstroStretchMode.OFF }.forEach { mode ->
            val image = rampImage()
            profileStretch.applyInPlace(image, mode)
            assertMonotonic(image)
        }
    }

    @Test
    fun darkSignalIsExpanded() {
        val manual = imageOf(0, 0, 0, 20, 20, 20, 40, 40)
        val profile = manual.copy(pixels = manual.pixels.copyOf())
        applyManualAstroStretchInPlace(manual)
        profileStretch.applyInPlace(profile, AstroStretchMode.MEDIUM)
        assertTrue(channel(manual.pixels[3]) > 20)
        assertTrue(channel(profile.pixels[3]) > 20)
    }

    @Test
    fun highlightsAndRgbChannelsRemainClamped() {
        val image = colorImage(
            rgb(250, 120, 5), rgb(255, 200, 30), rgb(30, 250, 180), rgb(1, 2, 255)
        )
        profileStretch.applyInPlace(image, AstroStretchMode.EXTREME_PREVIEW)
        assertValid(image)
    }

    @Test
    fun activeStretchMakesAlphaOpaque() {
        val pixels = intArrayOf(argb(0, 10, 20, 30), argb(64, 40, 50, 60))
        val manual = ArgbPixelImage(2, 1, pixels.copyOf())
        val profile = ArgbPixelImage(2, 1, pixels.copyOf())
        applyManualAstroStretchInPlace(manual)
        profileStretch.applyInPlace(profile, AstroStretchMode.SOFT)
        assertTrue(manual.pixels.all { it ushr 24 and 0xFF == 255 })
        assertTrue(profile.pixels.all { it ushr 24 and 0xFF == 255 })
    }

    @Test
    fun processingIsDeterministic() {
        val first = rampImage()
        val second = first.copy(pixels = first.pixels.copyOf())
        profileStretch.applyInPlace(first, AstroStretchMode.STRONG)
        profileStretch.applyInPlace(second, AstroStretchMode.STRONG)
        assertArrayEquals(first.pixels, second.pixels)
    }

    @Test
    fun invalidImageDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ArgbPixelImage(1, 0, intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ArgbPixelImage(2, 2, IntArray(2)) }
    }

    @Test
    fun invalidManualHistogramParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            manualAstroStretchParameters(IntArray(255), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manualAstroStretchParameters(IntArray(256), 0)
        }
    }

    @Test
    fun offModeDoesNotMutateOwnedPixels() {
        val image = colorImage(argb(12, 10, 20, 30), argb(34, 40, 50, 60))
        val snapshot = image.pixels.copyOf()
        profileStretch.applyInPlace(image, AstroStretchMode.OFF)
        assertArrayEquals(snapshot, image.pixels)
    }

    @Test
    fun manualAndProfileStretchRemainIntentionallyDifferent() {
        val manual = rampImage()
        val profile = manual.copy(pixels = manual.pixels.copyOf())
        val manualParameters = applyManualAstroStretchInPlace(manual)
        val profileResult = profileStretch.applyInPlace(profile, AstroStretchMode.MEDIUM)
        assertEquals(8.0, manualParameters.strength, 0.0)
        assertEquals(1.0, manualParameters.gamma, 0.0)
        assertEquals(manualParameters.blackPoint, profileResult.blackPoint)
        assertEquals(manualParameters.whitePoint, profileResult.whitePoint)
        assertFalse(manual.pixels.contentEquals(profile.pixels))
    }

    @Test
    fun declaredInPlaceApiMutatesTheOwnedArray() {
        val image = imageOf(0, 10, 20, 30, 40, 50, 60, 70)
        val ownedPixels = image.pixels
        profileStretch.applyInPlace(image, AstroStretchMode.SOFT)
        assertTrue(ownedPixels === image.pixels)
        assertFalse(ownedPixels.contentEquals(imageOf(0, 10, 20, 30, 40, 50, 60, 70).pixels))
    }

    private fun rampImage() = imageOf(*IntArray(256) { it })

    private fun imageOf(vararg values: Int) = ArgbPixelImage(
        values.size,
        1,
        values.map { rgb(it, it, it) }.toIntArray()
    )

    private fun colorImage(vararg colors: Int) = ArgbPixelImage(colors.size, 1, colors)

    private fun assertMonotonic(image: ArgbPixelImage) {
        val values = image.pixels.map(::channel)
        values.zipWithNext().forEach { (lower, higher) -> assertTrue(higher >= lower) }
    }

    private fun assertValid(image: ArgbPixelImage) = image.pixels.forEach { color ->
        assertEquals(255, color ushr 24 and 0xFF)
        assertTrue((color ushr 16 and 0xFF) in 0..255)
        assertTrue((color ushr 8 and 0xFF) in 0..255)
        assertTrue((color and 0xFF) in 0..255)
    }

    private fun channel(color: Int) = color and 0xFF
    private fun rgb(red: Int, green: Int, blue: Int) = argb(255, red, green, blue)
    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
