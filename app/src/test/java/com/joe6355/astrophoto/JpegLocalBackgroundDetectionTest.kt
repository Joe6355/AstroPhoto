package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class JpegLocalBackgroundDetectionTest {
    private val detector = JpegStarDetector()
    private val points = listOf(150 to 24, 180 to 48, 215 to 80, 235 to 110)
    private val mask = SkyMask.full(256, 128)

    @Test fun brightCloudDoesNotHideStarsInAnotherPartOfTheSky() {
        val image = render(cloud = true, amplitude = 24.0)
        assertTrue(detector.detect(image, mask).stars.isEmpty())
        val found = detector.detect(image, mask, useLocalBackground = true).stars
        assertEquals(4, found.size)
        points.forEach { (x, y) -> assertTrue(found.any { hypot(it.x - x, it.y - y) < 0.15f }) }
    }

    @Test fun ordinaryUniformSkyKeepsExactlyTheSameCatalog() {
        val image = render(cloud = false, amplitude = 90.0)
        val global = detector.detect(image, mask)
        val local = detector.detect(image, mask, useLocalBackground = true)
        assertEquals(4, global.stars.size)
        assertEquals(global, local)
    }

    @Test fun frameAnalysisUsesLocalDetectionAndKeepsGlobalNoiseStatistics() {
        val image = render(cloud = true, amplitude = 24.0)
        val sky = SkyMaskResult(mask, 1f, false)
        assertEquals(0, JpegFrameAnalyzer().analyze("frame", "frame.jpg", image, sky).reliableStarCount)
        val analysis = JpegFrameAnalyzer().analyze("frame", "frame.jpg", image, sky, useLocalBackground = true)
        assertEquals(4, analysis.reliableStarCount)
        assertEquals(detector.detect(image, mask).noise, analysis.backgroundNoise, 0f)
    }

    @Test fun excludedPixelsCannotEnterTheAdaptiveCatalog() {
        val onlyCloud = SkyMask(256, 128, BooleanArray(256 * 128) { it % 256 < 128 })
        assertTrue(detector.detect(render(true, 24.0), onlyCloud, useLocalBackground = true).stars.isEmpty())
        assertTrue(detector.detect(render(true, 24.0), SkyMask.empty(256, 128), useLocalBackground = true).stars.isEmpty())
    }

    @Test fun hotPixelsAndBroadCloudFeaturesStillFailTheShapeChecks() {
        val image = ArgbPixelImage(256, 128, IntArray(256 * 128) { index ->
            val x = index % 256
            val y = index / 256
            val broad = 40 * exp(-((x - 200) * (x - 200) + (y - 60) * (y - 60)) / (2.0 * 12 * 12))
            var value = (12 + broad).roundToInt()
            if (x == 150 && y == 24 || x == 180 && y == 100) value = 180
            grey(value)
        })
        assertTrue(detector.detect(image, mask, useLocalBackground = true).stars.isEmpty())
    }

    @Test fun adaptiveDetectionHonorsTheExistingCatalogLimit() {
        assertEquals(2, detector.detect(render(true, 24.0), mask, maxStars = 2, useLocalBackground = true).stars.size)
    }

    private fun render(cloud: Boolean, amplitude: Double): ArgbPixelImage =
        ArgbPixelImage(256, 128, IntArray(256 * 128) { index ->
            val x = index % 256
            val y = index / 256
            val background = if (cloud && x < 128) 100 else 12
            val signal = points.sumOf { (cx, cy) ->
                amplitude * exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * 0.9 * 0.9))
            }
            grey((background + signal).roundToInt().coerceIn(0, 255))
        })

    private fun grey(value: Int): Int = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
}
