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
    fun editorAcceptsStackPngAndCameraJpeg() {
        assertTrue(isSupportedEditorImage("ExperimentalStars_1.png"))
        assertTrue(isSupportedEditorImage("AstroSeries_1.jpg"))
        assertTrue(isSupportedEditorImage("AstroSeries_1.JPEG"))
        assertFalse(isSupportedEditorImage("AstroSeries_1.dng"))
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
