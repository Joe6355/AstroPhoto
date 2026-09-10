package com.joe6355.astrophoto

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
        assertTrue(
            SERIES_FRAME_COUNTS.containsAll(
                listOf(1, 40, 50, 100, 150, 200, 300, 500)
            )
        )
        assertEquals(SERIES_FRAME_COUNTS.sorted(), SERIES_FRAME_COUNTS)
    }

    @Test
    fun darkFramesCanBeDisabledWithZero() {
        assertEquals(listOf(0, 3, 5, 10, 20), CameraSettingsStore.DARK_FRAME_COUNT_VALUES)
    }

    @Test
    fun helpAndOnboardingDescribeCurrentCaptureOptions() {
        val help = ASTROPHOTO_HELP_SECTIONS.joinToString(" ") { it.text }
        val onboarding = ASTROPHOTO_ONBOARDING_PAGES.joinToString(" ") {
            "${it.first} ${it.second}"
        }

        assertTrue(help.contains("500"))
        assertTrue(help.contains("20 и 30 кадр/с"))
        assertTrue(help.contains("Значение 0 отключает"))
        assertTrue(onboarding.contains("до 500 кадров"))
        assertTrue(onboarding.contains("до 30 кадр/с"))
        assertTrue(help.contains("FWHM"))
        assertFalse(onboarding.contains("пробн", ignoreCase = true))
    }

    @Test
    fun thirtySecondIntegrationAdaptsToNativeCameraLimit() {
        val galaxyLike = checkNotNull(
            astroIntegrationPlan(100_000L..10_000_000_000L)
        )
        val pixelLike = checkNotNull(
            astroIntegrationPlan(100_000L..8_310_000_000L)
        )

        assertEquals(3, galaxyLike.frameCount)
        assertEquals(30_000_000_000L, galaxyLike.totalIntegrationNs)
        assertEquals(4, pixelLike.frameCount)
        assertTrue(pixelLike.totalIntegrationNs >= MINIMUM_ASTRO_INTEGRATION_NS)
        assertTrue(pixelLike.reachesTarget)
    }

    @Test
    fun captureResultComparisonDetectsIgnoredIso() {
        assertFalse(manualCaptureResultMatchesRequest(ManualCaptureResult(16_666_667L, 20_000_000L, 400, 400)))
        assertFalse(manualCaptureResultMatchesRequest(ManualCaptureResult(20_000_000L, 16_666_667L, 400, 400)))
        assertFalse(manualCaptureResultMatchesRequest(ManualCaptureResult(20_000_000L, null, 400, 400)))
        assertFalse(manualCaptureResultMatchesRequest(ManualCaptureResult(20_000_000L, 0L, 400, 400)))
        assertFalse(manualCaptureResultMatchesRequest(ManualCaptureResult(20_000_000L, 20_000_000L, 400, 0)))
        assertTrue(
            manualCaptureResultMatchesRequest(
                ManualCaptureResult(10_000_000_000L, 9_900_000_000L, 800, 800)
            )
        )
        assertFalse(
            manualCaptureResultMatchesRequest(
                ManualCaptureResult(10_000_000_000L, 9_900_000_000L, 800, 100)
            )
        )
    }

    @Test
    fun cameraUiAdaptsRecommendationsToJpegSeries() {
        val adapted = ExposureRecommendation(
            iso = 800,
            exposureTimeNs = 10_000_000_000L,
            focusMode = CameraFocusMode.INFINITY,
            format = AssistantCaptureFormat.RAW,
            frameCount = 1,
            timerSeconds = 5,
            explanation = "Для звёзд лучше RAW/DNG"
        ).forJpegOnlyUi()

        assertEquals(AssistantCaptureFormat.JPEG, adapted.format)
        assertEquals(1, adapted.frameCount)
        assertFalse(adapted.explanation.contains("RAW/DNG"))
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
                stackingInProgress = true
            )
        )
        assertFalse(
            shouldOpenCompletedResultEditor(
                pendingEditorFileName = null,
                stackingInProgress = false
            )
        )
        assertTrue(
            shouldOpenCompletedResultEditor(
                pendingEditorFileName = "RecoveredStars_1.png",
                stackingInProgress = false
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
