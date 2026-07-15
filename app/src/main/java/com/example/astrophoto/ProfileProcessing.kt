package com.example.astrophoto

internal data class AstroProfileRecipe(
    val roi: AstroRoi,
    val alignmentRoi: AstroRoi,
    val roiName: String,
    val sensitivity: StarDetectionSensitivity,
    val backgroundMode: BackgroundRemovalMode,
    val stretchMode: AstroStretchMode,
    val starBoostMode: StarBoostMode,
    val useSignalPreservingSigma: Boolean,
    val sigma: Double,
    val aggressiveAlignment: Boolean,
    val requiresAlignment: Boolean
)

internal fun profileRecipe(
    profile: AstroProcessingProfile,
    frameCount: Int
): AstroProfileRecipe {
    require(frameCount >= 1)
    val hasStableStackSignal = frameCount >= MIN_FRAMES_FOR_SIGNAL_PROCESSING
    fun safeStarBoost(mode: StarBoostMode): StarBoostMode =
        if (hasStableStackSignal) mode else StarBoostMode.OFF

    return when (profile) {
        AstroProcessingProfile.NORMAL -> AstroProfileRecipe(
            AstroRoi.Full, AstroRoi.Top70, "Full", StarDetectionSensitivity.MEDIUM,
            BackgroundRemovalMode.NONE, AstroStretchMode.OFF, StarBoostMode.OFF,
            false, 2.0, false, false
        )
        AstroProcessingProfile.DEEP_SKY,
        AstroProcessingProfile.DEEP_SKY_ALIGNED -> AstroProfileRecipe(
            AstroRoi.Full, AstroRoi.Top70, "Full frame", StarDetectionSensitivity.MEDIUM,
            BackgroundRemovalMode.SOFT, AstroStretchMode.NATURAL,
            safeStarBoost(StarBoostMode.SOFT),
            hasStableStackSignal,
            if (frameCount >= 15) 2.5 else 2.0,
            false,
            profile == AstroProcessingProfile.DEEP_SKY_ALIGNED
        )
        AstroProcessingProfile.URBAN_SKY -> AstroProfileRecipe(
            AstroRoi.Top70, AstroRoi.Top70, "Top 70%", StarDetectionSensitivity.MEDIUM,
            BackgroundRemovalMode.URBAN, AstroStretchMode.NATURAL,
            safeStarBoost(StarBoostMode.SOFT),
            hasStableStackSignal, 2.0, false, false
        )
        AstroProcessingProfile.URBAN_SKY_STRONG -> AstroProfileRecipe(
            AstroRoi.Top70, AstroRoi.Top70, "Top 70%", StarDetectionSensitivity.HIGH,
            BackgroundRemovalMode.URBAN, AstroStretchMode.NATURAL,
            safeStarBoost(StarBoostMode.STRONG),
            hasStableStackSignal, 2.0, false, false
        )
        AstroProcessingProfile.MAX_STARS -> AstroProfileRecipe(
            AstroRoi.Top70, AstroRoi.Top70, "Top 70%", StarDetectionSensitivity.HIGH,
            BackgroundRemovalMode.STRONG, AstroStretchMode.NATURAL,
            safeStarBoost(StarBoostMode.STRONG),
            hasStableStackSignal, 2.5, true, false
        )
    }
}

/** Legacy pixel-wide recipe retained for deterministic compatibility tests and manual behavior only. */
internal fun applyLegacyProfilePostProcessingInPlace(
    image: ArgbPixelImage,
    profile: AstroProcessingProfile,
    frameCount: Int
) {
    val recipe = profileRecipe(profile, frameCount)
    BackgroundRemoval().applyInPlace(image, recipe.backgroundMode, recipe.roi)
    AstroStretch().applyInPlace(image, recipe.stretchMode)
    val stars = StarDetector().detect(image, recipe.roi, recipe.sensitivity).stars
    StarBoost().applyInPlace(image, stars, recipe.starBoostMode)
}

private const val MIN_FRAMES_FOR_SIGNAL_PROCESSING = 6
