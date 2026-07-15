package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar as V2Star
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage1Test {
    private val registrar = StarSimilarityRegistrar()
    private val referenceStars = listOf(
        star(12f, 14f), star(31f, 18f), star(50f, 12f), star(19f, 37f),
        star(43f, 45f), star(68f, 30f), star(75f, 56f), star(27f, 61f)
    )

    @Test
    fun selectorDoesNotKeepBlurredTrailedFirstFrame() {
        val blurred = analysis(
            id = "first",
            count = 7,
            contrast = 24f,
            width = 3.8f,
            ellipticity = 0.61f,
            noise = 10f,
            clipping = 2f,
            suitability = 0.35f
        )
        val sharp = analysis(
            id = "second",
            count = 15,
            contrast = 62f,
            width = 1.7f,
            ellipticity = 0.08f,
            noise = 3f,
            clipping = 0.1f,
            suitability = 0.91f
        )
        assertEquals("second", ReferenceFrameSelector().select(listOf(blurred, sharp)).analysis.id)
        assertEquals("second", ReferenceFrameSelector().select(listOf(sharp, blurred)).analysis.id)
    }

    @Test
    fun detectorRejectsIsolatedSingleChannelHotPixel() {
        val pixels = IntArray(80 * 64) { gray(12) }
        pixels[24 * 80 + 30] = rgb(255, 12, 12)
        val result = JpegStarDetector().detect(
            ArgbPixelImage(80, 64, pixels),
            SkyMask.full(80, 64)
        )
        assertTrue(result.stars.none { kotlin.math.abs(it.x - 30f) < 2f && kotlin.math.abs(it.y - 24f) < 2f })
    }

    @Test
    fun detectorReturnsSubpixelCentroid() {
        val expectedX = 35.35f
        val expectedY = 27.65f
        val image = gaussianImage(80, 64, listOf(expectedX to expectedY))
        val detected = JpegStarDetector().detect(image, SkyMask.full(80, 64)).stars.single()
        assertEquals(expectedX.toDouble(), detected.x.toDouble(), 0.20)
        assertEquals(expectedY.toDouble(), detected.y.toDouble(), 0.20)
    }

    @Test
    fun skyMaskExcludesSyntheticBuildingAndWindowFrame() {
        val image = skyAndBuildingScene()
        val result = SkyMaskEstimator().estimate(image)
        assertFalse(result.mask.contains(60, 82))
        assertFalse(result.mask.contains(3, 30))
    }

    @Test
    fun skyMaskRetainsSyntheticStarField() {
        val result = SkyMaskEstimator().estimate(skyAndBuildingScene())
        assertTrue(result.mask.contains(60, 28))
        assertTrue(result.mask.retainedFraction() > 0.10f)
    }

    @Test
    fun registrarRecoversKnownTranslation() {
        val candidate = transform(referenceStars, dx = 5f, dy = -3f)
        val result = registrar.register(referenceStars, candidate, 100, 80)
        assertTrue(result.toString(), result.isReliable)
        assertEquals(5.0, result.dx.toDouble(), 0.05)
        assertEquals(-3.0, result.dy.toDouble(), 0.05)
    }

    @Test
    fun registrarRecoversSmallRotation() {
        val rotation = (2.0 * PI / 180.0).toFloat()
        val candidate = transform(referenceStars, dx = 3f, dy = -2f, rotation = rotation)
        val result = registrar.register(referenceStars, candidate, 100, 80)
        assertTrue(result.toString(), result.isReliable)
        assertEquals(rotation.toDouble(), result.rotationRadians.toDouble(), 0.002)
    }

    @Test
    fun registrarToleratesFalseCandidates() {
        val candidate = transform(referenceStars, dx = -4f, dy = 3f) + listOf(
            star(5f, 70f, 0.7f), star(90f, 7f, 0.65f), star(88f, 74f, 0.6f)
        )
        val result = registrar.register(referenceStars, candidate, 100, 80)
        assertTrue(result.toString(), result.isReliable)
        assertTrue(result.inlierStars >= referenceStars.size)
        assertEquals(-4.0, result.dx.toDouble(), 0.05)
    }

    @Test
    fun registrarRejectsInsufficientMatches() {
        val result = registrar.register(referenceStars, referenceStars.take(3), 100, 80)
        assertFalse(result.isReliable)
        assertTrue(result.rejectionReason.orEmpty().contains("Insufficient"))
    }

    @Test
    fun registrarRejectsImplausiblyLargeTransform() {
        val result = registrar.register(
            referenceStars,
            transform(referenceStars, dx = 80f, dy = 0f),
            100,
            80
        )
        assertFalse(result.isReliable)
    }

    @Test
    fun failedRegistrationIsNotAcceptedAsZeroShift() {
        val failed = registrar.register(referenceStars, referenceStars.take(2), 100, 80)
        assertEquals(0f, failed.dx)
        assertEquals(0f, failed.dy)
        assertFalse(failed.isReliable)
    }

    @Test
    fun genuineNearZeroTransformNeedsAndUsesValidMatches() {
        val valid = registrar.register(referenceStars, transform(referenceStars, 0.04f, -0.03f), 100, 80)
        val invalid = registrar.register(referenceStars, referenceStars.take(2), 100, 80)
        assertTrue(valid.toString(), valid.isReliable)
        assertTrue(valid.inlierStars >= StarSimilarityRegistrar.MIN_INLIER_STARS)
        assertFalse(invalid.isReliable)
    }

    @Test
    fun presetIdentifiersRemainCompatible() {
        assertEquals(
            listOf("NORMAL", "DEEP_SKY", "DEEP_SKY_ALIGNED", "URBAN_SKY", "URBAN_SKY_STRONG", "MAX_STARS"),
            AstroProcessingProfile.entries.map { it.name }
        )
    }

    @Test
    fun jpegSessionFrameModelRemainsCompatible() {
        val legacyCompatibleFrame = SessionFrame(
            "Lights/JPEG/a.jpg", "a.jpg", SessionFrameCategory.LIGHTS_JPEG,
            1L, 2L, "Lights/JPEG/a.jpg", null, "C:/session/Lights/JPEG/a.jpg"
        )
        assertEquals("Lights/JPEG/a.jpg", legacyCompatibleFrame.markKey)
        assertTrue(legacyCompatibleFrame.isJpeg)
    }

    @Test
    fun rawIdentifiersAndEligibleFrameBehaviorRemainUnchanged() {
        assertEquals(
            listOf("LIGHTS_JPEG", "CROPPED_JPEG", "LIGHTS_RAW", "DARKS_JPEG", "DARKS_RAW"),
            SessionFrameCategory.entries.map { it.name }
        )
        val raw = SessionFrame(
            "Lights/RAW/a.dng", "a.dng", SessionFrameCategory.LIGHTS_RAW,
            1L, 2L, "Lights/RAW/a.dng", null, "C:/session/Lights/RAW/a.dng"
        )
        assertTrue(selectEligibleLightFrames(listOf(raw), FrameMarks(), false).isEmpty())
    }

    private fun analysis(
        id: String,
        count: Int,
        contrast: Float,
        width: Float,
        ellipticity: Float,
        noise: Float,
        clipping: Float,
        suitability: Float
    ) = FrameAnalysis(
        id = id,
        fileName = "$id.jpg",
        width = 100,
        height = 80,
        stars = List(count) { index -> star(10f + index * 3f, 12f + index * 2f) },
        reliableStarCount = count,
        medianStarContrast = contrast,
        medianStarWidth = width,
        medianStarEllipticity = ellipticity,
        backgroundNoise = noise,
        clippingPercent = clipping,
        exposureSuitability = 0.9f,
        decodeValid = true,
        alignmentSuitability = suitability,
        skyMaskConfidence = 0.8f,
        skyMaskUsedFallback = false
    )

    private fun transform(
        source: List<V2Star>,
        dx: Float,
        dy: Float,
        rotation: Float = 0f,
        scale: Float = 1f
    ): List<V2Star> = source.map { sourceStar ->
        val x = scale * (cos(rotation) * sourceStar.x - sin(rotation) * sourceStar.y) + dx
        val y = scale * (sin(rotation) * sourceStar.x + cos(rotation) * sourceStar.y) + dy
        sourceStar.copy(x = x, y = y)
    }

    private fun star(x: Float, y: Float, confidence: Float = 0.95f) = V2Star(
        x = x,
        y = y,
        flux = 500f,
        localBackground = 12f,
        localContrast = 90f,
        width = 1.8f,
        ellipticity = 0.08f,
        confidence = confidence
    )

    private fun gaussianImage(
        width: Int,
        height: Int,
        stars: List<Pair<Float, Float>>
    ): ArgbPixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 12.0
            stars.forEach { (starX, starY) ->
                val dx = x - starX
                val dy = y - starY
                value += 190.0 * exp(-(dx * dx + dy * dy) / 2.0)
            }
            pixels[y * width + x] = gray(value.toInt().coerceIn(0, 245))
        }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun skyAndBuildingScene(): ArgbPixelImage {
        val width = 120
        val height = 96
        val image = gaussianImage(
            width,
            height,
            listOf(20.2f to 18.4f, 43.5f to 34.1f, 60.3f to 28.2f, 82.7f to 16.8f, 99.1f to 39.3f)
        )
        val pixels = image.pixels.copyOf()
        for (y in 66 until height) for (x in 0 until width) {
            val value = 55 + (x * 31 + y * 17 + x * y) % 170
            pixels[y * width + x] = gray(value)
        }
        for (y in 0 until height) {
            pixels[y * width + 2] = gray(245)
            pixels[y * width + width - 3] = gray(245)
        }
        for (y in 72..88) for (x in 48..72) pixels[y * width + x] = gray(238)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun gray(value: Int) = rgb(value, value, value)

    private fun rgb(red: Int, green: Int, blue: Int) =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
