package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptiveAsinhStretch
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptiveGradientRemoval
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.postprocessing.BackgroundNeutralizer
import com.example.astrophoto.processing.jpeg.v2.postprocessing.ChromaNoiseReducer
import com.example.astrophoto.processing.jpeg.v2.postprocessing.LocalStarContrastEnhancer
import com.example.astrophoto.processing.jpeg.v2.postprocessing.SkyStatistics
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class JpegV2Stage4Test {
    private val statistics = SkyStatistics()

    @Test fun skyStatisticsIgnoreForegroundPixels() {
        val image = splitScene(48, 40, rgb(22, 22, 22), rgb(230, 180, 90))
        val result = statistics.calculate(image, topMask(48, 40, 20))
        assertEquals(linearLuminanceOf(rgb(22, 22, 22)), result.luminanceMedian, 0.002f)
    }

    @Test fun skyStatisticsIgnoreInvalidCoverage() {
        val image = uniform(32, 24, rgb(24, 24, 24)).also { it.pixels[4] = rgb(255, 255, 255) }
        val alpha = AlphaMask(32, 24, FloatArray(32 * 24) { if (it == 4) 0f else 1f })
        val result = statistics.calculate(image, alpha)
        assertEquals(linearLuminanceOf(rgb(24, 24, 24)), result.luminanceMedian, 0.002f)
    }

    @Test fun skyStatisticsIgnoreBrightWindowsOutsideMask() {
        val base = splitScene(48, 40, rgb(26, 24, 22), rgb(12, 12, 12))
        val window = base.copy(pixels = base.pixels.copyOf()).also { image ->
            for (y in 28..35) for (x in 30..42) image.pixels[y * image.width + x] = rgb(255, 190, 70)
        }
        assertEquals(
            statistics.calculate(base, topMask(48, 40, 20)),
            statistics.calculate(window, topMask(48, 40, 20))
        )
    }

    @Test fun skyStatisticsExcludeDetectedStarCoresFromBackground() {
        val image = starScene(48, 40)
        val result = statistics.calculate(image, AlphaMask.full(48, 40), listOf(star(24, 18)))
        assertEquals(linearLuminanceOf(rgb(20, 20, 20)), result.luminanceMedian, 0.002f)
    }

    @Test fun medianAndMadAreRobustAndDeterministic() {
        val image = uniform(40, 30, rgb(32, 32, 32))
        val first = statistics.calculate(image, AlphaMask.full(40, 30))
        val second = statistics.calculate(image, AlphaMask.full(40, 30))
        assertEquals(first, second)
        assertEquals(0f, first.luminanceMad, 1f / 4095f)
    }

    @Test fun gradientRemovalReducesBroadGradient() {
        val image = gradientScene()
        val mask = AlphaMask.full(image.width, image.height)
        val before = statistics.calculate(image, mask)
        val result = AdaptiveGradientRemoval().apply(image, mask, emptyList(), before, 1f, 0.15f)
        assertTrue(horizontalGradient(result.image) < horizontalGradient(image))
    }

    @Test fun gradientRemovalPreservesSyntheticStars() {
        val image = gradientScene().also { addStar(it, 48, 28) }
        val mask = AlphaMask.full(image.width, image.height)
        val candidate = star(48, 28)
        val beforeContrast = localContrast(image, 48, 28)
        val result = AdaptiveGradientRemoval().apply(
            image, mask, listOf(candidate), statistics.calculate(image, mask, listOf(candidate)), 1f, 0.12f
        )
        assertTrue(localContrast(result.image, 48, 28) >= beforeContrast * 0.85f)
    }

    @Test fun gradientRemovalDoesNotAlterForeground() {
        val image = gradientScene()
        val mask = topMask(image.width, image.height, image.height / 2)
        val result = AdaptiveGradientRemoval().apply(
            image, mask, emptyList(), statistics.calculate(image, mask), 1f, 0.12f
        )
        for (y in image.height / 2 until image.height) for (x in 0 until image.width) {
            assertEquals(image.pixelAt(x, y), result.image.pixelAt(x, y))
        }
    }

    @Test fun gradientRemovalDoesNotCreateDarkBuildingHalos() {
        val image = splitScene(96, 72, rgb(50, 42, 35), rgb(12, 12, 12))
        val mask = topMask(96, 72, 36)
        val result = AdaptiveGradientRemoval().apply(
            image, mask, emptyList(), statistics.calculate(image, mask), 1f, 0.12f
        )
        val boundary = channel(result.image.pixelAt(40, 34), 8)
        assertTrue(boundary >= 35)
        assertEquals(image.pixelAt(40, 38), result.image.pixelAt(40, 38))
    }

    @Test fun backgroundNeutralizationReducesColorCast() {
        val image = uniform(64, 48, rgb(85, 48, 28))
        val mask = AlphaMask.full(64, 48)
        val before = averageRedBlueDifference(image)
        val result = BackgroundNeutralizer().apply(
            image, mask, emptyList(), statistics.calculate(image, mask), 1f, 0.15f
        )
        assertTrue(averageRedBlueDifference(result.image) < before)
    }

    @Test fun backgroundNeutralizationPreservesStarColorRatios() {
        val image = uniform(64, 48, rgb(70, 40, 25)).also { addColoredStar(it, 30, 20) }
        val candidate = star(30, 20)
        val mask = AlphaMask.full(64, 48)
        val result = BackgroundNeutralizer().apply(
            image, mask, listOf(candidate), statistics.calculate(image, mask, listOf(candidate)), 1f, 0.15f
        )
        assertEquals(image.pixelAt(30, 20), result.image.pixelAt(30, 20))
    }

    @Test fun adaptiveStretchRaisesFaintStarContrast() {
        val image = starScene(64, 48)
        val candidate = star(32, 20)
        val mask = AlphaMask.full(64, 48)
        val result = AdaptiveAsinhStretch().apply(
            image, mask, listOf(candidate), statistics.calculate(image, mask, listOf(candidate)),
            0.45f, 5f, 0.9f, 2.5f, 0.4f
        )
        assertTrue(localContrast(result.image, 32, 20) > localContrast(image, 32, 20))
    }

    @Test fun adaptiveStretchProtectsBrightStarsFromClipping() {
        val image = uniform(64, 48, rgb(18, 18, 18)).also { addStar(it, 32, 20, 245) }
        val candidate = star(32, 20)
        val mask = AlphaMask.full(64, 48)
        val result = AdaptiveAsinhStretch().apply(
            image, mask, listOf(candidate), statistics.calculate(image, mask, listOf(candidate)),
            0.6f, 7f, 0.98f, 2.5f, 0.4f
        )
        assertTrue(maxChannel(result.image.pixelAt(32, 20)) < 255)
    }

    @Test fun adaptiveStretchRespectsSkyMedianBound() {
        val image = starScene(64, 48)
        val mask = AlphaMask.full(64, 48)
        val before = statistics.calculate(image, mask)
        val result = AdaptiveAsinhStretch().apply(
            image, mask, emptyList(), before, 1f, 12f, 0.9f, 1.5f, 0.4f
        )
        val after = statistics.calculate(result.image, mask)
        assertTrue(after.luminanceMedian <= before.luminanceMedian * 1.5f + 0.001f)
    }

    @Test fun adaptiveStretchDoesNotTurnDarkSkyGray() {
        val image = uniform(64, 48, rgb(12, 12, 12))
        val mask = AlphaMask.full(64, 48)
        val result = AdaptiveAsinhStretch().apply(
            image, mask, emptyList(), statistics.calculate(image, mask),
            0.3f, 6f, 0.95f, 1.5f, 0.42f
        )
        assertTrue(channel(result.image.pixels[0], 8) < 32)
    }

    @Test fun chromaNoiseReductionLowersColorNoise() {
        val image = chromaNoiseScene()
        val mask = AlphaMask.full(image.width, image.height)
        val before = chromaVariation(image)
        val result = ChromaNoiseReducer().apply(
            image, mask, emptyList(), statistics.calculate(image, mask), 1f, 3
        )
        assertTrue(chromaVariation(result.image) < before)
    }

    @Test fun chromaNoiseReductionPreservesLuminanceDetail() {
        val image = splitScene(64, 48, rgb(20, 20, 20), rgb(80, 80, 80))
        val mask = AlphaMask.full(64, 48)
        val result = ChromaNoiseReducer().apply(
            image, mask, emptyList(), statistics.calculate(image, mask), 1f, 3
        )
        assertEquals(localContrast(image, 20, 23), localContrast(result.image, 20, 23), 2f)
    }

    @Test fun chromaNoiseReductionPreservesDetectedStars() {
        val image = starScene(64, 48)
        val candidate = star(32, 20)
        val mask = AlphaMask.full(64, 48)
        val result = ChromaNoiseReducer().apply(
            image, mask, listOf(candidate), statistics.calculate(image, mask, listOf(candidate)), 1f, 3
        )
        assertEquals(image.pixelAt(32, 20), result.image.pixelAt(32, 20))
    }

    @Test fun localStarEnhancementIncreasesLocalContrast() {
        val image = starScene(64, 48)
        val mask = AlphaMask.full(64, 48)
        val result = LocalStarContrastEnhancer().apply(
            image, mask, listOf(star(32, 20)), statistics.calculate(image, mask), 1f, 1.5f, 0.03f
        )
        assertTrue(localContrast(result.image, 32, 20) > localContrast(image, 32, 20))
    }

    @Test fun localStarEnhancementDoesNotIncreaseStarWidth() {
        val image = starScene(64, 48)
        val mask = AlphaMask.full(64, 48)
        val result = LocalStarContrastEnhancer().apply(
            image, mask, listOf(star(32, 20)), statistics.calculate(image, mask), 1f, 1.5f, 0.03f
        )
        assertTrue(result.diagnostics.maximumMeasuredWidthGrowth <= 0.03f)
        assertEquals(image.pixelAt(36, 20), result.image.pixelAt(36, 20))
    }

    @Test fun localStarEnhancementRejectsIsolatedHotPixels() {
        val image = uniform(64, 48, rgb(20, 20, 20)).also { it.pixels[20 * 64 + 32] = rgb(150, 150, 150) }
        val mask = AlphaMask.full(64, 48)
        val result = LocalStarContrastEnhancer().apply(
            image, mask, listOf(star(32, 20)), statistics.calculate(image, mask), 1f, 1.5f, 0.03f
        )
        assertEquals(0, result.diagnostics.enhanced)
        assertArrayEquals(image.pixels, result.image.pixels)
    }

    @Test fun localStarEnhancementRejectsLongLines() {
        val image = uniform(64, 48, rgb(20, 20, 20)).also { candidate ->
            for (x in 20..44) candidate.pixels[20 * 64 + x] = rgb(90, 90, 90)
        }
        val line = star(32, 20).copy(ellipticity = 0.95f, width = 4.6f)
        val mask = AlphaMask.full(64, 48)
        val result = LocalStarContrastEnhancer().apply(
            image, mask, listOf(line), statistics.calculate(image, mask), 1f, 1.5f, 0.03f
        )
        assertEquals(0, result.diagnostics.enhanced)
    }

    @Test fun localStarEnhancementPreservesStarColor() {
        val image = uniform(64, 48, rgb(20, 20, 20)).also { addColoredStar(it, 32, 20) }
        val mask = AlphaMask.full(64, 48)
        val before = image.pixelAt(32, 20)
        val result = LocalStarContrastEnhancer().apply(
            image, mask, listOf(star(32, 20)), statistics.calculate(image, mask), 1f, 1.4f, 0.03f
        )
        val after = result.image.pixelAt(32, 20)
        assertEquals(channel(before, 16).toFloat() / channel(before, 8), channel(after, 16).toFloat() / channel(after, 8), 0.08f)
    }

    @Test fun foregroundRemainsUnchangedOutsideFeatherZone() {
        val sky = gradientScene()
        val reference = splitScene(sky.width, sky.height, rgb(35, 35, 35), rgb(160, 80, 40))
        val result = process(AstroProcessingProfile.URBAN_SKY, sky, topMask(sky.width, sky.height, 40), reference)
        for (y in 40 until sky.height) for (x in 0 until sky.width) {
            assertEquals(reference.pixelAt(x, y), result.image.pixelAt(x, y))
        }
        assertEquals(0, result.diagnostics.foregroundDifferenceOutsideMask)
    }

    @Test fun buildingsAreNotStretched() {
        val sky = starScene(64, 48)
        val reference = splitScene(64, 48, rgb(20, 20, 20), rgb(110, 60, 30))
        val result = process(AstroProcessingProfile.MAX_STARS, sky, topMask(64, 48, 28), reference)
        assertEquals(reference.pixelAt(20, 40), result.image.pixelAt(20, 40))
    }

    @Test fun wiresAreNotEnhancedAsStars() {
        val sky = starScene(64, 48)
        val reference = sky.copy(pixels = sky.pixels.copyOf()).also { image ->
            for (y in 0 until image.height) image.pixels[y * image.width + 30] = rgb(5, 5, 5)
        }
        val alpha = AlphaMask(64, 48, FloatArray(64 * 48) { if (it % 64 == 30) 0f else 1f })
        val result = process(AstroProcessingProfile.MAX_STARS, sky, alpha, reference)
        for (y in 0 until 48) assertEquals(reference.pixelAt(30, y), result.image.pixelAt(30, y))
    }

    @Test fun brightWindowsDoNotAffectSkyParameters() {
        val base = splitScene(64, 48, rgb(25, 23, 20), rgb(20, 20, 20))
        val window = base.copy(pixels = base.pixels.copyOf()).also { image ->
            for (y in 34..44) for (x in 40..55) image.pixels[y * 64 + x] = rgb(255, 180, 60)
        }
        val mask = topMask(64, 48, 28)
        assertEquals(statistics.calculate(base, mask), statistics.calculate(window, mask))
    }

    @Test fun cleanSkyRemainsConservativeAndDark() {
        val image = gradientScene()
        val mask = AlphaMask.full(image.width, image.height)
        val before = statistics.calculate(image, mask)
        val result = process(AstroProcessingProfile.DEEP_SKY, image, mask)
        assertTrue(result.diagnostics.after.luminanceMedian <= before.luminanceMedian * 1.5f + 0.001f)
    }

    @Test fun cleanSkyAlignmentUsesSameVisualParameters() {
        assertEquals(
            ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 8),
            ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY_ALIGNED, 8)
        )
    }

    @Test fun cityWindowRemovesMoreGradientThanCleanSky() {
        val clean = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 8)
        val city = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY, 8)
        assertTrue(city.gradientStrength > clean.gradientStrength)
    }

    @Test fun cityWindowStrongIsMeasurablyStronger() {
        val city = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY, 8)
        val strong = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY_STRONG, 8)
        assertTrue(strong.gradientStrength > city.gradientStrength)
        assertTrue(strong.stretchBlend > city.stretchBlend)
        assertTrue(strong.chromaNoiseStrength > city.chromaNoiseStrength)
        assertTrue(strong.targetDisplaySkyMedian > city.targetDisplaySkyMedian)
        assertTrue(strong.minimumStarContrastGain > city.minimumStarContrastGain)
    }

    @Test fun automaticPresetsExposeRequestedBackgroundAndStarTargets() {
        val deep = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 20)
        val urban = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY, 20)
        val strong = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.URBAN_SKY_STRONG, 20)
        val maximum = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.MAX_STARS, 20)
        assertEquals(18f / 255f, deep.targetDisplaySkyMedian, 0f)
        assertEquals(20f / 255f, urban.targetDisplaySkyMedian, 0f)
        assertEquals(24f / 255f, strong.targetDisplaySkyMedian, 0f)
        assertEquals(26f / 255f, maximum.targetDisplaySkyMedian, 0f)
        assertEquals(0.15f, deep.minimumStarContrastGain, 0f)
        assertEquals(0.20f, urban.minimumStarContrastGain, 0f)
        assertEquals(0.35f, strong.minimumStarContrastGain, 0f)
        assertEquals(0.50f, maximum.minimumStarContrastGain, 0f)
    }

    @Test fun cityWindowStrongIsNotNumericallyEquivalent() {
        val image = gradientScene()
        val normal = process(AstroProcessingProfile.URBAN_SKY, image, AlphaMask.full(image.width, image.height))
        val strong = process(AstroProcessingProfile.URBAN_SKY_STRONG, image, AlphaMask.full(image.width, image.height))
        assertTrue(imageDistance(normal.image, strong.image) > 0.5f)
    }

    @Test fun maximumStarsRevealsMoreFaintStarContrastThanCleanSky() {
        val image = starScene(64, 48)
        val candidate = listOf(star(32, 20))
        val clean = process(AstroProcessingProfile.DEEP_SKY, image, AlphaMask.full(64, 48), stars = candidate)
        val maximum = process(AstroProcessingProfile.MAX_STARS, image, AlphaMask.full(64, 48), stars = candidate)
        assertTrue(localContrast(maximum.image, 32, 20) > localContrast(clean.image, 32, 20))
    }

    @Test fun maximumStarsDoesNotCreatePointsWithoutAlignedStars() {
        val image = chromaNoiseScene()
        val result = process(AstroProcessingProfile.MAX_STARS, image, AlphaMask.full(image.width, image.height))
        assertEquals(0, result.diagnostics.starEnhancement.considered)
        assertEquals(0, result.diagnostics.starEnhancement.enhanced)
    }

    @Test fun allPresetsStayInsideAddedClippingLimits() {
        automaticProfiles().forEach { profile ->
            val image = starScene(64, 48)
            val before = statistics.calculate(image, AlphaMask.full(64, 48))
            val result = process(profile, image, AlphaMask.full(64, 48), stars = listOf(star(32, 20)))
            val allowed = ExistingPresetParameterMapper.parametersFor(profile, 8).maximumChannelClippingPercent
            assertTrue(result.diagnostics.after.channelClippingPercent.red <= maxOf(before.channelClippingPercent.red, allowed) + 0.1f)
            assertTrue(result.image.pixels.all { maxChannel(it) < 255 })
        }
    }

    @Test fun allPresetsPreserveOutputWidth() {
        automaticProfiles().forEach { assertEquals(64, process(it, starScene(64, 48), AlphaMask.full(64, 48)).image.width) }
    }

    @Test fun allPresetsPreserveOutputHeight() {
        automaticProfiles().forEach { assertEquals(48, process(it, starScene(64, 48), AlphaMask.full(64, 48)).image.height) }
    }

    @Test fun allPresetsPreserveAspectRatio() {
        automaticProfiles().forEach {
            val output = process(it, starScene(64, 48), AlphaMask.full(64, 48)).image
            assertEquals(64.0 / 48.0, output.width.toDouble() / output.height, 0.0)
        }
    }

    @Test fun losslessPngOutputCompatibilityRemainsEnabled() {
        assertTrue(isSupportedProcessedImageFile("DeepSky_Stage4.png", "image/png"))
        assertEquals("image/png", processedImageMimeType("DeepSky_Stage4.png"))
    }

    @Test fun stage1StarDetectorStillFindsShapedStars() {
        val image = starScene(96, 72)
        val result = JpegStarDetector().detect(image, SkyMask.full(96, 72))
        assertTrue(result.stars.isNotEmpty())
    }

    @Test fun stage2StackRepresentationRemainsFullResolutionArgb() {
        val image = gradientScene()
        assertEquals(image.width * image.height, image.pixels.size)
        assertTrue(image.pixels.all { it ushr 24 == 0xFF })
    }

    @Test fun stage3ComposerStillPreservesReferenceForeground() {
        val sky = uniform(32, 24, rgb(90, 90, 90))
        val reference = splitScene(32, 24, rgb(20, 20, 20), rgb(150, 80, 30))
        val result = SkyForegroundComposer().compose(sky, reference, topMask(32, 24, 12))
        assertEquals(reference.pixelAt(10, 20), result.image.pixelAt(10, 20))
    }

    @Test fun existingPresetIdentifiersRemainCompatibleInStage4() {
        assertEquals(
            listOf("NORMAL", "DEEP_SKY", "DEEP_SKY_ALIGNED", "URBAN_SKY", "URBAN_SKY_STRONG", "MAX_STARS"),
            AstroProcessingProfile.entries.map { it.name }
        )
    }

    @Test fun existingSavedPresetNamesStillDeserialize() {
        AstroProcessingProfile.entries.forEach { assertEquals(it, AstroProcessingProfile.valueOf(it.name)) }
    }

    @Test fun normalRawFacingProfileHasNoAdaptiveJpegProcessing() {
        val parameters = ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.NORMAL, 8)
        assertEquals(0f, parameters.gradientStrength)
        assertEquals(0f, parameters.stretchBlend)
        assertEquals(0f, parameters.starContrastStrength)
    }

    @Test fun unreliableSmallSkyMakesProcessingConservative() {
        val image = gradientScene()
        val values = FloatArray(image.width * image.height)
        repeat(24) { values[it] = 1f }
        val result = process(AstroProcessingProfile.MAX_STARS, image, AlphaMask(image.width, image.height, values))
        assertTrue(result.diagnostics.before.confidence < 0.5f)
        assertTrue(result.diagnostics.stretch.appliedBlend < 0.2f)
    }

    @Test fun processingIsDeterministicForEveryPreset() {
        automaticProfiles().forEach { profile ->
            val image = gradientScene()
            val first = process(profile, image, AlphaMask.full(image.width, image.height))
            val second = process(profile, image, AlphaMask.full(image.width, image.height))
            assertArrayEquals(profile.name, first.image.pixels, second.image.pixels)
        }
    }

    private fun process(
        profile: AstroProcessingProfile,
        sky: ArgbPixelImage,
        alpha: AlphaMask,
        reference: ArgbPixelImage = sky,
        stars: List<DetectedStar> = emptyList()
    ) = runBlocking {
        AdaptivePresetProcessor().process(sky, reference, alpha, profile, 8, stars)
    }

    private fun automaticProfiles() = AstroProcessingProfile.entries.filterNot { it == AstroProcessingProfile.NORMAL }

    private fun gradientScene(width: Int = 96, height: Int = 72): ArgbPixelImage = ArgbPixelImage(
        width,
        height,
        IntArray(width * height) { index ->
            val x = index % width
            val value = 25 + x * 55 / (width - 1)
            rgb(value + 10, value + 4, value)
        }
    )

    private fun chromaNoiseScene(width: Int = 64, height: Int = 48): ArgbPixelImage = ArgbPixelImage(
        width,
        height,
        IntArray(width * height) { index ->
            if ((index + index / width) % 2 == 0) rgb(42, 28, 22) else rgb(22, 31, 43)
        }
    )

    private fun starScene(width: Int, height: Int): ArgbPixelImage =
        uniform(width, height, rgb(20, 20, 20)).also { addStar(it, width / 2, minOf(20, height / 2)) }

    private fun addStar(image: ArgbPixelImage, centerX: Int, centerY: Int, peak: Int = 105) {
        for (dy in -1..1) for (dx in -1..1) {
            val value = if (dx == 0 && dy == 0) peak else 55
            image.pixels[(centerY + dy) * image.width + centerX + dx] = rgb(value, value, value + 3)
        }
    }

    private fun addColoredStar(image: ArgbPixelImage, centerX: Int, centerY: Int) {
        for (dy in -1..1) for (dx in -1..1) {
            val scale = if (dx == 0 && dy == 0) 1f else 0.55f
            image.pixels[(centerY + dy) * image.width + centerX + dx] =
                rgb((125 * scale).toInt(), (100 * scale).toInt(), (82 * scale).toInt())
        }
    }

    private fun star(x: Int, y: Int) = DetectedStar(
        x.toFloat(), y.toFloat(), 300f, 20f, 85f, 2f, 0.08f, 0.95f
    )

    private fun uniform(width: Int, height: Int, color: Int) =
        ArgbPixelImage(width, height, IntArray(width * height) { color })

    private fun splitScene(width: Int, height: Int, upper: Int, lower: Int) =
        ArgbPixelImage(width, height, IntArray(width * height) { if (it / width < height / 2) upper else lower })

    private fun topMask(width: Int, height: Int, skyRows: Int) =
        AlphaMask(width, height, FloatArray(width * height) { if (it / width < skyRows) 1f else 0f })

    private fun localContrast(image: ArgbPixelImage, x: Int, y: Int): Float {
        val center = pixelLuminance(image.pixelAt(x, y)).toFloat()
        var background = 0f
        var count = 0
        for (dy in -3..3) for (dx in -3..3) {
            if (kotlin.math.abs(dx) < 2 && kotlin.math.abs(dy) < 2) continue
            val px = x + dx
            val py = y + dy
            if (px in 0 until image.width && py in 0 until image.height) {
                background += pixelLuminance(image.pixelAt(px, py))
                count++
            }
        }
        return center - background / count.coerceAtLeast(1)
    }

    private fun horizontalGradient(image: ArgbPixelImage): Float {
        var left = 0f
        var right = 0f
        for (y in 0 until image.height) {
            left += pixelLuminance(image.pixelAt(4, y))
            right += pixelLuminance(image.pixelAt(image.width - 5, y))
        }
        return abs(right - left) / image.height
    }

    private fun averageRedBlueDifference(image: ArgbPixelImage): Float =
        image.pixels.sumOf { (channel(it, 16) - channel(it, 0)).toLong() }.toFloat() / image.pixels.size

    private fun chromaVariation(image: ArgbPixelImage): Float =
        image.pixels.sumOf {
            (abs(channel(it, 16) - channel(it, 8)) + abs(channel(it, 0) - channel(it, 8))).toLong()
        }.toFloat() / image.pixels.size

    private fun imageDistance(first: ArgbPixelImage, second: ArgbPixelImage): Float =
        first.pixels.indices.sumOf { index ->
            (abs(channel(first.pixels[index], 16) - channel(second.pixels[index], 16)) +
                abs(channel(first.pixels[index], 8) - channel(second.pixels[index], 8)) +
                abs(channel(first.pixels[index], 0) - channel(second.pixels[index], 0))).toLong()
        }.toFloat() / first.pixels.size

    private fun linearLuminanceOf(color: Int): Float {
        fun linear(shift: Int): Float = com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
            .srgbToLinear(channel(color, shift) / 255f)
        return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
    }

    private fun channel(color: Int, shift: Int) = color ushr shift and 0xFF
    private fun maxChannel(color: Int) = maxOf(channel(color, 16), channel(color, 8), channel(color, 0))

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
