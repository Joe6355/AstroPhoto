package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarBoostTest {
    private val boost = StarBoost()

    @Test
    fun noStarsOnUniformImageCreatesNoBrightObjects() {
        val image = SyntheticImageTestData.uniform(32, 32, 50)
        val snapshot = image.pixels.copyOf()
        val result = boost.applyInPlace(image, emptyList(), StarBoostMode.SOFT)
        assertArrayEquals(snapshot, image.pixels)
        assertEquals(0, result.starsBoosted)
    }

    @Test
    fun noDetectedStarsNeverTurnsNoiseIntoDetail() {
        val image = ArgbPixelImage(
            32,
            32,
            IntArray(32 * 32) { index ->
                val noise = (index * 37 + index / 11) % 19
                rgb(35 + noise, 32 + noise, 40 + noise)
            }
        )
        val snapshot = image.pixels.copyOf()

        val result = boost.applyInPlace(image, emptyList(), StarBoostMode.STRONG)

        assertArrayEquals(snapshot, image.pixels)
        assertEquals(0, result.starsBoosted)
    }

    @Test
    fun oneIsolatedStarIsEnhanced() {
        val image = SyntheticImageTestData.stars(
            32, 32, 20, listOf(Triple(16, 16, 100))
        )
        val before = pixelLuminance(image.pixelAt(16, 16))
        val result = boost.applyInPlace(image, listOf(star(16, 16, 100)), StarBoostMode.SOFT)
        assertTrue(pixelLuminance(image.pixelAt(16, 16)) > before)
        assertEquals(1, result.starsBoosted)
    }

    @Test
    fun multipleStarsAreEnhanced() {
        val image = SyntheticImageTestData.stars(
            40, 40, 20,
            listOf(Triple(10, 10, 80), Triple(20, 25, 120), Triple(31, 15, 160))
        )
        val positions = listOf(10 to 10, 20 to 25, 31 to 15)
        val before = positions.map { (x, y) -> pixelLuminance(image.pixelAt(x, y)) }
        boost.applyInPlace(
            image,
            positions.mapIndexed { index, (x, y) -> star(x, y, before[index]) },
            StarBoostMode.STRONG
        )
        positions.forEachIndexed { index, (x, y) ->
            assertTrue(pixelLuminance(image.pixelAt(x, y)) > before[index])
        }
    }

    @Test
    fun distantBackgroundRemainsUnchanged() {
        val image = SyntheticImageTestData.stars(40, 40, 30, listOf(Triple(20, 20, 100)))
        val before = image.pixelAt(2, 2)
        boost.applyInPlace(image, listOf(star(20, 20, 100)), StarBoostMode.STRONG)
        assertEquals(before, image.pixelAt(2, 2))
    }

    @Test
    fun largeBrightBlobIsSkippedByCurrentRadiusRule() {
        val image = SyntheticImageTestData.uniform(32, 32, 80)
        val snapshot = image.pixels.copyOf()
        val result = boost.applyInPlace(
            image,
            listOf(star(16, 16, 180).copy(radius = 6f)),
            StarBoostMode.STRONG
        )
        assertArrayEquals(snapshot, image.pixels)
        assertEquals(0, result.starsBoosted)
    }

    @Test
    fun saturatedStarRemainsClampedAndIsSkipped() {
        val image = SyntheticImageTestData.stars(20, 20, 20, listOf(Triple(10, 10, 255)))
        boost.applyInPlace(image, listOf(star(10, 10, 255)), StarBoostMode.STRONG)
        assertEquals(255, pixelLuminance(image.pixelAt(10, 10)))
    }

    @Test
    fun rgbChannelsRemainValid() {
        val pixels = IntArray(25 * 25) { rgb(20, 30, 40) }
        pixels[12 * 25 + 12] = rgb(240, 150, 80)
        val image = ArgbPixelImage(25, 25, pixels)
        boost.applyInPlace(image, listOf(star(12, 12, 160)), StarBoostMode.STRONG)
        image.pixels.forEach { color ->
            assertTrue((color ushr 16 and 0xFF) in 0..255)
            assertTrue((color ushr 8 and 0xFF) in 0..255)
            assertTrue((color and 0xFF) in 0..255)
        }
    }

    @Test
    fun activeBoostMakesEntireOutputOpaque() {
        val pixels = IntArray(20 * 20) { argb(20, 30, 30, 30) }
        pixels[10 * 20 + 10] = argb(40, 100, 100, 100)
        val image = ArgbPixelImage(20, 20, pixels)
        boost.applyInPlace(image, listOf(star(10, 10, 100)), StarBoostMode.SOFT)
        assertTrue(image.pixels.all { it ushr 24 and 0xFF == 255 })
    }

    @Test
    fun boundaryStarsDoNotReadOutsideArray() {
        val image = SyntheticImageTestData.stars(
            8, 8, 20,
            listOf(Triple(0, 0, 100), Triple(7, 0, 100), Triple(0, 7, 100), Triple(7, 7, 100))
        )
        boost.applyInPlace(
            image,
            listOf(star(0, 0, 100), star(7, 0, 100), star(0, 7, 100), star(7, 7, 100)),
            StarBoostMode.STRONG
        )
        assertEquals(64, image.pixels.size)
    }

    @Test
    fun processingIsDeterministic() {
        val first = SyntheticImageTestData.stars(32, 32, 20, listOf(Triple(16, 16, 100)))
        val second = first.copy(pixels = first.pixels.copyOf())
        val stars = listOf(star(16, 16, 100))
        boost.applyInPlace(first, stars, StarBoostMode.STRONG)
        boost.applyInPlace(second, stars, StarBoostMode.STRONG)
        assertArrayEquals(first.pixels, second.pixels)
    }

    @Test
    fun declaredInPlaceApiMutatesOwnedArray() {
        val image = SyntheticImageTestData.stars(20, 20, 20, listOf(Triple(10, 10, 100)))
        val owned = image.pixels
        val snapshot = owned.copyOf()
        boost.applyInPlace(image, listOf(star(10, 10, 100)), StarBoostMode.SOFT)
        assertTrue(owned === image.pixels)
        assertFalse(snapshot.contentEquals(owned))
    }

    @Test
    fun offModeDoesNotMutateInput() {
        val image = SyntheticImageTestData.stars(20, 20, 20, listOf(Triple(10, 10, 100)))
        val snapshot = image.pixels.copyOf()
        boost.applyInPlace(image, listOf(star(10, 10, 100)), StarBoostMode.OFF)
        assertArrayEquals(snapshot, image.pixels)
    }

    @Test
    fun invalidDimensionsAndPixelCountAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ArgbPixelImage(-1, 2, intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ArgbPixelImage(3, 3, IntArray(8)) }
    }

    private fun star(x: Int, y: Int, brightness: Int) = DetectedStar(
        x = x,
        y = y,
        brightness = brightness,
        localContrast = 80f,
        radius = 1f,
        confidence = 1f
    )

    private fun rgb(red: Int, green: Int, blue: Int) = argb(255, red, green, blue)
    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
