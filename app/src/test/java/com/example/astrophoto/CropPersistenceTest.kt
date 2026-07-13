package com.example.astrophoto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CropPersistenceTest {
    @Test
    fun saveFailureDoesNotUpdateManifest() {
        var manifestUpdated = false
        assertThrows(IllegalStateException::class.java) {
            persistCropAfterVerifiedWrite(
                writeImage = { error("image write failed") },
                updateManifest = { _: String -> manifestUpdated = true },
                commitImage = {},
                rollbackImage = {}
            )
        }
        assertFalse(manifestUpdated)
    }

    @Test
    fun manifestFailureRollsBackVerifiedImage() {
        var committed = false
        var rolledBack = false
        assertThrows(IllegalStateException::class.java) {
            persistCropAfterVerifiedWrite(
                writeImage = { "verified.jpg" },
                updateManifest = { error("manifest failed") },
                commitImage = { committed = true },
                rollbackImage = { rolledBack = true }
            )
        }
        assertFalse(committed)
        assertTrue(rolledBack)
    }

    @Test
    fun successfulSaveUpdatesManifestBeforeCommittingReplacement() {
        val events = mutableListOf<String>()
        val result = persistCropAfterVerifiedWrite(
            writeImage = { events += "image"; "crop.jpg" },
            updateManifest = { events += "manifest:$it" },
            commitImage = { events += "commit:$it" },
            rollbackImage = { events += "rollback:$it" }
        )
        assertEquals("crop.jpg", result)
        assertEquals(listOf("image", "manifest:crop.jpg", "commit:crop.jpg"), events)
    }

    @Test
    fun batchCropProcessesOnlyOriginalLightJpegsAndReportsFailures() = runBlocking {
        val visited = mutableListOf<String>()
        val result = processCropBatch(
            listOf(
                frame("a.jpg", SessionFrameCategory.LIGHTS_JPEG),
                frame("derived.jpg", SessionFrameCategory.CROPPED_JPEG),
                frame("bad.jpg", SessionFrameCategory.LIGHTS_JPEG)
            )
        ) { frame ->
            visited += frame.fileName
            if (frame.fileName == "bad.jpg") error("decode failed")
        }
        assertEquals(listOf("a.jpg", "bad.jpg"), visited)
        assertEquals(1, result.processed)
        assertEquals(1, result.skipped)
        assertEquals(1, result.failed)
        assertEquals("bad.jpg", result.failures.single().fileName)
    }

    @Test
    fun refreshedFramesExposeNewCropAndPreserveOriginalMarkKey() {
        val original = frame("a.jpg", SessionFrameCategory.LIGHTS_JPEG)
        val cropped = frame("crop_a.jpg", SessionFrameCategory.CROPPED_JPEG)
        val rect = NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f)
        val entry = CropManifestEntry(
            originalKey = original.key,
            originalFileName = original.fileName,
            croppedFileName = cropped.fileName,
            normalizedRect = rect,
            pixelRect = rect.toPixelRect(100, 100),
            originalWidth = 100,
            originalHeight = 100,
            croppedWidth = 80,
            croppedHeight = 80,
            updatedAtMillis = 1
        )
        val record = resolveCroppedFrameRecords(
            CropManifest(listOf(entry)),
            listOf(original, cropped)
        ).single()
        assertEquals(cropped.fileName, record.frame.fileName)
        assertEquals(original.key, record.frame.markKey)
    }

    @Test
    fun cropDestinationStaysInsideSessionPicturesFolder() {
        assertEquals(
            "Pictures/AstroPhoto/Session_1/Lights/Cropped/",
            cropRelativePath("Session_1")
        )
    }

    private fun frame(name: String, category: SessionFrameCategory) = SessionFrame(
        key = "${category.name}/$name",
        fileName = name,
        category = category,
        sizeBytes = 10,
        createdAtMillis = 1,
        displayPath = name,
        contentUri = null,
        filePath = "C:/$name"
    )
}
