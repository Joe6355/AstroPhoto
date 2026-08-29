package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTimelapseExporterTest {
    @Test
    fun exporterSupportsRequestedHighFrameRates() {
        assertEquals(setOf(2, 5, 10, 20, 30), SessionTimelapseExporter.SUPPORTED_FPS)
    }

    @Test
    fun eligibleFramesKeepOnlyOrderedGoodLightsJpeg() {
        val frames = listOf(
            frame("late.jpg", SessionFrameCategory.LIGHTS_JPEG, 30),
            frame("dark.jpg", SessionFrameCategory.DARKS_JPEG, 5),
            frame("bad.jpg", SessionFrameCategory.LIGHTS_JPEG, 20),
            frame("early.jpg", SessionFrameCategory.LIGHTS_JPEG, 10),
            frame("raw.dng", SessionFrameCategory.LIGHTS_RAW, 1),
            frame("crop.jpg", SessionFrameCategory.CROPPED_JPEG, 40)
        )

        val result = eligibleTimelapseFrames(frames, FrameMarks(bad = setOf("bad.jpg")))

        assertEquals(listOf("early.jpg", "late.jpg"), result.map { it.fileName })
    }

    @Test
    fun landscapeOutputFitsFullHdAndHasEvenDimensions() {
        assertEquals(1620 to 1080, timelapseOutputSize(6000, 4000))
    }

    @Test
    fun portraitOutputFitsFullHdAndHasEvenDimensions() {
        assertEquals(1080 to 1620, timelapseOutputSize(4000, 6000))
    }

    @Test
    fun smallFramesAreNotUpscaled() {
        assertEquals(800 to 600, timelapseOutputSize(800, 600))
    }

    private fun frame(
        name: String,
        category: SessionFrameCategory,
        createdAtMillis: Long
    ) = SessionFrame(
        key = name,
        fileName = name,
        category = category,
        sizeBytes = 1,
        createdAtMillis = createdAtMillis,
        displayPath = name,
        contentUri = null,
        filePath = name
    )
}
