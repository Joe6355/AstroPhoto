package com.joe6355.astrophoto.processing.jpeg.v2.quality

import com.joe6355.astrophoto.ArgbPixelImage
import com.joe6355.astrophoto.pixelLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
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
) {
    fun combinedWith(other: LineArtifactResult) = LineArtifactResult(
        accepted && other.accepted,
        LineArtifactMetrics(maxOf(metrics.newLongLineComponents, other.metrics.newLongLineComponents),
            maxOf(metrics.newLinePixelRatio, other.metrics.newLinePixelRatio),
            maxOf(metrics.directionalConcentration, other.metrics.directionalConcentration),
            maxOf(metrics.fanPatternScore, other.metrics.fanPatternScore),
            maxOf(metrics.lineArtifactScore, other.metrics.lineArtifactScore)),
        (hardFailureReasons + other.hardFailureReasons).distinct(),
        (warningReasons + other.warningReasons).distinct()
    )

    fun constrain(decision: QualityGateDecision) = decision.copy(
        accepted = decision.accepted && accepted,
        hardFailureReasons = (decision.hardFailureReasons + hardFailureReasons).distinct(),
        warningReasons = (decision.warningReasons + warningReasons).distinct()
    )
}

class LineArtifactDetector(private val diagnostic: (String) -> Unit = {}) {
    /** Independent full-resolution image evidence: does not consume registration/retention scores. */
    fun compareStarNeighborhoods(
        reference: FileBackedImage,
        candidate: FileBackedImage,
        stars: List<DetectedStar>,
        cancellationCheck: () -> Unit = {}
    ): LineArtifactResult = FileBackedImageReader(reference, cachedRows = 2).use { first ->
        FileBackedImageReader(candidate, cachedRows = 2).use { second ->
            compareStarNeighborhoods(first, second, stars, cancellationCheck)
        }
    }

    fun compareStarNeighborhoods(
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        stars: List<DetectedStar>,
        cancellationCheck: () -> Unit = {}
    ): LineArtifactResult {
        if (reference.width != candidate.width || reference.height != candidate.height) {
            return failed("star_neighborhood_dimensions_changed")
        }
        val radius = 32
        val side = radius * 2 + 1
        var evaluated = 0
        var affected = 0
        var lineCount = 0
        var linePixels = 0
        val ghostDirections = IntArray(DIRECTION_BINS)
        val observed = mutableListOf<Pair<Float, Float>>()
        for (star in stars.distinctBy { it.x.toInt() to it.y.toInt() }
            .sortedWith(compareBy<DetectedStar> { it.y }.thenBy { it.x }).take(256)) {
            cancellationCheck()
            val cx = star.x.toInt()
            val cy = star.y.toInt()
            if (cx < radius + 1 || cy < radius + 1 || cx + radius + 1 >= reference.width ||
                cy + radius + 1 >= reference.height) continue
            val before = FloatArray(side * side)
            val after = FloatArray(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                before[y * side + x] = pixelLuminance(reference.argbAt(cx + x - radius, cy + y - radius)).toFloat()
                after[y * side + x] = pixelLuminance(candidate.argbAt(cx + x - radius, cy + y - radius)).toFloat()
            }
            fun median(values: FloatArray): Float = values.sortedArray()[values.size / 2]
            val bgBefore = median(before)
            val bgAfter = median(after)
            val noise = maxOf(0.5f, median(after.map { abs(it - bgAfter) }.toFloatArray()) * 1.4826f)
            val core = maxOf(3, kotlin.math.ceil(star.width * 1.8f).toInt()).coerceAtMost(7)
            var peakBefore = 0f
            var peakAfter = 0f
            for (dy in -core..core) for (dx in -core..core) if (dx * dx + dy * dy <= core * core) {
                peakBefore = maxOf(peakBefore, before[(radius + dy) * side + radius + dx] - bgBefore)
                peakAfter = maxOf(peakAfter, after[(radius + dy) * side + radius + dx] - bgAfter)
            }
            if (peakBefore < 4f || peakAfter < 4f) continue
            evaluated++
            val gain = (peakAfter / peakBefore).coerceIn(0.25f, 8f)
            // A bright preserved core must not hide a much fainter coherent trail nearby.
            val threshold = maxOf(2f, noise * 3f)
            val edges = BooleanArray(side * side)
            for (y in 1 until side - 1) for (x in 1 until side - 1) {
                val dx = x - radius
                val dy = y - radius
                if (dx * dx + dy * dy <= (core + 2) * (core + 2)) continue
                val signal = after[y * side + x] - bgAfter
                if (signal < threshold) continue
                var existing = 0f
                // One-pixel tolerance protects existing faint stars against sharpening/decoder differences.
                for (oy in -1..1) for (ox in -1..1) {
                    existing = maxOf(existing, before[(y + oy) * side + x + ox] - bgBefore)
                }
                edges[y * side + x] = signal > existing * gain * 2f + maxOf(2f, noise * 3f)
            }
            var starAffected = false
            val directionsForStar = hashSetOf<Int>()
            for (component in components(edges, side, side)) {
                if (component.size < 3) continue
                val x = cx + component.centerX - radius
                val y = cy + component.centerY - radius
                if (observed.any { hypot(it.first - x, it.second - y) < 3f }) continue
                observed += x to y
                diagnostic("star=${star.x},${star.y} component=$component offset=${x-star.x},${y-star.y}")
                val isLine = component.size >= 4 && maxOf(component.width, component.height) >= 4 &&
                    component.elongation >= 2f
                if (isLine) { lineCount++; linePixels += component.size; starAffected = true }
                // Require the direction to repeat at distinct stars, not many components at one star.
                val direction = ((atan2((y - star.y).toDouble(), (x - star.x).toDouble()) + PI) /
                    (2 * PI) * DIRECTION_BINS).toInt().coerceIn(0, DIRECTION_BINS - 1)
                directionsForStar += direction
            }
            directionsForStar.forEach { ghostDirections[it]++ }
            if (starAffected) affected++
        }
        val repeated = ghostDirections.maxOrNull() ?: 0
        val fraction = affected.toFloat() / evaluated.coerceAtLeast(1)
        val hard = buildList {
            if (affected >= 3 && fraction >= 0.05f) add("new_stellar_streaks_in_full_resolution")
            if (repeated >= 4 && repeated.toFloat() / evaluated.coerceAtLeast(1) >= 0.10f) {
                add("repeated_new_stellar_companions_in_full_resolution")
            }
        }
        return LineArtifactResult(hard.isEmpty(),
            LineArtifactMetrics(lineCount, linePixels.toFloat() / (evaluated.coerceAtLeast(1) * side * side),
                repeated.toFloat() / observed.size.coerceAtLeast(1),
                0f, if (hard.isEmpty()) fraction else 1f), hard,
            buildList {
                if (evaluated < 3) add("insufficient_stars_for_independent_artifact_check")
                if (affected > 0 && hard.isEmpty()) add("isolated_new_stellar_structure")
            })
    }

    fun compare(
        reference: ArgbPixelImage,
        cleanStack: ArgbPixelImage,
        effectiveSky: AlphaMask
    ): LineArtifactResult = compareSources(
        IntArrayPixelSource(reference.width, reference.height, reference.pixels),
        IntArrayPixelSource(cleanStack.width, cleanStack.height, cleanStack.pixels),
        object : AlphaPixelSource {
            override val width = effectiveSky.width
            override val height = effectiveSky.height
            override fun alphaAt(x: Int, y: Int) = effectiveSky.alphaAt(x, y)
        }
    )

    fun compare(
        reference: FileBackedImage,
        cleanStack: FileBackedImage,
        effectiveSky: FileBackedFloatPlane
    ): LineArtifactResult = FileBackedImageReader(reference, cachedRows = 5).use { first ->
        FileBackedImageReader(cleanStack, cachedRows = 5).use { second ->
            FileBackedFloatPlaneReader(effectiveSky, cachedRows = 5).use { alpha ->
                compareSources(first, second, alpha)
            }
        }
    }

    private fun compareSources(
        reference: ArgbPixelSource,
        cleanStack: ArgbPixelSource,
        effectiveSky: AlphaPixelSource
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

    private fun gradient(image: ArgbPixelSource, x: Int, y: Int): Gradient {
        val gx = pixelLuminance(image.argbAt(x + 1, y)) - pixelLuminance(image.argbAt(x - 1, y))
        val gy = pixelLuminance(image.argbAt(x, y + 1)) - pixelLuminance(image.argbAt(x, y - 1))
        return Gradient(hypot(gx.toFloat(), gy.toFloat()), atan2(gy.toDouble(), gx.toDouble()))
    }

    private data class EdgeComponent(
        val size: Int,
        val width: Int,
        val height: Int,
        val elongation: Float,
        val angleBin: Int,
        val centerX: Float,
        val centerY: Float
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
                (angle / PI * DIRECTION_BINS).toInt().coerceIn(0, DIRECTION_BINS - 1),
                meanX.toFloat(), meanY.toFloat()
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
