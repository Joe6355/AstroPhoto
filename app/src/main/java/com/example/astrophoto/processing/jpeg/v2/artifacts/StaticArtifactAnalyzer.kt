package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis

class StaticArtifactAnalyzer(
    private val temporalConsistency: TemporalPixelConsistency = TemporalPixelConsistency()
) {
    fun excludeFrom(analysis: FrameAnalysis, mask: StaticArtifactMask): FrameAnalysis {
        val filtered = mask.filter(analysis.stars)
        return analysis.copy(
            stars = filtered,
            reliableStarCount = filtered.size,
            medianStarContrast = median(filtered.map { it.localContrast }, 0f),
            medianStarWidth = median(filtered.map { it.width }, Float.POSITIVE_INFINITY),
            medianStarEllipticity = median(filtered.map { it.ellipticity }, 1f),
            alignmentSuitability = (analysis.alignmentSuitability *
                (filtered.size.toFloat() / analysis.stars.size.coerceAtLeast(1))).coerceIn(0f, 1f)
        )
    }

    fun analyze(
        frames: List<ArtifactFrameObservation>,
        imageWidth: Int,
        imageHeight: Int
    ): StaticArtifactMask {
        require(imageWidth > 0 && imageHeight > 0)
        if (frames.size < TemporalPixelConsistency.MIN_TEMPORAL_FRAMES) {
            return StaticArtifactMask.empty(imageWidth, imageHeight)
        }
        val regions = temporalConsistency.stationaryTracks(frames).mapNotNull { track ->
            val stars = track.observations.map { it.second }
            val representative = representative(stars)
            val type = classify(representative) ?: return@mapNotNull null
            if (
                track.positionDeviation > MAX_POSITION_DEVIATION ||
                track.contrastVariation > MAX_CONTRAST_VARIATION ||
                track.widthVariation > MAX_WIDTH_VARIATION
            ) return@mapNotNull null
            val confidence = (
                track.presenceRatio * 0.45f +
                    (1f - track.positionDeviation / MAX_POSITION_DEVIATION).coerceIn(0f, 1f) * 0.30f +
                    (1f - track.widthVariation / MAX_WIDTH_VARIATION).coerceIn(0f, 1f) * 0.25f
                ).coerceIn(0f, 1f)
            StaticArtifactRegion(
                x = stars.map { it.x }.average().toFloat(),
                y = stars.map { it.y }.average().toFloat(),
                radius = maxOf(MIN_MASK_RADIUS, representative.width * MASK_RADIUS_SCALE),
                type = type,
                confidence = confidence,
                reason = when (type) {
                    StaticArtifactType.HOT_PIXEL -> "stationary_camera_space_hot_pixel"
                    StaticArtifactType.SINGLE_CHANNEL_SPIKE -> "stationary_sensor_color_spike"
                    StaticArtifactType.REFLECTION_PATCH -> "stationary_camera_space_reflection_patch"
                    StaticArtifactType.FIXED_PATTERN_POINT -> "stationary_fixed_pattern_point"
                }
            )
        }
        val confidence = regions.map { it.confidence }.average()
            .takeIf { it.isFinite() }?.toFloat() ?: 0f
        return StaticArtifactMask(
            imageWidth,
            imageHeight,
            regions,
            confidence,
            StaticArtifactMask.estimatedMaskRatio(imageWidth, imageHeight, regions)
        )
    }

    private fun classify(star: DetectedStar): StaticArtifactType? = when {
        star.width <= MAX_HOT_PIXEL_WIDTH -> StaticArtifactType.HOT_PIXEL
        star.width >= MIN_REFLECTION_WIDTH || star.ellipticity >= MIN_REFLECTION_ELLIPTICITY ->
            StaticArtifactType.REFLECTION_PATCH
        star.localContrast >= MIN_FIXED_PATTERN_CONTRAST && star.width <= MAX_FIXED_PATTERN_WIDTH ->
            StaticArtifactType.FIXED_PATTERN_POINT
        else -> null
    }

    private fun representative(stars: List<DetectedStar>): DetectedStar = stars.sortedWith(
        compareBy<DetectedStar> { it.width }.thenBy { it.localContrast }
    )[stars.size / 2]

    private fun median(values: List<Float>, empty: Float): Float {
        if (values.isEmpty()) return empty
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    companion object {
        private const val MAX_POSITION_DEVIATION = 0.18f
        private const val MAX_CONTRAST_VARIATION = 0.18f
        private const val MAX_WIDTH_VARIATION = 0.16f
        private const val MAX_HOT_PIXEL_WIDTH = 1.15f
        private const val MIN_REFLECTION_WIDTH = 2.70f
        private const val MIN_REFLECTION_ELLIPTICITY = 0.42f
        private const val MIN_FIXED_PATTERN_CONTRAST = 110f
        private const val MAX_FIXED_PATTERN_WIDTH = 1.45f
        private const val MIN_MASK_RADIUS = 2.25f
        private const val MASK_RADIUS_SCALE = 1.8f
    }
}
