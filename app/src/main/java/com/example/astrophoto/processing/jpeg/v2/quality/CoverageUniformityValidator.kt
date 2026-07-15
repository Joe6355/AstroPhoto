package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil

data class CoverageUniformityMetrics(
    val minimumCoverage: Float,
    val medianCoverage: Float,
    val maximumCoverage: Float,
    val coverageGradient: Float,
    val abruptBoundaryRatio: Float,
    val lowCoverageSkyRatio: Float,
    val largestLowCoverageRegionRatio: Float,
    val uniformityScore: Float,
    val wedgeDiscontinuityScore: Float = 0f
)

data class CoverageUniformityResult(
    val accepted: Boolean,
    val metrics: CoverageUniformityMetrics,
    val hardFailureReasons: List<String>,
    val warningReasons: List<String>
)

class CoverageUniformityValidator {
    fun validate(
        coverage: AlphaMask,
        effectiveSky: AlphaMask
    ): CoverageUniformityResult {
        require(coverage.width == effectiveSky.width && coverage.height == effectiveSky.height)
        val columns = minOf(GRID_SIZE, coverage.width)
        val rows = minOf(GRID_SIZE, coverage.height)
        val sums = FloatArray(columns * rows)
        val counts = IntArray(columns * rows)
        for (y in 0 until coverage.height) for (x in 0 until coverage.width) {
            if (effectiveSky.alphaAt(x, y) < SKY_ALPHA_THRESHOLD) continue
            val column = (x.toLong() * columns / coverage.width).toInt().coerceIn(0, columns - 1)
            val row = (y.toLong() * rows / coverage.height).toInt().coerceIn(0, rows - 1)
            val index = row * columns + column
            sums[index] += coverage.alphaAt(x, y).coerceIn(0f, 1f)
            counts[index]++
        }
        val values = FloatArray(sums.size) { index ->
            if (counts[index] == 0) Float.NaN else sums[index] / counts[index]
        }
        val edgeMargin = maxOf(1, ceil(minOf(columns, rows) * EDGE_FALLBACK_MARGIN_FRACTION).toInt())
        val internal = mutableListOf<Float>()
        for (row in edgeMargin until rows - edgeMargin) {
            for (column in edgeMargin until columns - edgeMargin) {
                val value = values[row * columns + column]
                if (value.isFinite()) internal += value
            }
        }
        if (internal.isEmpty()) return CoverageUniformityResult(
            false,
            CoverageUniformityMetrics(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f),
            listOf("coverage_sky_region_is_empty"),
            emptyList()
        )
        internal.sort()
        val median = percentile(internal, 0.50f)
        val minimum = internal.first()
        val maximum = internal.last()
        val lowThreshold = median * LOW_COVERAGE_FACTOR
        val low = BooleanArray(values.size)
        var validInternalCells = 0
        var lowCells = 0
        var abruptEdges = 0
        var comparedEdges = 0
        var horizontalFirst = 0f
        var horizontalLast = 0f
        var horizontalCount = 0
        var verticalFirst = 0f
        var verticalLast = 0f
        var verticalCount = 0
        for (row in edgeMargin until rows - edgeMargin) {
            for (column in edgeMargin until columns - edgeMargin) {
                val index = row * columns + column
                val value = values[index]
                if (!value.isFinite()) continue
                validInternalCells++
                if (value < lowThreshold) {
                    low[index] = true
                    lowCells++
                }
                if (column + 1 < columns - edgeMargin) {
                    compare(values[index], values[index + 1], median)?.let {
                        comparedEdges++
                        if (it) abruptEdges++
                    }
                }
                if (row + 1 < rows - edgeMargin) {
                    compare(values[index], values[index + columns], median)?.let {
                        comparedEdges++
                        if (it) abruptEdges++
                    }
                }
                if (column == edgeMargin) horizontalFirst += value
                if (column == columns - edgeMargin - 1) horizontalLast += value
                if (column == edgeMargin) horizontalCount++
                if (row == edgeMargin) verticalFirst += value
                if (row == rows - edgeMargin - 1) verticalLast += value
                if (row == edgeMargin) verticalCount++
            }
        }
        val horizontalGradient = abs(horizontalLast - horizontalFirst) /
            horizontalCount.coerceAtLeast(1) / median.coerceAtLeast(0.01f)
        val verticalGradient = abs(verticalLast - verticalFirst) /
            verticalCount.coerceAtLeast(1) / median.coerceAtLeast(0.01f)
        val gradient = maxOf(horizontalGradient, verticalGradient)
        val lowRatio = lowCells.toFloat() / validInternalCells.coerceAtLeast(1)
        val abruptRatio = abruptEdges.toFloat() / comparedEdges.coerceAtLeast(1)
        val largestRegion = largestConnectedRegion(low, values, columns, rows, edgeMargin).toFloat() /
            validInternalCells.coerceAtLeast(1)
        val wedgeScore = wedgeDiscontinuityScore(low, values, columns, rows, edgeMargin)
        val score = (
            1f - lowRatio * 2.5f - abruptRatio * 2f - largestRegion * 1.5f -
                wedgeScore * 0.45f -
                (gradient - WARNING_GRADIENT).coerceAtLeast(0f) * 0.35f
            ).coerceIn(0f, 1f)
        val metrics = CoverageUniformityMetrics(
            minimum,
            median,
            maximum,
            gradient,
            abruptRatio,
            lowRatio,
            largestRegion,
            score,
            wedgeScore
        )
        val hard = buildList {
            if (lowRatio > MAX_LOW_COVERAGE_RATIO) add("internal_low_coverage_sector_detected")
            if (abruptRatio > MAX_ABRUPT_BOUNDARY_RATIO) add("abrupt_internal_coverage_boundaries")
            if (largestRegion > MAX_CONNECTED_LOW_REGION_RATIO) add("broad_internal_coverage_discontinuity")
            if (wedgeScore > MAX_WEDGE_DISCONTINUITY_SCORE) add("wedge_like_coverage_discontinuity")
            if (score < MIN_UNIFORMITY_SCORE) add("coverage_uniformity_score_too_low")
        }
        val warnings = buildList {
            if (gradient > WARNING_GRADIENT) add("coverage_gradient_is_elevated")
            if (lowRatio > WARNING_LOW_COVERAGE_RATIO) add("low_coverage_area_near_limit")
            if (wedgeScore > WARNING_WEDGE_DISCONTINUITY_SCORE) add("coverage_wedge_score_is_elevated")
        }
        return CoverageUniformityResult(hard.isEmpty(), metrics, hard.distinct(), warnings.distinct())
    }

    private fun compare(first: Float, second: Float, median: Float): Boolean? {
        if (!first.isFinite() || !second.isFinite()) return null
        return abs(first - second) > maxOf(MIN_ABRUPT_DIFFERENCE, median * ABRUPT_DIFFERENCE_FACTOR)
    }

    private fun largestConnectedRegion(
        low: BooleanArray,
        values: FloatArray,
        columns: Int,
        rows: Int,
        edgeMargin: Int
    ): Int {
        val visited = BooleanArray(low.size)
        val queue = IntArray(low.size)
        var largest = 0
        for (row in edgeMargin until rows - edgeMargin) {
            for (column in edgeMargin until columns - edgeMargin) {
                val start = row * columns + column
                if (!low[start] || visited[start] || !values[start].isFinite()) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % columns
                    val y = index / columns
                    intArrayOf(index - 1, index + 1, index - columns, index + columns).forEach { next ->
                        val nextX = next % columns
                        val nextY = next / columns
                        if (
                            next in low.indices && nextX in edgeMargin until columns - edgeMargin &&
                            nextY in edgeMargin until rows - edgeMargin &&
                            abs(nextX - x) + abs(nextY - y) == 1 &&
                            low[next] && !visited[next]
                        ) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                largest = maxOf(largest, tail)
            }
        }
        return largest
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float =
        sorted[((sorted.lastIndex) * fraction).toInt().coerceIn(0, sorted.lastIndex)]

    private fun wedgeDiscontinuityScore(
        low: BooleanArray,
        values: FloatArray,
        columns: Int,
        rows: Int,
        edgeMargin: Int
    ): Float {
        val validByAngle = IntArray(WEDGE_ANGLE_BINS)
        val lowByAngle = IntArray(WEDGE_ANGLE_BINS)
        val centerX = (columns - 1) * 0.5
        val centerY = (rows - 1) * 0.5
        for (row in edgeMargin until rows - edgeMargin) {
            for (column in edgeMargin until columns - edgeMargin) {
                val index = row * columns + column
                if (!values[index].isFinite()) continue
                val angle = atan2(row - centerY, column - centerX) + PI
                val bin = (angle / (2.0 * PI) * WEDGE_ANGLE_BINS).toInt()
                    .coerceIn(0, WEDGE_ANGLE_BINS - 1)
                validByAngle[bin]++
                if (low[index]) lowByAngle[bin]++
            }
        }
        val ratios = validByAngle.indices.map { bin ->
            lowByAngle[bin].toFloat() / validByAngle[bin].coerceAtLeast(1)
        }.sorted()
        val medianRatio = (ratios[ratios.size / 2] + ratios[(ratios.size - 1) / 2]) * 0.5f
        return (ratios.last() - medianRatio).coerceIn(0f, 1f)
    }

    companion object {
        private const val GRID_SIZE = 48
        private const val WEDGE_ANGLE_BINS = 16
        private const val EDGE_FALLBACK_MARGIN_FRACTION = 0.06f
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val LOW_COVERAGE_FACTOR = 0.65f
        private const val MIN_ABRUPT_DIFFERENCE = 0.18f
        private const val ABRUPT_DIFFERENCE_FACTOR = 0.28f
        private const val MAX_LOW_COVERAGE_RATIO = 0.08f
        private const val MAX_ABRUPT_BOUNDARY_RATIO = 0.08f
        private const val MAX_CONNECTED_LOW_REGION_RATIO = 0.12f
        private const val MAX_WEDGE_DISCONTINUITY_SCORE = 0.45f
        private const val MIN_UNIFORMITY_SCORE = 0.65f
        private const val WARNING_LOW_COVERAGE_RATIO = 0.04f
        private const val WARNING_GRADIENT = 0.35f
        private const val WARNING_WEDGE_DISCONTINUITY_SCORE = 0.25f
    }
}
