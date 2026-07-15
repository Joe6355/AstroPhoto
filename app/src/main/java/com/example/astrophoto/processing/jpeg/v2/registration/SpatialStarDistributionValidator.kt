package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar

data class SpatialStarDistribution(
    val occupiedCells: Int,
    val horizontalSpan: Float,
    val verticalSpan: Float,
    val boundingBoxCoverage: Float,
    val concentrationRatio: Float,
    val score: Float,
    val rotationAllowed: Boolean,
    val rejectionReason: String?
)

class SpatialStarDistributionValidator(
    private val gridSize: Int = 3
) {
    fun evaluate(
        inlierReferenceStars: List<DetectedStar>,
        imageWidth: Int,
        imageHeight: Int
    ): SpatialStarDistribution {
        require(imageWidth > 0 && imageHeight > 0 && gridSize >= 2)
        if (inlierReferenceStars.isEmpty()) return SpatialStarDistribution(
            0, 0f, 0f, 0f, 1f, 0f, false, "no_spatial_inliers"
        )
        val cells = IntArray(gridSize * gridSize)
        inlierReferenceStars.forEach { star ->
            val column = (star.x / imageWidth * gridSize).toInt().coerceIn(0, gridSize - 1)
            val row = (star.y / imageHeight * gridSize).toInt().coerceIn(0, gridSize - 1)
            cells[row * gridSize + column]++
        }
        val minX = inlierReferenceStars.minOf { it.x }
        val maxX = inlierReferenceStars.maxOf { it.x }
        val minY = inlierReferenceStars.minOf { it.y }
        val maxY = inlierReferenceStars.maxOf { it.y }
        val horizontalSpan = ((maxX - minX) / imageWidth).coerceIn(0f, 1f)
        val verticalSpan = ((maxY - minY) / imageHeight).coerceIn(0f, 1f)
        val occupied = cells.count { it > 0 }
        val concentration = cells.maxOrNull()!!.toFloat() / inlierReferenceStars.size
        val coverage = horizontalSpan * verticalSpan
        val score = (
            (occupied / MIN_OCCUPIED_CELLS.toFloat()).coerceIn(0f, 1f) * 0.35f +
                (horizontalSpan / MIN_SPAN).coerceIn(0f, 1f) * 0.25f +
                (verticalSpan / MIN_SPAN).coerceIn(0f, 1f) * 0.25f +
                (1f - concentration / MAX_CONCENTRATION).coerceIn(0f, 1f) * 0.15f
            ).coerceIn(0f, 1f)
        val rejection = when {
            occupied < MIN_OCCUPIED_CELLS -> "rotation_inliers_occupy_too_few_cells"
            horizontalSpan < MIN_SPAN -> "rotation_inliers_horizontal_span_too_small"
            verticalSpan < MIN_SPAN -> "rotation_inliers_vertical_span_too_small"
            concentration > MAX_CONCENTRATION -> "rotation_inliers_are_clustered"
            else -> null
        }
        return SpatialStarDistribution(
            occupied,
            horizontalSpan,
            verticalSpan,
            coverage,
            concentration,
            score,
            rejection == null,
            rejection
        )
    }

    companion object {
        const val MIN_OCCUPIED_CELLS = 4
        const val MIN_SPAN = 0.30f
        const val MAX_CONCENTRATION = 0.55f
    }
}
