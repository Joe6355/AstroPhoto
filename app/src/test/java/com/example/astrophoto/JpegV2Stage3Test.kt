package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.masking.ForegroundProtectionMask
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage3Test {
    @Test fun refinedMaskRetainsSyntheticSky() {
        val refined = refine(skyBuildingScene())
        assertTrue(refined.binaryMask.contains(20, 12))
        assertTrue(refined.diagnostics.refinedSkyRatio > 0.25f)
    }

    @Test fun refinedMaskExcludesSyntheticBuilding() {
        val refined = refine(skyBuildingScene())
        assertFalse(refined.binaryMask.contains(20, 52))
    }

    @Test fun refinedMaskExcludesBorderWindowFrame() {
        val image = skyBuildingScene().copyImage().also { scene ->
            for (y in scene.pixels.indices step scene.width) {
                for (x in 2..4) scene.pixels[y + x] = gray(205)
            }
        }
        val refined = refine(image)
        assertFalse(refined.binaryMask.contains(3, 15))
        assertTrue(refined.diagnostics.borderStructurePixels > 0)
    }

    @Test fun refinedMaskExcludesBrightWindow() {
        val image = uniformImage(80, 60, 65)
        for (y in 16..23) for (x in 32..43) image.pixels[y * image.width + x] = gray(252)
        val refined = refine(image)
        assertFalse(refined.binaryMask.contains(36, 20))
    }

    @Test fun smoothDarkWallIsNotSky() {
        val image = ArgbPixelImage(80, 60, IntArray(80 * 60) { index ->
            gray(if (index / 80 < 30) 38 else 7)
        })
        val refined = refine(image)
        assertTrue(refined.binaryMask.contains(40, 10))
        assertFalse(refined.binaryMask.contains(40, 48))
    }

    @Test fun disconnectedUpperDarkBuildingsAreNotSky() {
        val width = 120
        val height = 80
        val image = ArgbPixelImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            gray(if (x in 30 until 92) 42 else 7)
        })
        for (y in 12..18) for (x in 8..15) image.pixels[y * width + x] = gray(245)
        for (y in 28..34) for (x in 103..110) image.pixels[y * width + x] = gray(235)

        val refined = refine(image)

        assertTrue(refined.binaryMask.contains(60, 20))
        assertFalse(refined.binaryMask.contains(12, 45))
        assertFalse(refined.binaryMask.contains(106, 45))
    }

    @Test fun subtleColorBoundarySeparatesDarkForegroundFromSky() {
        val width = 120
        val height = 80
        val image = ArgbPixelImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            if (x in 30 until 92) rgb(12, 15, 18) else rgb(8, 9, 6)
        })

        val refined = refine(image)

        assertTrue(refined.binaryMask.contains(60, 20))
        assertFalse(refined.binaryMask.contains(12, 20))
        assertFalse(refined.binaryMask.contains(106, 20))
    }

    @Test fun vignettedPrimarySkyRemainsContinuousToTheTop() {
        val width = 120
        val height = 80
        val image = ArgbPixelImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                x !in 30 until 92 -> rgb(5, 5, 3)
                y < 20 -> rgb(6 + y / 4, 7 + y / 4, 8 + y / 4)
                else -> rgb(13, 15, 18)
            }
        })

        val refined = refine(image)

        assertTrue(refined.binaryMask.contains(60, 4))
        assertTrue(refined.binaryMask.contains(60, 40))
        assertFalse(refined.binaryMask.contains(12, 4))
    }

    @Test fun disconnectedSkyComponentWithConfirmedStarIsRetained() {
        val width = 120
        val height = 80
        val image = ArgbPixelImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            gray(if (x in 30 until 92) 42 else 7)
        })
        val refined = SkyMaskRefiner().refine(
            initialMask = SkyMask.full(width, height),
            reference = image,
            stars = listOf(star(105f, 30f)),
            initialConfidence = 1f,
            initialUsedFallback = false,
            registrationConfidence = 1f
        )

        assertTrue(refined.binaryMask.contains(60, 20))
        assertTrue(refined.binaryMask.contains(105, 30))
        assertTrue(refined.binaryMask.contains(94, 30))
        assertFalse(refined.binaryMask.contains(12, 45))
    }

    @Test fun isolatedMaskIslandIsRemoved() {
        val initial = mask(80, 60) { x, y -> y < 28 || (x in 55..57 && y in 48..50) }
        val refined = refine(uniformImage(80, 60, 50), initial)
        assertFalse(refined.binaryMask.contains(56, 49))
        assertTrue(refined.diagnostics.removedIslandPixels >= 9)
    }

    @Test fun smallHoleInsideSkyIsFilled() {
        val initial = mask(80, 60) { x, y -> y < 35 && !(x == 30 && y == 16) }
        val refined = refine(uniformImage(80, 60, 50), initial)
        assertTrue(refined.binaryMask.contains(30, 16))
        assertTrue(refined.diagnostics.filledHolePixels >= 1)
    }

    @Test fun thinDarkWireAgainstBrightSkyIsProtected() {
        val image = wireScene(background = 190, wire = 18)
        val protection = ForegroundProtectionMask(1).detect(image, emptyList())
        assertTrue(protection.mask.contains(40, 25))
    }

    @Test fun thinBrightWireAgainstDarkSkyIsProtected() {
        val image = wireScene(background = 18, wire = 210)
        val protection = ForegroundProtectionMask(1).detect(image, emptyList())
        assertTrue(protection.mask.contains(40, 25))
    }

    @Test fun knownStarIsNotProtectedAsWire() {
        val image = uniformImage(80, 60, 25)
        for (y in 28..32) for (x in 38..42) {
            val distance = abs(x - 40) + abs(y - 30)
            if (distance <= 2) image.pixels[y * 80 + x] = gray(230 - distance * 45)
        }
        val protection = ForegroundProtectionMask(1).detect(image, listOf(star(40f, 30f)))
        assertFalse(protection.mask.contains(40, 30))
    }

    @Test fun foregroundProtectionUsesConfiguredDilationRadius() {
        val protection = ForegroundProtectionMask(2).detect(wireScene(190, 18), emptyList())
        assertEquals(2, protection.dilationRadius)
        assertTrue(protection.mask.contains(42, 25))
        assertFalse(protection.mask.contains(43, 25))
    }

    @Test fun adaptiveProtectionRadiusScalesWithResolution() {
        val detector = ForegroundProtectionMask()
        assertTrue(detector.adaptiveRadius(2160, 1440) >= detector.adaptiveRadius(640, 480))
    }

    @Test fun alphaOneReturnsStackedSkyExactly() {
        val stacked = imageOf(gray(220))
        val reference = imageOf(gray(20))
        val result = compose(stacked, reference, AlphaMask.full(1, 1))
        assertEquals(stacked.pixels.single(), result.image.pixels.single())
    }

    @Test fun alphaZeroReturnsReferenceExactly() {
        val stacked = imageOf(gray(220))
        val reference = imageOf(gray(20))
        val result = compose(stacked, reference, AlphaMask.empty(1, 1))
        assertEquals(reference.pixels.single(), result.image.pixels.single())
    }

    @Test fun partialAlphaMatchesLinearRgbBlend() {
        val result = compose(
            imageOf(gray(255)),
            imageOf(gray(0)),
            AlphaMask(1, 1, floatArrayOf(0.5f))
        )
        val expected = (SrgbTransfer.linearToSrgb(0.5f) * 255f).roundToInt()
        assertEquals(expected, result.image.pixels.single() and 0xFF)
    }

    @Test fun compositionIsNotGammaSpaceBlend() {
        val result = compose(
            imageOf(gray(255)),
            imageOf(gray(0)),
            AlphaMask(1, 1, floatArrayOf(0.5f))
        )
        assertTrue((result.image.pixels.single() and 0xFF) > 180)
        assertTrue((result.image.pixels.single() and 0xFF) != 128)
    }

    @Test fun featheringCreatesSoftSkylineWithoutHardBinarySeam() {
        val sky = mask(12, 10) { _, y -> y < 6 }
        val result = MaskFeathering().feather(sky, radiusOverride = 3).alphaMask
        assertEquals(0f, result.alphaAt(5, 6), 0f)
        assertTrue(result.alphaAt(5, 5) in 0f..0.99f)
        assertTrue(result.alphaAt(5, 4) > result.alphaAt(5, 5))
    }

    @Test fun featheringKeepsProtectedWireAtReferenceAlpha() {
        val sky = SkyMask.full(11, 7)
        val wire = mask(11, 7) { x, _ -> x == 5 }
        val alpha = MaskFeathering().feather(sky, wire, radiusOverride = 6).alphaMask
        assertEquals(0f, alpha.alphaAt(5, 3), 0f)
        assertEquals(1f, alpha.alphaAt(3, 3), 0f)
    }

    @Test fun adaptiveFeatherRadiusIsPracticalAtPhotoResolution() {
        val radius = MaskFeathering().adaptiveRadius(1440, 1920)
        assertTrue(radius in 2..8)
        assertEquals(6, radius)
    }

    @Test fun refinedMaskFeathersAcrossItsCoarseColorGridAtPhotoResolution() {
        val refined = refine(uniformImage(1440, 1920, 35))

        assertTrue(refined.diagnostics.featherRadius >= 48)
    }

    @Test fun invalidCoverageFallsBackToReference() {
        val result = compose(
            imageOf(gray(0)),
            imageOf(gray(90)),
            AlphaMask.full(1, 1),
            AlphaMask.empty(1, 1)
        )
        assertEquals(gray(90), result.image.pixels.single())
        assertEquals(1f, result.diagnostics.referenceFallbackRatio, 0f)
    }

    @Test fun invalidBordersNeverBecomeBlack() {
        val stacked = ArgbPixelImage(3, 1, intArrayOf(gray(0), gray(180), gray(0)))
        val reference = ArgbPixelImage(3, 1, intArrayOf(gray(70), gray(70), gray(70)))
        val coverage = AlphaMask(3, 1, floatArrayOf(0f, 1f, 0f))
        val result = compose(stacked, reference, AlphaMask.full(3, 1), coverage)
        assertEquals(gray(70), result.image.pixels[0])
        assertEquals(gray(70), result.image.pixels[2])
    }

    @Test fun integratorReportsNormalizedPerPixelCoverage() = runBlocking {
        val coverage = FloatArray(3)
        LinearWeightedIntegrator().integrate(
            outputWidth = 3,
            outputHeight = 1,
            frames = listOf(
                WeightedIntegrationFrame("reference", intArrayOf(gray(20), gray(20), gray(20)), registration(), 1f),
                WeightedIntegrationFrame("shifted", intArrayOf(gray(40), gray(40), gray(40)), registration(dx = 10f), 1f)
            ),
            maximumWorkingMemoryBytes = 8L * 1024L * 1024L,
            openSource = { IntArrayPixelSource(3, 1, it) },
            writeTile = { _, _ -> },
            writeCoverageTile = { tile, values ->
                values.copyInto(coverage, destinationOffset = tile.left)
            }
        )
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), coverage, 0.0001f)
    }

    @Test fun integratorSkipsForegroundOutsideInitialSkyMask() = runBlocking {
        val output = IntArray(3)
        val coverage = FloatArray(3)
        LinearWeightedIntegrator().integrate(
            outputWidth = 3,
            outputHeight = 1,
            frames = listOf(
                WeightedIntegrationFrame("reference", intArrayOf(gray(90), gray(90), gray(90)), registration(), 1f)
            ),
            maximumWorkingMemoryBytes = 8L * 1024L * 1024L,
            openSource = { IntArrayPixelSource(3, 1, it) },
            includeOutputPixel = { x, _ -> x < 2 },
            writeTile = { _, values -> values.copyInto(output) },
            writeCoverageTile = { _, values -> values.copyInto(coverage) }
        )
        assertEquals(gray(90), output[0])
        assertEquals(0f, coverage[2], 0f)
    }

    @Test fun outputWidthEqualsReferenceWidth() {
        val result = compose(uniformImage(9, 5, 80), uniformImage(9, 5, 20), AlphaMask.full(9, 5))
        assertEquals(9, result.image.width)
    }

    @Test fun outputHeightEqualsReferenceHeight() {
        val result = compose(uniformImage(9, 5, 80), uniformImage(9, 5, 20), AlphaMask.full(9, 5))
        assertEquals(5, result.image.height)
    }

    @Test fun outputAspectRatioIsUnchanged() {
        val result = compose(uniformImage(12, 5, 80), uniformImage(12, 5, 20), AlphaMask.full(12, 5))
        assertEquals(12.0 / 5.0, result.image.width.toDouble() / result.image.height, 0.0)
    }

    @Test fun composerNeverAppliesCommonAreaCrop() {
        val result = compose(uniformImage(8, 6, 80), uniformImage(8, 6, 20), AlphaMask.full(8, 6))
        assertFalse(result.diagnostics.cropApplied)
    }

    @Test fun foregroundOutsideTransitionMatchesReferenceExactly() {
        val stacked = uniformImage(8, 8, 220)
        val reference = uniformImage(8, 8, 30)
        val alpha = AlphaMask(8, 8, FloatArray(64) { if (it / 8 < 4) 1f else 0f })
        val result = compose(stacked, reference, alpha)
        for (y in 4 until 8) for (x in 0 until 8) {
            assertEquals(reference.pixelAt(x, y), result.image.pixelAt(x, y))
        }
        assertEquals(0, result.diagnostics.maximumForegroundChannelDifference)
    }

    @Test fun foregroundEdgeSharpnessIsPreserved() {
        val reference = edgeScene()
        val alpha = AlphaMask(20, 12, FloatArray(240) { if (it / 20 < 5) 1f else 0f })
        val result = compose(uniformImage(20, 12, 100), reference, alpha)
        assertEquals(
            result.diagnostics.foregroundSharpnessBefore,
            result.diagnostics.foregroundSharpnessAfter,
            0.0001f
        )
    }

    @Test fun shiftedBuildingEdgesAreNotDoubled() {
        val reference = edgeScene()
        val shifted = ArgbPixelImage(20, 12, reference.pixels.copyOf()).also { image ->
            for (y in 5 until 12) for (x in 0 until 20) {
                image.pixels[y * 20 + x] = gray(if (x < 13) 20 else 210)
            }
        }
        val alpha = AlphaMask(20, 12, FloatArray(240) { if (it / 20 < 5) 1f else 0f })
        val result = compose(shifted, reference, alpha)
        for (y in 5 until 12) for (x in 0 until 20) {
            assertEquals(reference.pixelAt(x, y), result.image.pixelAt(x, y))
        }
    }

    @Test fun protectedWireRemainsSingleAndContinuous() {
        val reference = wireScene(180, 15)
        val stacked = uniformImage(80, 60, 180)
        val wire = mask(80, 60) { x, _ -> x == 40 }
        val alpha = MaskFeathering().feather(SkyMask.full(80, 60), wire, radiusOverride = 4).alphaMask
        val result = compose(stacked, reference, alpha)
        for (y in 0 until 60) assertEquals(15, result.image.pixelAt(40, y) and 0xFF)
        assertTrue((0 until 80).count { x -> (result.image.pixelAt(x, 30) and 0xFF) < 40 } == 1)
    }

    @Test fun postProcessingDamageCanBeRestoredOnlyOutsideSky() {
        val reference = edgeScene()
        val damaged = uniformImage(20, 12, 245)
        val alpha = AlphaMask(20, 12, FloatArray(240) { if (it / 20 < 5) 1f else 0f })
        val restored = compose(damaged, reference, alpha)
        for (y in 5 until 12) for (x in 0 until 20) {
            assertEquals(reference.pixelAt(x, y), restored.image.pixelAt(x, y))
        }
        assertEquals(gray(245), restored.image.pixelAt(4, 2))
    }

    @Test fun existingPresetIdentifiersRemainCompatible() {
        assertEquals(
            listOf("NORMAL", "DEEP_SKY", "DEEP_SKY_ALIGNED", "URBAN_SKY", "URBAN_SKY_STRONG", "MAX_STARS"),
            AstroProcessingProfile.entries.map { it.name }
        )
    }

    @Test fun pngSupportRemainsEnabledForComposedOutput() {
        assertTrue(isSupportedProcessedImageFile("DeepSky_20260715.png", "image/png"))
        assertEquals("image/png", processedImageMimeType("DeepSky_20260715.png"))
    }

    private fun refine(
        image: ArgbPixelImage,
        initial: SkyMask = SkyMask.full(image.width, image.height)
    ) = SkyMaskRefiner().refine(
        initialMask = initial,
        reference = image,
        stars = emptyList(),
        initialConfidence = 1f,
        initialUsedFallback = false,
        registrationConfidence = 1f
    )

    private fun compose(
        stacked: ArgbPixelImage,
        reference: ArgbPixelImage,
        alpha: AlphaMask,
        coverage: AlphaMask = AlphaMask.full(reference.width, reference.height)
    ) = SkyForegroundComposer().compose(stacked, reference, alpha, coverage)

    private fun skyBuildingScene(): ArgbPixelImage = ArgbPixelImage(
        80,
        60,
        IntArray(80 * 60) { index -> gray(if (index / 80 < 36) 62 else 14) }
    ).also { image ->
        for (y in 44..52) for (x in 28..39) image.pixels[y * 80 + x] = gray(245)
    }

    private fun edgeScene(): ArgbPixelImage = ArgbPixelImage(
        20,
        12,
        IntArray(20 * 12) { index ->
            val x = index % 20
            val y = index / 20
            gray(if (y < 5) 70 else if (x < 10) 18 else 215)
        }
    )

    private fun wireScene(background: Int, wire: Int): ArgbPixelImage =
        uniformImage(80, 60, background).also { image ->
            for (y in 0 until image.height) image.pixels[y * image.width + 40] = gray(wire)
        }

    private fun uniformImage(width: Int, height: Int, value: Int) =
        ArgbPixelImage(width, height, IntArray(width * height) { gray(value) })

    private fun imageOf(color: Int) = ArgbPixelImage(1, 1, intArrayOf(color))

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue


    private fun mask(width: Int, height: Int, included: (Int, Int) -> Boolean) =
        SkyMask(width, height, BooleanArray(width * height) { index -> included(index % width, index / width) })

    private fun ArgbPixelImage.copyImage() = ArgbPixelImage(width, height, pixels.copyOf())

    private fun star(x: Float, y: Float) = DetectedStar(
        x = x,
        y = y,
        flux = 200f,
        localBackground = 25f,
        localContrast = 120f,
        width = 2f,
        ellipticity = 0.05f,
        confidence = 1f
    )

    private fun registration(dx: Float = 0f) = RegistrationResult(
        dx = dx,
        dy = 0f,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = 12,
        matchedStars = 12,
        inlierStars = 12,
        residualError = 0f,
        confidence = 1f,
        isReliable = true,
        rejectionReason = null,
        referenceStars = 12
    )

    private fun gray(value: Int): Int {
        val channel = value.coerceIn(0, 255)
        return 0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or channel
    }
}
