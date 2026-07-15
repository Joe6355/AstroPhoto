package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.ceil

enum class StaticArtifactType {
    HOT_PIXEL,
    SINGLE_CHANNEL_SPIKE,
    REFLECTION_PATCH,
    FIXED_PATTERN_POINT
}

data class StaticArtifactRegion(
    val x: Float,
    val y: Float,
    val radius: Float,
    val type: StaticArtifactType,
    val confidence: Float,
    val reason: String
)

data class StaticArtifactMask(
    val width: Int,
    val height: Int,
    val regions: List<StaticArtifactRegion>,
    val confidence: Float,
    val maskRatio: Float
) {
    init {
        require(width > 0 && height > 0)
    }

    val staticHotPixelCandidates: List<StaticArtifactRegion>
        get() = regions.filter {
            it.type == StaticArtifactType.HOT_PIXEL ||
                it.type == StaticArtifactType.SINGLE_CHANNEL_SPIKE
        }

    val staticReflectionCandidates: List<StaticArtifactRegion>
        get() = regions.filter { it.type == StaticArtifactType.REFLECTION_PATCH }

    fun contains(x: Float, y: Float): Boolean = regions.any { region ->
        val dx = x - region.x
        val dy = y - region.y
        dx * dx + dy * dy <= region.radius * region.radius
    }

    fun filter(stars: List<DetectedStar>): List<DetectedStar> = stars.filterNot { contains(it.x, it.y) }

    fun scaledTo(targetWidth: Int, targetHeight: Int): StaticArtifactMask {
        require(targetWidth > 0 && targetHeight > 0)
        val scaleX = targetWidth.toFloat() / width
        val scaleY = targetHeight.toFloat() / height
        val radiusScale = (scaleX + scaleY) * 0.5f
        return StaticArtifactMask(
            targetWidth,
            targetHeight,
            regions.map { region ->
                region.copy(
                    x = region.x * scaleX,
                    y = region.y * scaleY,
                    radius = region.radius * radiusScale
                )
            },
            confidence,
            maskRatio
        )
    }

    companion object {
        fun empty(width: Int, height: Int) = StaticArtifactMask(width, height, emptyList(), 0f, 0f)

        internal fun estimatedMaskRatio(width: Int, height: Int, regions: List<StaticArtifactRegion>): Float {
            val covered = regions.sumOf { region ->
                ceil(Math.PI * region.radius * region.radius).toLong()
            }
            return (covered.toFloat() / (width.toLong() * height).coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
    }
}
