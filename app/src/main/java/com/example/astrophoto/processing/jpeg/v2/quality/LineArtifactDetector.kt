package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

data class LineArtifactMetrics(
    val newLongLineComponents: Int,
    val newLinePixelRatio: Float,
    val directionalConcentration: Float,
    val fanPatternScore: Float,
    val lineArtifactScore: Float
)

data class LineArtifactResult(
    val accepted: Boolean,
    val metrics: LineArtifactMetrics,
    val hardFailureReasons: List<String>,
    val warningReasons: List<String>
)

class LineArtifactDetector {
    fun compare(
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        effectiveSky: AlphaMask
    ): LineArtifactResult {
        if (
            reference.width != cleanStack.width || reference.height != cleanStack.height ||
            reference.width != effectiveSky.width || reference.height != effectiveSky.height
        ) return failed("line_artifact_dimensions_changed")
        val scale = minOf(1f, MAX_ANALYSIS_DIMENSION.toFloat() / maxOf(reference.width, reference.height))
        val width = maxOf(3, (reference.width * scale).toInt())
        val height = maxOf(3, (reference.height * scale).toInt())
        val edges = BooleanArray(width * height)
        val directions = IntArray(DIRECTION_BINS)
        var validSky = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val sourceX = (x.toLong() * reference.width / width).toInt().coerceIn(1, reference.width - 2)
            val sourceY = (y.toLong() * reference.height / height).toInt().coerceIn(1, reference.height - 2)
            if (effectiveSky.alphaAt(sourceX, sourceY) < SKY_ALPHA_THRESHOLD) continue
            validSky++
            val candidateGradient = gradient(cleanStack, sourceX, sourceY)
            val referenceGradient = gradient(reference, sourceX, sourceY)
            if (
                candidateGradient.magnitude < MIN_NEW_EDGE_MAGNITUDE ||
                candidateGradient.magnitude < referenceGradient.magnitude + MIN_EDGE_INCREASE
            ) continue
            edges[y * width + x] = true
            val angle = ((candidateGradient.angle + PI) / (2.0 * PI) * DIRECTION_BINS).toInt()
                .coerceIn(0, DIRECTION_BINS - 1)
            directions[angle]++
        }
        val components = components(edges, width, height)
        val longComponents = components.filter { component ->
            component.size >= MIN_LONG_COMPONENT_PIXELS &&
                maxOf(component.width, component.height) >= MIN_LONG_COMPONENT_SPAN &&
                component.elongation >= MIN_COMPONENT_ELONGATION
        }
        val broadNewLine = longComponents.any { component ->
            maxOf(component.width, component.height) >=
                minOf(width, height) * BROAD_LINE_SPAN_FRACTION
        }
        val longPixels = longComponents.sumOf { it.size }
        val directionTotal = directions.sum().coerceAtLeast(1)
        val directionalConcentration = directions.maxOrNull()!!.toFloat() / directionTotal
        val lineRatio = longPixels.toFloat() / validSky.coerceAtLeast(1)
        val angleGroups = longComponents.map { it.angleBin }.distinct().size
        val fanScore = if (longComponents.size >= MIN_FAN_LINES && angleGroups >= MIN_FAN_DIRECTIONS) {
            (longComponents.size / 6f + angleGroups / 6f).coerceIn(0f, 1f)
        } else {
            0f
        }
        val lineScore = (
            lineRatio * LINE_RATIO_SCALE +
                (directionalConcentration - EXPECTED_DIRECTION_CONCENTRATION).coerceAtLeast(0f) * 0.45f +
                fanScore * 0.55f
            ).coerceIn(0f, 1f)
        val metrics = LineArtifactMetrics(
            longComponents.size,
            lineRatio,
            directionalConcentration,
            fanScore,
            lineScore
        )
        val hard = buildList {
            if (broadNewLine) add("new_strong_line_artifacts_detected")
            if (lineScore > MAX_LINE_ARTIFACT_SCORE) add("new_strong_line_artifacts_detected")
            if (fanScore > MAX_FAN_PATTERN_SCORE) add("new_fan_pattern_detected")
            if (longComponents.size >= MAX_NEW_LONG_LINES) add("too_many_new_long_streaks")
        }
        val warnings = buildList {
            if (lineScore > WARNING_LINE_ARTIFACT_SCORE) add("new_line_artifact_score_elevated")
        }
        return LineArtifactResult(hard.isEmpty(), metrics, hard.distinct(), warnings)
    }

    private data class Gradient(val magnitude: Float, val angle: Double)

    private fun gradient(image: ArgbPixelImage, x: Int, y: Int): Gradient {
        val gx = pixelLuminance(image.pixelAt(x + 1, y)) - pixelLuminance(image.pixelAt(x - 1, y))
        val gy = pixelLuminance(image.pixelAt(x, y + 1)) - pixelLuminance(image.pixelAt(x, y - 1))
        return Gradient(hypot(gx.toFloat(), gy.toFloat()), atan2(gy.toDouble(), gx.toDouble()))
    }

    private data class EdgeComponent(
        val size: Int,
        val width: Int,
        val height: Int,
        val elongation: Float,
        val angleBin: Int
    )

    private fun components(edges: BooleanArray, width: Int, height: Int): List<EdgeComponent> {
        val visited = BooleanArray(edges.size)
        val queue = IntArray(edges.size)
        val result = mutableListOf<EdgeComponent>()
        edges.indices.forEach { start ->
            if (!edges[start] || visited[start]) return@forEach
            var head = 0
            var tail = 0
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            var sumXY = 0.0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                sumX += x
                sumY += y
                sumXX += x.toDouble() * x
                sumYY += y.toDouble() * y
                sumXY += x.toDouble() * y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (edges[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1
            val count = tail.coerceAtLeast(1).toDouble()
            val meanX = sumX / count
            val meanY = sumY / count
            val covarianceX = (sumXX / count - meanX * meanX).coerceAtLeast(0.0)
            val covarianceY = (sumYY / count - meanY * meanY).coerceAtLeast(0.0)
            val covarianceXY = sumXY / count - meanX * meanY
            val trace = covarianceX + covarianceY
            val discriminant = sqrt(
                ((covarianceX - covarianceY) * (covarianceX - covarianceY) +
                    4.0 * covarianceXY * covarianceXY).coerceAtLeast(0.0)
            )
            val major = ((trace + discriminant) * 0.5).coerceAtLeast(0.0)
            val minor = ((trace - discriminant) * 0.5).coerceAtLeast(0.0)
            var angle = 0.5 * atan2(2.0 * covarianceXY, covarianceX - covarianceY)
            if (angle < 0.0) angle += PI
            result += EdgeComponent(
                tail,
                componentWidth,
                componentHeight,
                sqrt((major + 0.01) / (minor + 0.01)).toFloat(),
                (angle / PI * DIRECTION_BINS).toInt().coerceIn(0, DIRECTION_BINS - 1)
            )
        }
        return result
    }

    private fun failed(reason: String) = LineArtifactResult(
        false,
        LineArtifactMetrics(0, 1f, 1f, 1f, 1f),
        listOf(reason),
        emptyList()
    )

    companion object {
        private const val MAX_ANALYSIS_DIMENSION = 640
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val MIN_NEW_EDGE_MAGNITUDE = 28f
        private const val MIN_EDGE_INCREASE = 16f
        private const val DIRECTION_BINS = 12
        private const val MIN_LONG_COMPONENT_PIXELS = 10
        private const val MIN_LONG_COMPONENT_SPAN = 10
        private const val MIN_COMPONENT_ELONGATION = 2.5f
        private const val BROAD_LINE_SPAN_FRACTION = 0.35f
        private const val MIN_FAN_LINES = 3
        private const val MIN_FAN_DIRECTIONS = 2
        private const val MAX_NEW_LONG_LINES = 5
        private const val EXPECTED_DIRECTION_CONCENTRATION = 0.35f
        private const val LINE_RATIO_SCALE = 8f
        private const val MAX_LINE_ARTIFACT_SCORE = 0.30f
        private const val MAX_FAN_PATTERN_SCORE = 0.35f
        private const val WARNING_LINE_ARTIFACT_SCORE = 0.18f
    }
}
