package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

internal enum class ExperimentalStarStrengthVariant(
    val residualStrength: Float,
    val maximumDetailGain: Float
) {
    CURRENT(residualStrength = 1.00f, maximumDetailGain = 1.75f),
    MEDIUM(residualStrength = 1.50f, maximumDetailGain = 2.50f),
    STRONG(residualStrength = 2.25f, maximumDetailGain = 3.25f);

    companion object {
        val PRODUCTION_SELECTED = MEDIUM
    }
}

/** Fixed production math shared by the in-memory oracle and active file-backed pipeline. */
internal object ExperimentalStarEnhancementSupport {
    const val MIN_ANNULUS_SAMPLES = 12
    const val MIN_STAR_SUPPORT = 3
    const val MAX_STAR_SUPPORT = 24
    const val MAX_ENHANCED_VALUE = 0.985f
    const val MIN_CORE_DETAIL_FRACTION = 0.18f
    const val DEFECT_AFFECTED_THRESHOLD = 0f
    const val BACKGROUND_ALPHA_THRESHOLD = 0.50f

    fun validShape(star: DetectedStar): Boolean =
        star.confidence >= 0.32f &&
            star.width in 0.65f..4.4f &&
            star.ellipticity <= 0.62f &&
            star.localContrast > 0f

    fun coreRadius(width: Float): Int =
        ceil(maxOf(1f, width * 0.55f)).toInt().coerceAtMost(3)

    fun noiseFloor(statistics: SkyStatisticsResult): Float =
        maxOf(1f / 8191f, statistics.luminanceMad * 0.75f)

    fun supportThreshold(centerDetail: Float, noiseFloor: Float): Float =
        maxOf(centerDetail * MIN_CORE_DETAIL_FRACTION, noiseFloor * 0.55f)

    fun discoverCompactCandidates(
        width: Int,
        height: Int,
        knownStars: List<DetectedStar>,
        statistics: SkyStatisticsResult,
        colorAt: (x: Int, y: Int) -> Int,
        alphaAt: (x: Int, y: Int) -> Float,
        defectAt: (x: Int, y: Int) -> Float
    ): List<DetectedStar> {
        if (width < DISCOVERY_MARGIN * 2 + 1 || height < DISCOVERY_MARGIN * 2 + 1) return emptyList()
        val noise = noiseFloor(statistics)
        val candidates = mutableListOf<ScoredCandidate>()
        for (y in DISCOVERY_MARGIN until height - DISCOVERY_MARGIN) {
            for (x in DISCOVERY_MARGIN until width - DISCOVERY_MARGIN) {
                if (alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD || defectAt(x, y) > 0f) continue
                val centerColor = colorAt(x, y)
                val center = linearLuminance(centerColor)
                if (center - statistics.luminanceMedian <= noise) continue
                var localMaximum = true
                var strictlyAboveNeighbor = false
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val neighbor = linearLuminance(colorAt(x + dx, y + dy))
                    if (neighbor > center) localMaximum = false
                    if (neighbor < center) strictlyAboveNeighbor = true
                }
                if (!localMaximum || !strictlyAboveNeighbor) continue
                if (knownStars.any { star ->
                        val dx = star.x - x
                        val dy = star.y - y
                        dx * dx + dy * dy <= KNOWN_STAR_EXCLUSION_RADIUS_SQUARED
                    }
                ) continue
                val background = fixedAnnulusMedian(
                    x,
                    y,
                    colorAt,
                    alphaAt,
                    defectAt
                )
                if (!background.isFinite()) continue
                val detail = center - background
                if (detail <= noise || isSingleChannelSpike(centerColor, background, detail)) continue
                var support = 0
                var weightSum = 0f
                var xx = 0f
                var yy = 0f
                var xy = 0f
                val threshold = supportThreshold(detail, noise)
                for (dy in -DISCOVERY_CORE_RADIUS..DISCOVERY_CORE_RADIUS) {
                    for (dx in -DISCOVERY_CORE_RADIUS..DISCOVERY_CORE_RADIUS) {
                        if (dx * dx + dy * dy > DISCOVERY_CORE_RADIUS * DISCOVERY_CORE_RADIUS) continue
                        val px = x + dx
                        val py = y + dy
                        if (alphaAt(px, py) < STATISTICS_ALPHA_THRESHOLD || defectAt(px, py) > 0f) continue
                        val residual = linearLuminance(colorAt(px, py)) - background
                        if (residual < threshold) continue
                        support++
                        weightSum += residual
                        xx += residual * dx * dx
                        yy += residual * dy * dy
                        xy += residual * dx * dy
                    }
                }
                if (support !in MIN_STAR_SUPPORT..MAX_STAR_SUPPORT || weightSum <= 0f) continue
                val oppositePairs = DISCOVERY_DIRECTIONS.count { (dx, dy) ->
                    linearLuminance(colorAt(x + dx, y + dy)) - background >= threshold &&
                        linearLuminance(colorAt(x - dx, y - dy)) - background >= threshold
                }
                if (oppositePairs == 0) continue
                val momentX = xx / weightSum
                val momentY = yy / weightSum
                val momentXY = xy / weightSum
                val trace = momentX + momentY
                val discriminant = sqrt(
                    ((momentX - momentY) * (momentX - momentY) + 4f * momentXY * momentXY)
                        .coerceAtLeast(0f)
                )
                val maximumMoment = (trace + discriminant) * 0.5f
                val minimumMoment = (trace - discriminant) * 0.5f
                if (maximumMoment <= 0f || minimumMoment < 0f) continue
                val ellipticity = 1f - sqrt((minimumMoment / maximumMoment).coerceIn(0f, 1f))
                if (ellipticity > MAX_DISCOVERY_ELLIPTICITY) continue
                val confidence = (
                    0.45f + minOf(0.30f, detail / (noise * 8f)) +
                        minOf(0.20f, support / 30f) - ellipticity * 0.10f
                ).coerceIn(MIN_DISCOVERY_CONFIDENCE, 0.95f)
                candidates += ScoredCandidate(
                    score = detail / noise + support * 0.05f - ellipticity,
                    star = DetectedStar(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        flux = weightSum,
                        localBackground = background,
                        localContrast = detail,
                        width = DISCOVERY_PSF_WIDTH,
                        ellipticity = ellipticity,
                        confidence = confidence
                    )
                )
            }
        }
        return candidates
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenBy { it.star.y }
                    .thenBy { it.star.x }
            )
            .take(MAX_DISCOVERED_CANDIDATES)
            .map { it.star }
    }

    fun supportWeight(
        dx: Int,
        dy: Int,
        radius: Int,
        localDetail: Float,
        centerDetail: Float,
        noiseFloor: Float,
        foregroundAlpha: Float,
        defectAffected: Float
    ): Float {
        if (
            localDetail <= 0f || foregroundAlpha <= OPERATION_ALPHA_THRESHOLD ||
            defectAffected > DEFECT_AFFECTED_THRESHOLD
        ) return 0f
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared > radius * radius) return 0f
        val noiseSupport = smoothStep(noiseFloor, noiseFloor * 2.2f, localDetail)
        val psfSignalSupport = smoothStep(
            centerDetail * MIN_CORE_DETAIL_FRACTION,
            centerDetail * 0.55f,
            localDetail
        )
        val radialPsfSupport = 1f - distanceSquared.toFloat() / (radius * radius + 1f)
        return (noiseSupport * psfSignalSupport * radialPsfSupport *
            sqrt(foregroundAlpha.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    fun strengthAdjustedSupportWeight(supportWeight: Float, residualStrength: Float): Float =
        supportWeight.coerceIn(0f, 1f).pow(
            1f / residualStrength.coerceAtLeast(1f).let { it * it }
        )

    fun brightProtection(statistics: SkyStatisticsResult, centerLuminance: Float): Float =
        1f - smoothStep(
            maxOf(statistics.starBrightnessMedian, statistics.highPercentile),
            maxOf(statistics.brightStarCorePercentile, statistics.estimatedSafeWhitePoint),
            centerLuminance
        )

    fun residualGain(
        profileStrength: Float,
        residualStrength: Float,
        maximumDetailGain: Float,
        minimumContrastGain: Float,
        confidence: Float
    ): Float {
        val maximumResidualGain = (maximumDetailGain - 1f).coerceAtLeast(0f)
        return (
            maxOf(
                minimumContrastGain,
                maximumResidualGain * profileStrength.coerceIn(0f, 1f) *
                    confidence.coerceIn(0f, 1f)
            ) * residualStrength
        ).coerceAtMost(maximumResidualGain)
    }

    fun isSingleChannelSpike(color: Int, background: Float, detail: Float): Boolean {
        val deltas = floatArrayOf(
            linearChannel(color, 16) - background,
            linearChannel(color, 8) - background,
            linearChannel(color, 0) - background
        ).sortedDescending()
        return deltas[0] > maxOf(0.06f, detail * 0.8f) &&
            deltas[1] < maxOf(0.012f, deltas[0] * 0.28f)
    }

    private fun fixedAnnulusMedian(
        centerX: Int,
        centerY: Int,
        colorAt: (x: Int, y: Int) -> Int,
        alphaAt: (x: Int, y: Int) -> Float,
        defectAt: (x: Int, y: Int) -> Float
    ): Float {
        val values = FloatArray((DISCOVERY_OUTER_RADIUS * 2 + 1) * (DISCOVERY_OUTER_RADIUS * 2 + 1))
        var count = 0
        for (dy in -DISCOVERY_OUTER_RADIUS..DISCOVERY_OUTER_RADIUS) {
            for (dx in -DISCOVERY_OUTER_RADIUS..DISCOVERY_OUTER_RADIUS) {
                val distanceSquared = dx * dx + dy * dy
                if (
                    distanceSquared < DISCOVERY_INNER_RADIUS * DISCOVERY_INNER_RADIUS ||
                    distanceSquared > DISCOVERY_OUTER_RADIUS * DISCOVERY_OUTER_RADIUS
                ) continue
                val x = centerX + dx
                val y = centerY + dy
                if (alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD || defectAt(x, y) > 0f) continue
                values[count++] = linearLuminance(colorAt(x, y))
            }
        }
        if (count < MIN_ANNULUS_SAMPLES) return Float.NaN
        values.sort(0, count)
        return values[count / 2]
    }

    private data class ScoredCandidate(val score: Float, val star: DetectedStar)

    private val DISCOVERY_DIRECTIONS = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    private const val DISCOVERY_PSF_WIDTH = 2.85f
    private const val DISCOVERY_CORE_RADIUS = 2
    private const val DISCOVERY_INNER_RADIUS = 4
    private const val DISCOVERY_OUTER_RADIUS = 7
    private const val DISCOVERY_MARGIN = DISCOVERY_OUTER_RADIUS
    private const val MAX_DISCOVERY_ELLIPTICITY = 0.68f
    private const val MIN_DISCOVERY_CONFIDENCE = 0.48f
    private const val KNOWN_STAR_EXCLUSION_RADIUS_SQUARED = 6.25f
    private const val MAX_DISCOVERED_CANDIDATES = 512
}
