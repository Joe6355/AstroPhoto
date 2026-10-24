package com.joe6355.astrophoto.processing.jpeg.v2.composition

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionSource
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ReferenceStarRestorationPlan(
    val sourceIndices: List<Int>,
    val starsToRestore: List<DetectedStar>
) {
    /** Intersection of a measured neighbourhood with the planned, clipped copy stencil. */
    fun intersectsPatch(star: DetectedStar, width: Int, height: Int): Boolean {
        val centerX = star.x.roundToInt()
        val centerY = star.y.roundToInt()
        val radius = referenceStarPreservationRadius(star)
        return starsToRestore.any { restored ->
            val restoredRadius = referenceStarPreservationRadius(restored)
            val restoredX = restored.x.roundToInt()
            val top = maxOf(0, centerY - radius, ceil(restored.y - restoredRadius).toInt())
            val bottom = minOf(height - 1, centerY + radius, floor(restored.y + restoredRadius).toInt())
            (top..bottom).any { y ->
                val dy = y - centerY
                val span = floor(sqrt((radius * radius - dy * dy).toDouble())).toInt()
                val restoredDy = y - restored.y
                val restoredSpan = sqrt((restoredRadius * restoredRadius - restoredDy * restoredDy)
                    .coerceAtLeast(0f)).roundToInt()
                maxOf(0, centerX - span, restoredX - restoredSpan) <=
                    minOf(width - 1, centerX + span, restoredX + restoredSpan)
            }
        }
    }
}

class ReferenceStarRestorationPolicy {
    fun plan(
        stars: List<DetectedStar>,
        measurements: List<ReferenceStarRetentionSource>
    ): ReferenceStarRestorationPlan {
        val byIndex = measurements.associateBy { it.sourceIndex }
        val indices = stars.indices.filter { index ->
            byIndex[index]?.let(::canKeepStackedSignal) != true
        }
        return ReferenceStarRestorationPlan(indices, indices.map(stars::get))
    }

    private fun canKeepStackedSignal(source: ReferenceStarRetentionSource): Boolean =
        source.retained && source.beforePresent && source.afterPresent &&
            source.beforeContrast.isFinite() && source.beforeContrast > 0f &&
            source.afterContrast.isFinite() &&
            source.afterContrast >= source.beforeContrast * ReferenceStarRetentionValidator.MIN_MEDIAN_CONTRAST_RATIO &&
            source.beforeWidth.isFinite() && source.beforeWidth > 0f && source.afterWidth.isFinite() &&
            source.afterWidth <= source.beforeWidth * (1f + ReferenceStarRetentionValidator.MAX_MEDIAN_WIDTH_GROWTH) &&
            source.centroidShift.isFinite() &&
            source.centroidShift <= ReferenceStarRetentionValidator.MAX_MEDIAN_CENTROID_SHIFT
}
