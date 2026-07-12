package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualStarAlignmentIntegrationTest {
    private val stars = listOf(8 to 8, 17 to 18, 29 to 10, 40 to 24, 52 to 15, 24 to 27)

    @Test
    fun staticForegroundMakesFullImageMatcherPreferZero() {
        val reference = scene(0, 0)
        val candidate = scene(4, 3)
        val imageShift = alignArgbImages(reference, candidate, safeMode = false, maxShiftPx = 8)
        assertEquals(0 to 0, imageShift.dx to imageShift.dy)
    }

    @Test
    fun starFirstManualAlignmentFindsMovingSky() {
        val result = alignManualImages(scene(0, 0), scene(4, 3), safeMode = true, maxShiftPx = 8)
        assertEquals("stars", result.method)
        assertEquals(4 to 3, result.shift.dx to result.shift.dy)
        assertTrue(result.matches >= 4)
        assertTrue(result.confidence >= 0.25f)
    }

    @Test
    fun applyingStarShiftAlignsTheStars() {
        val reference = scene(0, 0)
        val candidate = scene(4, 3)
        val result = alignManualImages(reference, candidate, safeMode = true, maxShiftPx = 8)
        val aligned = applyImageShift(candidate, result.shift.dx, result.shift.dy)
        stars.forEach { (x, y) ->
            assertEquals(reference.pixelAt(x, y), aligned.pixelAt(x, y))
        }
    }

    @Test
    fun croppedSceneStillUsesTheSkyShift() {
        val crop = NormalizedCropRect(0f, 0f, 1f, 0.65f)
        val reference = cropArgbImage(scene(0, 0), crop)
        val candidate = cropArgbImage(scene(4, 3), crop)
        val result = alignManualImages(reference, candidate, safeMode = true, maxShiftPx = 8)
        assertEquals("stars", result.method)
        assertEquals(4 to 3, result.shift.dx to result.shift.dy)
    }

    @Test
    fun safeModeDoesNotCallWeakThreeStarMatchSuccessful() {
        val reference = sparseStars(listOf(10 to 10, 25 to 16, 40 to 22), 0, 0)
        val candidate = sparseStars(listOf(10 to 10, 25 to 16, 40 to 22), 4, 2)
        val result = alignManualImages(reference, candidate, safeMode = true, maxShiftPx = 8)
        assertNotEquals("stars", result.method)
        assertTrue(result.matches < 4)
    }

    @Test
    fun validImageFallbackIsUsedWhenThereAreNoStars() {
        val reference = lowContrastTexture()
        val candidate = SyntheticImageTestData.translated(reference, -4, 3, fill = 20)
        val result = alignManualImages(reference, candidate, safeMode = true, maxShiftPx = 8)
        assertEquals("imageFallback", result.method)
        assertEquals(-4 to 3, result.shift.dx to result.shift.dy)
        assertEquals("tooFewStars", result.fallbackReason)
    }

    @Test
    fun unsafeFallbackIsRejectedForUniformFrames() {
        val image = SyntheticImageTestData.uniform(64, 64, 20)
        val result = alignManualImages(image, image, safeMode = true, maxShiftPx = 8)
        assertEquals("none", result.method)
        assertEquals(0 to 0, result.shift.dx to result.shift.dy)
    }

    @Test
    fun croppedSourceResolverPassesDerivedFramesToAlignment() {
        val originals = listOf(frame("a", false), frame("b", false))
        val cropRect = NormalizedCropRect(0f, 0f, 1f, 0.65f)
        val entries = originals.map { original ->
            val pixels = cropRect.toPixelRect(64, 64)
            CropManifestEntry(
                original.key,
                original.fileName,
                deterministicCropFileName(original.key),
                cropRect,
                pixels,
                64,
                64,
                pixels.width,
                pixels.height,
                1
            )
        }
        val records = entries.map { entry ->
            CroppedFrameRecord(
                entry,
                frame(entry.croppedFileName, true).copy(originalKey = entry.originalKey)
            )
        }
        val selected = resolveStackingSource(
            originals, records, FrameMarks(), false, ManualStackingSource.CROPPED
        ) as StackingSourceSelection.Valid
        assertTrue(selected.frames.all { it.filePath!!.contains("Cropped") })
        val images = mapOf(
            selected.frames[0].fileName to cropArgbImage(scene(0, 0), cropRect),
            selected.frames[1].fileName to cropArgbImage(scene(4, 3), cropRect)
        )
        val result = alignManualImages(
            images.getValue(selected.frames[0].fileName),
            images.getValue(selected.frames[1].fileName),
            true,
            8
        )
        assertEquals(4 to 3, result.shift.dx to result.shift.dy)
    }

    @Test
    fun alignmentDiagnosticsAreDeterministic() {
        val reference = scene(0, 0)
        val candidate = scene(4, 3)
        val first = alignManualImages(reference, candidate, true, 8)
        repeat(4) { assertEquals(first, alignManualImages(reference, candidate, true, 8)) }
        assertTrue(first.method in setOf("stars", "imageFallback", "none"))
    }

    @Test
    fun alignmentDoesNotModifyEitherInput() {
        val reference = scene(0, 0)
        val candidate = scene(4, 3)
        val referenceSnapshot = reference.pixels.copyOf()
        val candidateSnapshot = candidate.pixels.copyOf()
        alignManualImages(reference, candidate, true, 8)
        assertArrayEquals(referenceSnapshot, reference.pixels)
        assertArrayEquals(candidateSnapshot, candidate.pixels)
    }

    private fun scene(starDx: Int, starDy: Int): ArgbPixelImage {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height) { gray(12) }
        for (x in 0 until width) pixels[41 * width + x] = gray(150)
        for (y in 44 until height) {
            for (x in 0 until width) {
                val building = 100 + ((x / 5) * 19 + y * 7) % 120
                pixels[y * width + x] = gray(building)
            }
        }
        stars.forEachIndexed { index, (x, y) ->
            pixels[(y + starDy) * width + x + starDx] = gray(100 + index * 12)
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun sparseStars(points: List<Pair<Int, Int>>, dx: Int, dy: Int): ArgbPixelImage {
        val pixels = IntArray(64 * 48) { gray(15) }
        points.forEach { (x, y) -> pixels[(y + dy) * 64 + x + dx] = gray(150) }
        return ArgbPixelImage(64, 48, pixels)
    }

    private fun lowContrastTexture(): ArgbPixelImage {
        val pixels = IntArray(72 * 64)
        for (y in 0 until 64) for (x in 0 until 72) {
            pixels[y * 72 + x] = gray(20 + (x * 17 + y * 29 + x * y) % 41)
        }
        return ArgbPixelImage(72, 64, pixels)
    }

    private fun frame(name: String, cropped: Boolean) = SessionFrame(
        key = if (cropped) "Lights/Cropped/$name" else "Lights/JPEG/$name.jpg",
        fileName = if (cropped) name else "$name.jpg",
        category = if (cropped) SessionFrameCategory.CROPPED_JPEG else SessionFrameCategory.LIGHTS_JPEG,
        sizeBytes = 1,
        createdAtMillis = 1,
        displayPath = name,
        contentUri = null,
        filePath = if (cropped) "C:/Lights/Cropped/$name" else "C:/Lights/JPEG/$name.jpg"
    )

    private fun gray(value: Int) =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value
}
