package com.example.astrophoto.processing.jpeg.v2.profile

import com.example.astrophoto.AstroProcessingProfile
import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters

object ExistingPresetParameterMapper {
    fun parametersFor(
        profile: AstroProcessingProfile,
        frameCount: Int
    ): AdaptiveProcessingParameters {
        require(frameCount >= 1)
        val stableSignal = frameCount >= MIN_STABLE_STACK_FRAMES
        val parameters = when (profile) {
            AstroProcessingProfile.NORMAL -> NONE
            AstroProcessingProfile.DEEP_SKY,
            AstroProcessingProfile.DEEP_SKY_ALIGNED -> CLEAN_SKY
            AstroProcessingProfile.URBAN_SKY -> CITY_WINDOW
            AstroProcessingProfile.URBAN_SKY_STRONG -> CITY_WINDOW_STRONG
            AstroProcessingProfile.MAX_STARS -> MAXIMUM_STARS
        }
        return if (stableSignal) parameters else parameters.copy(starContrastStrength = 0f)
    }

    private val NONE = AdaptiveProcessingParameters(
        gradientStrength = 0f,
        neutralizationStrength = 0f,
        stretchBlend = 0f,
        asinhStrength = 0f,
        highlightProtection = 1f,
        chromaNoiseStrength = 0f,
        starContrastStrength = 0f,
        maximumSkyMedianFactor = 1f,
        maximumChannelClippingPercent = 1f,
        minimumBlackWhiteSeparation = 0.35f,
        maximumGradientCorrection = 0f,
        maximumNeutralizationCorrection = 0f,
        maximumStarDetailGain = 1f,
        maximumChromaRadius = 0,
        maximumStarWidthGrowth = 0f
    )

    private val CLEAN_SKY = AdaptiveProcessingParameters(
        gradientStrength = 0.08f,
        neutralizationStrength = 0.05f,
        stretchBlend = 0.10f,
        asinhStrength = 2.4f,
        highlightProtection = 0.88f,
        chromaNoiseStrength = 0.20f,
        starContrastStrength = 0.02f,
        maximumSkyMedianFactor = 1.50f,
        maximumChannelClippingPercent = 3f,
        minimumBlackWhiteSeparation = 0.42f,
        maximumGradientCorrection = 0.010f,
        maximumNeutralizationCorrection = 0.008f,
        maximumStarDetailGain = 1.05f,
        maximumChromaRadius = 1,
        maximumStarWidthGrowth = 0.02f
    )

    private val CITY_WINDOW = AdaptiveProcessingParameters(
        gradientStrength = 0.56f,
        neutralizationStrength = 0.48f,
        stretchBlend = 0.18f,
        asinhStrength = 3.8f,
        highlightProtection = 0.91f,
        chromaNoiseStrength = 0.50f,
        starContrastStrength = 0.14f,
        maximumSkyMedianFactor = 2.20f,
        maximumChannelClippingPercent = 3f,
        minimumBlackWhiteSeparation = 0.40f,
        maximumGradientCorrection = 0.032f,
        maximumNeutralizationCorrection = 0.026f,
        maximumStarDetailGain = 1.18f,
        maximumChromaRadius = 2,
        maximumStarWidthGrowth = 0.04f
    )

    private val CITY_WINDOW_STRONG = AdaptiveProcessingParameters(
        gradientStrength = 0.80f,
        neutralizationStrength = 0.70f,
        stretchBlend = 0.25f,
        asinhStrength = 5.2f,
        highlightProtection = 0.95f,
        chromaNoiseStrength = 0.74f,
        starContrastStrength = 0.25f,
        maximumSkyMedianFactor = 2.80f,
        maximumChannelClippingPercent = 2.5f,
        minimumBlackWhiteSeparation = 0.42f,
        maximumGradientCorrection = 0.055f,
        maximumNeutralizationCorrection = 0.042f,
        maximumStarDetailGain = 1.27f,
        maximumChromaRadius = 3,
        maximumStarWidthGrowth = 0.04f
    )

    private val MAXIMUM_STARS = AdaptiveProcessingParameters(
        gradientStrength = 0.48f,
        neutralizationStrength = 0.30f,
        stretchBlend = 0.30f,
        asinhStrength = 6.2f,
        highlightProtection = 0.96f,
        chromaNoiseStrength = 0.68f,
        starContrastStrength = 0.52f,
        maximumSkyMedianFactor = 2.50f,
        maximumChannelClippingPercent = 2f,
        minimumBlackWhiteSeparation = 0.44f,
        maximumGradientCorrection = 0.040f,
        maximumNeutralizationCorrection = 0.030f,
        maximumStarDetailGain = 1.45f,
        maximumChromaRadius = 3,
        maximumStarWidthGrowth = 0.03f
    )

    private const val MIN_STABLE_STACK_FRAMES = 6
}
