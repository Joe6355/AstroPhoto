package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathAndManifestTest {
    @Test
    fun normalizedRectangleMapsToExclusivePixelBounds() {
        assertEquals(
            PixelRect(20, 10, 80, 40),
            NormalizedCropRect(0.2f, 0.2f, 0.8f, 0.8f).toPixelRect(100, 50)
        )
    }

    @Test
    fun coordinatesAreClampedToImageBounds() {
        assertEquals(
            PixelRect(0, 0, 100, 50),
            NormalizedCropRect(-1f, -2f, 3f, 4f).toPixelRect(100, 50)
        )
    }

    @Test
    fun zeroAndInvertedRectanglesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCropRect(0.5f, 0.2f, 0.5f, 0.8f).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCropRect(0.8f, 0.2f, 0.3f, 0.8f).validated()
        }
    }

    @Test
    fun nonFiniteCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCropRect(Float.NaN, 0f, 1f, 1f).validated()
        }
    }

    @Test
    fun orientationAwareDimensionsSwapForQuarterTurns() {
        assertEquals(300 to 400, orientedDimensions(400, 300, JpegOrientation.ROTATE_90))
        assertEquals(300 to 400, orientedDimensions(400, 300, JpegOrientation.TRANSPOSE))
        assertEquals(400 to 300, orientedDimensions(400, 300, JpegOrientation.ROTATE_180))
    }

    @Test
    fun sameNormalizedBatchRectangleMapsPerOriginalDimensions() {
        val crop = NormalizedCropRect(0.1f, 0.2f, 0.9f, 0.8f)
        assertEquals(PixelRect(10, 10, 90, 40), crop.toPixelRect(100, 50))
        assertEquals(PixelRect(20, 20, 180, 80), crop.toPixelRect(200, 100))
    }

    @Test
    fun manifestRoundTripPreservesEveryField() {
        val manifest = CropManifest(listOf(entry("Lights/JPEG/a.jpg", 10L)))
        assertEquals(manifest, CropManifest.decode(manifest.encode()))
    }

    @Test
    fun deterministicNameUsesOriginalIdentity() {
        val first = deterministicCropFileName("Lights/JPEG/a.jpg")
        assertEquals(first, deterministicCropFileName("Lights/JPEG/a.jpg"))
        assertNotEquals(first, deterministicCropFileName("Lights/JPEG/b.jpg"))
        assertTrue(first.startsWith("crop_") && first.endsWith(".jpg"))
    }

    @Test
    fun recropReplacesOnlySameOriginalEntry() {
        val a = entry("Lights/JPEG/a.jpg", 10L)
        val b = entry("Lights/JPEG/b.jpg", 20L)
        val replacement = a.copy(updatedAtMillis = 30L)
        val manifest = CropManifest(listOf(a, b)).replace(replacement)
        assertEquals(2, manifest.entries.size)
        assertEquals(30L, manifest.find(a.originalKey)?.updatedAtMillis)
        assertEquals(b, manifest.find(b.originalKey))
    }

    @Test
    fun deletingManifestEntryDoesNotAffectOtherOriginals() {
        val a = entry("Lights/JPEG/a.jpg", 10L)
        val b = entry("Lights/JPEG/b.jpg", 20L)
        assertEquals(listOf(b), CropManifest(listOf(a, b)).remove(a.originalKey).entries)
    }

    @Test
    fun pureCropDoesNotModifyOriginalPixels() {
        val original = SyntheticImageTestData.texture(20, 10)
        val snapshot = original.pixels.copyOf()
        val cropped = cropArgbImage(original, NormalizedCropRect(0.25f, 0.2f, 0.75f, 0.8f))
        assertEquals(10, cropped.width)
        assertEquals(6, cropped.height)
        assertArrayEquals(snapshot, original.pixels)
    }

    @Test
    fun invalidManifestVersionAndShapeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CropManifest.decode("wrong\n") }
        assertThrows(IllegalArgumentException::class.java) {
            CropManifest.decode("ASTROPHOTO_CROPS_V1\nbroken\n")
        }
    }

    private fun entry(key: String, updated: Long): CropManifestEntry {
        val rect = NormalizedCropRect(0.1f, 0.2f, 0.9f, 0.8f)
        val pixels = rect.toPixelRect(100, 50)
        return CropManifestEntry(
            key,
            key.substringAfterLast('/'),
            deterministicCropFileName(key),
            rect,
            pixels,
            100,
            50,
            pixels.width,
            pixels.height,
            updated
        )
    }
}
