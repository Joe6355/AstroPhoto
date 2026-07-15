package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.AstroProcessingProfile
import com.example.astrophoto.processing.jpeg.v2.composition.SkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.PresetProcessingResult
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper

class AdaptivePresetProcessor(
    private val statistics: SkyStatistics = SkyStatistics(),
    private val gradientRemoval: AdaptiveGradientRemoval = AdaptiveGradientRemoval(),
    private val neutralizer: BackgroundNeutralizer = BackgroundNeutralizer(),
    private val stretch: AdaptiveAsinhStretch = AdaptiveAsinhStretch(statistics),
    private val chromaNoiseReducer: ChromaNoiseReducer = ChromaNoiseReducer(),
    private val starEnhancer: LocalStarContrastEnhancer = LocalStarContrastEnhancer(),
    private val composer: SkyForegroundComposer = SkyForegroundComposer()
) {
    suspend fun process(
        stackedSky: ArgbPixelImage,
        referenceForeground: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        profile: AstroProcessingProfile,
        frameCount: Int,
        alignedStackStars: List<DetectedStar>,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): PresetProcessingResult {
        require(stackedSky.width == referenceForeground.width && stackedSky.height == referenceForeground.height)
        require(stackedSky.width == effectiveSkyAlpha.width && stackedSky.height == effectiveSkyAlpha.height)
        require(profile != AstroProcessingProfile.NORMAL)
        val started = System.nanoTime()
        val parameters = ExistingPresetParameterMapper.parametersFor(profile, frameCount)

        onProgress("Analyzing sky", 0, TOTAL_STAGES)
        val before = statistics.calculate(stackedSky, effectiveSkyAlpha, alignedStackStars)
        var working = stackedSky

        onProgress("Removing light pollution", 1, TOTAL_STAGES)
        val gradientDiagnostics = gradientRemoval.apply(
            working,
            effectiveSkyAlpha,
            alignedStackStars,
            before,
            parameters.gradientStrength,
            parameters.maximumGradientCorrection
        ).let { result ->
            working = result.image
            result.diagnostics
        }
        var currentStatistics = statistics.calculate(working, effectiveSkyAlpha, alignedStackStars)

        onProgress("Balancing sky color", 2, TOTAL_STAGES)
        val neutralizationDiagnostics = neutralizer.apply(
            working,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.neutralizationStrength,
            parameters.maximumNeutralizationCorrection
        ).let { result ->
            working = result.image
            result.diagnostics
        }
        currentStatistics = statistics.calculate(working, effectiveSkyAlpha, alignedStackStars)

        onProgress("Stretching faint stars", 3, TOTAL_STAGES)
        val stretchDiagnostics = stretch.apply(
            working,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.stretchBlend,
            parameters.asinhStrength,
            parameters.highlightProtection,
            parameters.maximumSkyMedianFactor,
            parameters.minimumBlackWhiteSeparation
        ).let { result ->
            working = result.image
            result.diagnostics
        }
        currentStatistics = statistics.calculate(working, effectiveSkyAlpha, alignedStackStars)

        onProgress("Reducing color noise", 4, TOTAL_STAGES)
        val chromaNoiseDiagnostics = chromaNoiseReducer.apply(
            working,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.chromaNoiseStrength,
            parameters.maximumChromaRadius
        ).let { result ->
            working = result.image
            result.diagnostics
        }

        onProgress("Enhancing stars", 5, TOTAL_STAGES)
        currentStatistics = statistics.calculate(working, effectiveSkyAlpha, alignedStackStars)
        val starEnhancementDiagnostics = starEnhancer.apply(
            working,
            effectiveSkyAlpha,
            alignedStackStars,
            currentStatistics,
            parameters.starContrastStrength,
            parameters.maximumStarDetailGain,
            parameters.maximumStarWidthGrowth
        ).let { result ->
            working = result.image
            result.diagnostics
        }
        var safeSky = working
        var after = statistics.calculate(safeSky, effectiveSkyAlpha, alignedStackStars)
        val safetyScale = finalSafetyScale(
            before,
            after,
            parameters.maximumSkyMedianFactor,
            parameters.maximumChannelClippingPercent
        )
        if (safetyScale < 0.999f) {
            safeSky = blendLinearSky(stackedSky, safeSky, effectiveSkyAlpha, safetyScale)
            after = statistics.calculate(safeSky, effectiveSkyAlpha, alignedStackStars)
        }
        val composite = composer.compose(
            stackedSky = safeSky,
            reference = referenceForeground,
            featheredSkyMask = effectiveSkyAlpha,
            validCoverage = effectiveSkyAlpha,
            precomputedEffectiveSkyAlpha = effectiveSkyAlpha
        )
        val diagnostics = AdaptiveProcessingDiagnostics(
            preset = profile.name,
            before = before,
            after = after,
            gradient = gradientDiagnostics,
            neutralization = neutralizationDiagnostics,
            stretch = stretchDiagnostics.copy(
                medianSafetyScale = stretchDiagnostics.medianSafetyScale * safetyScale
            ),
            chromaNoise = chromaNoiseDiagnostics,
            starEnhancement = starEnhancementDiagnostics,
            foregroundDifferenceOutsideMask = composite.diagnostics.maximumForegroundChannelDifference,
            processingDurationMillis = (System.nanoTime() - started) / 1_000_000L
        )
        return PresetProcessingResult(composite.image, diagnostics)
    }

    private fun finalSafetyScale(
        before: com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult,
        after: com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult,
        maximumMedianFactor: Float,
        maximumClippingPercent: Float
    ): Float {
        if (before.skyPixelCount == 0 || after.skyPixelCount == 0) return 0f
        val allowedMedian = maxOf(
            before.luminanceMedian * maximumMedianFactor,
            before.luminanceMedian + maxOf(before.luminanceMad * 2f, MIN_MEDIAN_HEADROOM)
        )
        val medianScale = if (
            after.luminanceMedian > allowedMedian &&
            after.luminanceMedian > before.luminanceMedian
        ) {
            ((allowedMedian - before.luminanceMedian) /
                (after.luminanceMedian - before.luminanceMedian)).coerceIn(0f, 1f)
        } else {
            1f
        }
        fun clippingScale(beforeValue: Float, afterValue: Float): Float {
            val allowed = maxOf(beforeValue, maximumClippingPercent)
            return if (afterValue > allowed && afterValue > beforeValue) {
                ((allowed - beforeValue) / (afterValue - beforeValue)).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
        return minOf(
            medianScale,
            clippingScale(before.channelClippingPercent.red, after.channelClippingPercent.red),
            clippingScale(before.channelClippingPercent.green, after.channelClippingPercent.green),
            clippingScale(before.channelClippingPercent.blue, after.channelClippingPercent.blue)
        )
    }

    private fun blendLinearSky(
        original: ArgbPixelImage,
        processed: ArgbPixelImage,
        mask: AlphaMask,
        amount: Float
    ): ArgbPixelImage {
        val output = original.pixels.copyOf()
        for (index in output.indices) {
            val x = index % original.width
            val y = index / original.width
            if (mask.alphaAt(x, y) <= OPERATION_ALPHA_THRESHOLD) continue
            val first = original.pixels[index]
            val second = processed.pixels[index]
            output[index] = packLinear(
                linearChannel(first, 16) + (linearChannel(second, 16) - linearChannel(first, 16)) * amount,
                linearChannel(first, 8) + (linearChannel(second, 8) - linearChannel(first, 8)) * amount,
                linearChannel(first, 0) + (linearChannel(second, 0) - linearChannel(first, 0)) * amount
            )
        }
        return ArgbPixelImage(original.width, original.height, output)
    }

    companion object {
        private const val TOTAL_STAGES = 6
        private const val MIN_MEDIAN_HEADROOM = 1f / 4095f
    }
}
