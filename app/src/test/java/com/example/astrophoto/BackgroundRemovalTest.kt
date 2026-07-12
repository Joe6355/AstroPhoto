package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRemovalTest {
    private val removal = BackgroundRemoval()

    @Test
    fun uniformBackgroundRemainsSpatiallyUniform() {
        val image = SyntheticImageTestData.uniform(40, 30, 60)
        removal.applyInPlace(image, BackgroundRemovalMode.SOFT)
        assertEquals(1, image.pixels.toSet().size)
    }

    @Test
    fun horizontalGradientRangeIsReduced() {
        assertGradientReduced { x, _ -> 20 + x }
    }

    @Test
    fun verticalGradientRangeIsReduced() {
        assertGradientReduced { _, y -> 20 + y }
    }

    @Test
    fun diagonalGradientRangeIsReduced() {
        assertGradientReduced { x, y -> 20 + (x + y) / 2 }
    }

    @Test
    fun isolatedStarDoesNotChangeFarBackgroundModel() {
        val baseline = SyntheticImageTestData.uniform(48, 48, 30)
        val starField = baseline.copy(pixels = baseline.pixels.copyOf())
        starField.pixels[24 * 48 + 24] = gray(220)
        removal.applyInPlace(baseline, BackgroundRemovalMode.STRONG)
        removal.applyInPlace(starField, BackgroundRemovalMode.STRONG)
        assertEquals(baseline.pixelAt(4, 4), starField.pixelAt(4, 4))
        assertTrue(luminance(starField.pixelAt(24, 24)) > luminance(starField.pixelAt(4, 4)))
    }

    @Test
    fun largeBrightBlobDoesNotCorruptFarBackground() {
        val baseline = SyntheticImageTestData.uniform(64, 64, 30)
        val blob = baseline.copy(pixels = baseline.pixels.copyOf())
        for (y in 24..39) for (x in 24..39) blob.pixels[y * 64 + x] = gray(210)
        removal.applyInPlace(baseline, BackgroundRemovalMode.STRONG)
        removal.applyInPlace(blob, BackgroundRemovalMode.STRONG)
        assertEquals(baseline.pixelAt(5, 5), blob.pixelAt(5, 5))
    }

    @Test
    fun blackAndWhiteImagesDoNotCrash() {
        val black = SyntheticImageTestData.uniform(8, 8, 0)
        val white = SyntheticImageTestData.uniform(8, 8, 255)
        removal.applyInPlace(black, BackgroundRemovalMode.URBAN)
        removal.applyInPlace(white, BackgroundRemovalMode.URBAN)
        assertChannelsValid(black)
        assertChannelsValid(white)
    }

    @Test
    fun onePixelImageIsHandledPredictably() {
        val image = SyntheticImageTestData.uniform(1, 1, 50)
        removal.applyInPlace(image, BackgroundRemovalMode.SOFT)
        assertEquals(gray(44), image.pixels.single())
    }

    @Test
    fun outputChannelsAndAlphaAreValid() {
        val image = colorImage(20, 20) { x, y -> rgb(x * 13, y * 13, (x + y) * 7) }
        removal.applyInPlace(image, BackgroundRemovalMode.URBAN)
        assertChannelsValid(image)
    }

    @Test
    fun processingIsDeterministic() {
        val first = gradientImage { x, y -> 20 + (x * 7 + y * 11) % 100 }
        val second = first.copy(pixels = first.pixels.copyOf())
        removal.applyInPlace(first, BackgroundRemovalMode.STRONG)
        removal.applyInPlace(second, BackgroundRemovalMode.STRONG)
        assertArrayEquals(first.pixels, second.pixels)
    }

    @Test
    fun noneModeDoesNotMutateInput() {
        val image = gradientImage { x, y -> 20 + x + y }
        val snapshot = image.pixels.copyOf()
        removal.applyInPlace(image, BackgroundRemovalMode.NONE)
        assertArrayEquals(snapshot, image.pixels)
    }

    @Test
    fun invalidDimensionsAndPixelCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ArgbPixelImage(0, 1, intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ArgbPixelImage(2, 2, IntArray(3)) }
    }

    @Test
    fun neutralGreyRemainsNeutralInUrbanMode() {
        val image = SyntheticImageTestData.uniform(32, 32, 90)
        removal.applyInPlace(image, BackgroundRemovalMode.URBAN)
        assertNeutral(image.pixels.first())
    }

    @Test
    fun orangeAndRedCastsAreReduced() {
        listOf(rgb(130, 80, 35), rgb(150, 65, 65)).forEach { color ->
            val image = ArgbPixelImage(32, 32, IntArray(32 * 32) { color })
            val before = channelRange(color)
            removal.applyInPlace(image, BackgroundRemovalMode.URBAN)
            assertTrue(channelRange(image.pixels.first()) < before)
        }
    }

    @Test
    fun blueCastIsNotConvertedToOrange() {
        val image = ArgbPixelImage(32, 32, IntArray(32 * 32) { rgb(50, 75, 140) })
        removal.applyInPlace(image, BackgroundRemovalMode.URBAN)
        val output = image.pixels.first()
        assertTrue(blue(output) >= red(output))
    }

    @Test
    fun brightStarRetainsChannelOrdering() {
        val image = ArgbPixelImage(40, 40, IntArray(1600) { rgb(100, 70, 40) })
        image.pixels[20 * 40 + 20] = rgb(220, 180, 140)
        removal.applyInPlace(image, BackgroundRemovalMode.URBAN)
        val star = image.pixelAt(20, 20)
        assertTrue(red(star) > green(star))
        assertTrue(green(star) > blue(star))
    }

    private fun assertGradientReduced(value: (Int, Int) -> Int) {
        val image = gradientImage(value)
        val before = luminanceRange(image)
        removal.applyInPlace(image, BackgroundRemovalMode.STRONG)
        assertTrue("before=$before after=${luminanceRange(image)}", luminanceRange(image) < before)
    }

    private fun gradientImage(value: (Int, Int) -> Int) =
        colorImage(64, 64) { x, y -> gray(value(x, y).coerceIn(0, 255)) }

    private fun colorImage(width: Int, height: Int, color: (Int, Int) -> Int): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = color(x, y)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun assertChannelsValid(image: ArgbPixelImage) = image.pixels.forEach { color ->
        assertEquals(255, color ushr 24 and 0xFF)
        assertTrue(red(color) in 0..255 && green(color) in 0..255 && blue(color) in 0..255)
    }

    private fun assertNeutral(color: Int) {
        assertEquals(red(color), green(color))
        assertEquals(green(color), blue(color))
    }

    private fun luminanceRange(image: ArgbPixelImage): Int {
        val values = image.pixels.map(::luminance)
        return values.maxOrNull()!! - values.minOrNull()!!
    }

    private fun channelRange(color: Int) =
        maxOf(red(color), green(color), blue(color)) - minOf(red(color), green(color), blue(color))

    private fun luminance(color: Int) = pixelLuminance(color)
    private fun gray(value: Int) = rgb(value, value, value)
    private fun rgb(red: Int, green: Int, blue: Int) =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
    private fun red(color: Int) = color ushr 16 and 0xFF
    private fun green(color: Int) = color ushr 8 and 0xFF
    private fun blue(color: Int) = color and 0xFF
}
