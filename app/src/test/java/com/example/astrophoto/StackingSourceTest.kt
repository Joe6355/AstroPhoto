package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StackingSourceTest {
    private val originals = listOf(original("a"), original("b"), original("c"))

    @Test
    fun originalSourceReturnsOnlyOriginalFrames() {
        val result = select(ManualStackingSource.ORIGINAL) as StackingSourceSelection.Valid
        assertEquals(originals, result.frames)
    }

    @Test
    fun croppedSourceReturnsOnlyDerivedFiles() {
        val result = select(ManualStackingSource.CROPPED) as StackingSourceSelection.Valid
        assertTrue(result.frames.all { it.filePath?.contains("Cropped") == true })
        assertTrue(result.frames.none { it.filePath?.contains("Lights/JPEG") == true })
    }

    @Test
    fun croppedFramesKeepOriginalIdentityForMarks() {
        val result = select(ManualStackingSource.CROPPED) as StackingSourceSelection.Valid
        assertEquals(originals.map { it.key }, result.frames.map { it.key })
        assertEquals(originals.map { it.key }, result.frames.map { it.markKey })
    }

    @Test
    fun badAndAutoBadMarksAreInheritedFromOriginals() {
        val result = select(
            ManualStackingSource.CROPPED,
            marks = FrameMarks(bad = setOf(originals[0].key), autoBad = setOf(originals[1].key))
        ) as StackingSourceSelection.Invalid
        assertTrue(result.message.contains("Недостаточно"))
    }

    @Test
    fun favoritesFilterUsesOriginalKeys() {
        val result = select(
            ManualStackingSource.CROPPED,
            marks = FrameMarks(favorite = setOf(originals[0].key, originals[2].key)),
            favoritesOnly = true
        ) as StackingSourceSelection.Valid
        assertEquals(listOf(originals[0].key, originals[2].key), result.frames.map { it.key })
    }

    @Test
    fun missingCropIsReportedWithoutOriginalFallback() {
        val result = resolveStackingSource(
            originals,
            cropRecords().dropLast(1),
            FrameMarks(),
            false,
            ManualStackingSource.CROPPED
        ) as StackingSourceSelection.Valid
        assertEquals(1, result.missingCropCount)
        assertEquals(2, result.frames.size)
        assertTrue(result.frames.all { it.filePath!!.contains("Cropped") })
    }

    @Test
    fun insufficientCropsFailBeforeProcessing() {
        val result = resolveStackingSource(
            originals,
            cropRecords().take(1),
            FrameMarks(),
            false,
            ManualStackingSource.CROPPED
        )
        assertTrue(result is StackingSourceSelection.Invalid)
    }

    @Test
    fun mismatchedCropDimensionsFailBeforeProcessing() {
        val records = cropRecords().toMutableList()
        records[1] = records[1].copy(
            entry = records[1].entry.copy(
                pixelRect = PixelRect(0, 0, 70, 40),
                croppedWidth = 70
            )
        )
        val result = resolveStackingSource(
            originals, records, FrameMarks(), false, ManualStackingSource.CROPPED
        )
        assertTrue(result is StackingSourceSelection.Invalid)
    }

    @Test
    fun croppedOrderingFollowsOriginalOrderingNotManifestOrdering() {
        val result = resolveStackingSource(
            originals.reversed(),
            cropRecords(),
            FrameMarks(),
            false,
            ManualStackingSource.CROPPED
        ) as StackingSourceSelection.Valid
        assertEquals(originals.reversed().map { it.key }, result.frames.map { it.key })
    }

    @Test
    fun darkModeAcceptsOneCommonCropArea() {
        val entries = cropRecords().map { it.entry }
        assertEquals(entries.first(), commonDarkCrop(entries))
    }

    @Test
    fun darkModeRejectsDifferentCropRectangles() {
        val entries = cropRecords().map { it.entry }.toMutableList()
        entries[2] = entries[2].copy(
            normalizedRect = NormalizedCropRect(0.2f, 0f, 1f, 1f),
            pixelRect = PixelRect(20, 0, 100, 50),
            croppedHeight = 50
        )
        val error = assertThrows(IllegalArgumentException::class.java) { commonDarkCrop(entries) }
        assertTrue(error.message.orEmpty().contains("Apply to all"))
    }

    @Test
    fun sourceSelectionDoesNotMutateInputs() {
        val records = cropRecords()
        val originalSnapshot = originals.toList()
        val cropSnapshot = records.toList()
        select(ManualStackingSource.CROPPED)
        assertEquals(originalSnapshot, originals)
        assertEquals(cropSnapshot, records)
    }

    private fun select(
        source: ManualStackingSource,
        marks: FrameMarks = FrameMarks(),
        favoritesOnly: Boolean = false
    ) = resolveStackingSource(originals, cropRecords(), marks, favoritesOnly, source)

    private fun cropRecords() = originals.map { original ->
        val normalized = NormalizedCropRect(0.2f, 0.1f, 1f, 0.9f)
        val pixels = normalized.toPixelRect(100, 50)
        val entry = CropManifestEntry(
            original.key,
            original.fileName,
            deterministicCropFileName(original.key),
            normalized,
            pixels,
            100,
            50,
            pixels.width,
            pixels.height,
            1L
        )
        CroppedFrameRecord(
            entry,
            SessionFrame(
                "Lights/Cropped/${entry.croppedFileName}",
                entry.croppedFileName,
                SessionFrameCategory.CROPPED_JPEG,
                1,
                1,
                "crop",
                null,
                "C:/session/Lights/Cropped/${entry.croppedFileName}",
                original.key
            )
        )
    }

    private fun original(name: String) = SessionFrame(
        "Lights/JPEG/$name.jpg",
        "$name.jpg",
        SessionFrameCategory.LIGHTS_JPEG,
        1,
        1,
        "original",
        null,
        "C:/session/Lights/JPEG/$name.jpg"
    )
}
