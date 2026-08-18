package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ManualControlsAndEditorPresetsTest {
    @Test
    fun exposureSliderCoversFullCameraRangeAndRoundTrips() {
        val range = 1_000_000L..30_000_000_000L

        assertEquals(range.first, exposureFromSliderFraction(0f, range))
        assertEquals(range.last, exposureFromSliderFraction(1f, range))

        val exposure = 7_500_000_000L
        val restored = exposureFromSliderFraction(
            exposureSliderFraction(exposure, range),
            range
        )
        assertTrue(abs(restored - exposure) < exposure / 10_000L)
    }

    @Test
    fun exposureSliderIsLogarithmicAndMonotonic() {
        val range = 1_000_000L..30_000_000_000L
        val quarter = exposureFromSliderFraction(0.25f, range)
        val middle = exposureFromSliderFraction(0.5f, range)
        val threeQuarter = exposureFromSliderFraction(0.75f, range)

        assertTrue(quarter in (range.first + 1)..<middle)
        assertTrue(middle < threeQuarter)
        assertTrue(threeQuarter < range.last)
    }

    @Test
    fun seriesOffersRequestedLargeFrameCounts() {
        assertTrue(SERIES_FRAME_COUNTS.containsAll(listOf(40, 50, 100)))
        assertEquals(SERIES_FRAME_COUNTS.sorted(), SERIES_FRAME_COUNTS)
    }

    @Test
    fun postRawBoostExtendsEffectiveIsoAndMapsToValidCameraRequest() {
        val sensorRange = 50..3200
        val boostRange = 100..3199

        assertEquals(50..102368, effectiveIsoRange(sensorRange, boostRange))
        assertEquals(
            CameraIsoRequest(sensorIso = 3200, postRawBoostPercent = 200),
            cameraIsoRequest(6400, sensorRange, boostRange)
        )
        assertEquals(
            CameraIsoRequest(sensorIso = 800, postRawBoostPercent = 100),
            cameraIsoRequest(800, sensorRange, boostRange)
        )
    }

    @Test
    fun extendedIsoSliderIsLogarithmicAndOffersHighPresets() {
        val range = 50..102368
        val restored = isoFromSliderFraction(isoSliderFraction(6400, range), range)

        assertTrue(abs(restored - 6400) <= 1)
        assertTrue(cameraIsoPresets(range).containsAll(listOf(6400, 12800, 102368)))
    }

    @Test
    fun editorAcceptsStackPngAndCameraJpeg() {
        assertTrue(isSupportedEditorImage("ExperimentalStars_1.png"))
        assertTrue(isSupportedEditorImage("AstroSeries_1.jpg"))
        assertTrue(isSupportedEditorImage("AstroSeries_1.JPEG"))
        assertFalse(isSupportedEditorImage("AstroSeries_1.dng"))
    }

    @Test
    fun completedResultCanBeSelectedForImmediateEditorOpen() {
        val result = ProcessedResult(
            key = "result",
            fileName = "RecoveredStars_1.png",
            type = ProcessedResultType.RECOVERED_STARS,
            createdAtMillis = 1L,
            sizeBytes = 2L,
            displayPath = "Processed/RecoveredStars_1.png",
            contentUri = null,
            filePath = "C:/result.png"
        )

        assertEquals(
            result,
            findProcessedResultForEditor(listOf(result), "recoveredstars_1.PNG")
        )
    }

    @Test
    fun completedResultEditorWaitsUntilProcessingCoroutineStops() {
        assertFalse(
            shouldOpenCompletedResultEditor(
                pendingEditorFileName = "RecoveredStars_1.png",
                stackingInProgress = true,
                showingProcessing = true
            )
        )
        assertFalse(
            shouldOpenCompletedResultEditor(
                pendingEditorFileName = null,
                stackingInProgress = false,
                showingProcessing = true
            )
        )
        assertTrue(
            shouldOpenCompletedResultEditor(
                pendingEditorFileName = "RecoveredStars_1.png",
                stackingInProgress = false,
                showingProcessing = true
            )
        )
    }

    @Test
    fun acceptedEnhancedResultIsOpenedWhileSafeBaseRemainsAvailable() {
        val base = SavedProcessedImage(
            fileName = "RecoveredStars_1.png",
            displayPath = "Processed/RecoveredStars_1.png",
            contentUri = null,
            filePath = "C:/RecoveredStars_1.png"
        )
        val enhanced = SavedProcessedImage(
            fileName = "Enhanced_1.png",
            displayPath = "Processed/Enhanced_1.png",
            contentUri = null,
            filePath = "C:/Enhanced_1.png"
        )

        val selected = selectUserFacingProfileOutput(base, enhanced)

        assertEquals(enhanced, selected.first)
        assertEquals(base.fileName, selected.second)
        assertEquals(base to null, selectUserFacingProfileOutput(base, null))
    }

    @Test
    fun visibleStarPresetsIncludeRealContrastAndSharpness() {
        val visible = EDITOR_PRESETS.first { it.name == "Звёзды — заметно" }.adjustments
        val maximum = EDITOR_PRESETS.first { it.name == "Звёзды — максимум" }.adjustments

        assertTrue(visible.contrast >= 30f)
        assertTrue(visible.sharpness >= 35f)
        assertTrue(maximum.contrast > visible.contrast)
        assertTrue(maximum.sharpness > visible.sharpness)
    }

    @Test
    fun sharpnessRaisesIsolatedDetailButKeepsFlatAreasStable() {
        assertEquals(50, editorSharpenedChannel(50, 50, 50, 50, 50, 1f))
        assertEquals(50, editorSharpenedChannel(50, 49, 49, 49, 49, 1f))
        assertTrue(editorSharpenedChannel(100, 50, 50, 50, 50, 0.8f) > 100)
        assertEquals(255, editorSharpenedChannel(250, 0, 0, 0, 0, 1.6f))
    }
}
