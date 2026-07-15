package com.example.astrophoto.processing.jpeg.v2.analysis

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import kotlin.math.abs
import kotlin.math.sqrt

data class JpegStarDetectionResult(
    val stars: List<DetectedStar>,
    val background: Float,
    val noise: Float,
    val confidence: Float
)

class JpegStarDetector {
    fun detect(
        image: ArgbPixelImage,
        mask: SkyMask,
        maxStars: Int = 260,
        staticArtifactMask: StaticArtifactMask? = null
    ): JpegStarDetectionResult {
        require(mask.width == image.width && mask.height == image.height)
        require(
            staticArtifactMask == null ||
                staticArtifactMask.width == image.width && staticArtifactMask.height == image.height
        )
        require(maxStars >= 0)
        val histogram = IntArray(256)
        var samples = 0L
        val samplingStep = maxOf(1, minOf(image.width, image.height) / 800)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (mask.contains(x, y)) {
                    histogram[pixelLuminance(image.pixelAt(x, y))]++
                    samples++
                }
                x += samplingStep
            }
            y += samplingStep
        }
        if (samples == 0L || image.width < MIN_DIAMETER || image.height < MIN_DIAMETER) {
            return JpegStarDetectionResult(emptyList(), 0f, 0f, 0f)
        }
        val background = percentile(histogram, samples, 0.50f).toFloat()
        val p84 = percentile(histogram, samples, 0.84f).toFloat()
        val p95 = percentile(histogram, samples, 0.95f).toFloat()
        val noise = maxOf(1.5f, p84 - background, (p95 - background) * 0.32f)
        val threshold = background + maxOf(6f, noise * 2.6f)
        val candidates = mutableListOf<DetectedStar>()

        for (centerY in ANALYSIS_RADIUS until image.height - ANALYSIS_RADIUS) {
            for (centerX in ANALYSIS_RADIUS until image.width - ANALYSIS_RADIUS) {
                if (!mask.contains(centerX, centerY)) continue
                if (staticArtifactMask?.contains(centerX.toFloat(), centerY.toFloat()) == true) continue
                val peak = pixelLuminance(image.pixelAt(centerX, centerY))
                if (peak < threshold || !isDeterministicLocalMaximum(image, mask, centerX, centerY, peak)) {
                    continue
                }
                analyzeCandidate(image, mask, centerX, centerY, background, noise)?.let(candidates::add)
            }
        }

        val ranked = candidates.sortedWith(
            compareByDescending<DetectedStar> { it.confidence }
                .thenByDescending { it.flux }
                .thenBy { it.y }
                .thenBy { it.x }
        )
        val distinct = mutableListOf<DetectedStar>()
        ranked.forEach { candidate ->
            if (distinct.size >= maxStars) return@forEach
            val minimumDistance = maxOf(2f, candidate.width * 0.75f)
            if (distinct.none { kept ->
                    val dx = candidate.x - kept.x
                    val dy = candidate.y - kept.y
                    dx * dx + dy * dy < minimumDistance * minimumDistance
                }
            ) {
                distinct += candidate
            }
        }
        val confidence = (distinct.size / 18f).coerceIn(0f, 1f) *
            (distinct.map { it.confidence }.average().takeIf { it.isFinite() }?.toFloat() ?: 0f)
        return JpegStarDetectionResult(distinct, background, noise, confidence)
    }

    private fun analyzeCandidate(
        image: ArgbPixelImage,
        mask: SkyMask,
        centerX: Int,
        centerY: Int,
        globalBackground: Float,
        noise: Float
    ): DetectedStar? {
        val annulus = mutableListOf<Int>()
        for (dy in -ANALYSIS_RADIUS..ANALYSIS_RADIUS) for (dx in -ANALYSIS_RADIUS..ANALYSIS_RADIUS) {
            val radiusSquared = dx * dx + dy * dy
            val x = centerX + dx
            val y = centerY + dy
            if (radiusSquared in ANNULUS_INNER_SQUARED..ANNULUS_OUTER_SQUARED && mask.contains(x, y)) {
                annulus += pixelLuminance(image.pixelAt(x, y))
            }
        }
        if (annulus.size < MIN_ANNULUS_PIXELS) return null
        annulus.sort()
        val localBackground = annulus[annulus.size / 2].toFloat()
        val peakColor = image.pixelAt(centerX, centerY)
        val peak = pixelLuminance(peakColor).toFloat()
        val contrast = peak - localBackground
        if (contrast < maxOf(6f, noise * 2.2f)) return null
        if (isSingleChannelSpike(peakColor, localBackground, contrast)) return null

        var flux = 0f
        var weightedX = 0f
        var weightedY = 0f
        var support = 0
        var saturated = 0
        val supportThreshold = maxOf(2.5f, noise * 1.15f)
        for (dy in -CORE_RADIUS..CORE_RADIUS) for (dx in -CORE_RADIUS..CORE_RADIUS) {
            if (dx * dx + dy * dy > CORE_RADIUS * CORE_RADIUS) continue
            val x = centerX + dx
            val y = centerY + dy
            if (!mask.contains(x, y)) continue
            val color = image.pixelAt(x, y)
            val signal = (pixelLuminance(color) - localBackground).coerceAtLeast(0f)
            if (signal > supportThreshold) support++
            if (maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF) >= 250) {
                saturated++
            }
            flux += signal
            weightedX += x * signal
            weightedY += y * signal
        }
        if (support !in MIN_SUPPORT_PIXELS..MAX_SUPPORT_PIXELS || flux <= 0f || saturated > 2) return null
        val centroidX = weightedX / flux
        val centroidY = weightedY / flux

        var momentXX = 0f
        var momentYY = 0f
        var momentXY = 0f
        for (dy in -CORE_RADIUS..CORE_RADIUS) for (dx in -CORE_RADIUS..CORE_RADIUS) {
            if (dx * dx + dy * dy > CORE_RADIUS * CORE_RADIUS) continue
            val x = centerX + dx
            val y = centerY + dy
            if (!mask.contains(x, y)) continue
            val signal = (pixelLuminance(image.pixelAt(x, y)) - localBackground).coerceAtLeast(0f)
            val offsetX = x - centroidX
            val offsetY = y - centroidY
            momentXX += signal * offsetX * offsetX
            momentYY += signal * offsetY * offsetY
            momentXY += signal * offsetX * offsetY
        }
        momentXX /= flux
        momentYY /= flux
        momentXY /= flux
        val trace = momentXX + momentYY
        val discriminant = sqrt(maxOf(0f, (momentXX - momentYY) * (momentXX - momentYY) + 4f * momentXY * momentXY))
        val major = maxOf(0f, (trace + discriminant) / 2f)
        val minor = maxOf(0f, (trace - discriminant) / 2f)
        if (major <= 0f) return null
        val width = 2.355f * sqrt((major + minor) / 2f)
        val ellipticity = (1f - sqrt(minor / major.coerceAtLeast(0.0001f))).coerceIn(0f, 1f)
        if (width !in MIN_WIDTH..MAX_WIDTH || ellipticity > MAX_ELLIPTICITY) return null

        val snrScore = (contrast / (noise * 7f + 1f)).coerceIn(0f, 1f)
        val shapeScore = (1f - ellipticity / MAX_ELLIPTICITY).coerceIn(0f, 1f)
        val widthScore = (1f - abs(width - IDEAL_WIDTH) / IDEAL_WIDTH_RANGE).coerceIn(0f, 1f)
        val supportScore = (support / 10f).coerceIn(0f, 1f)
        val confidence = (snrScore * 0.42f + shapeScore * 0.28f + widthScore * 0.18f + supportScore * 0.12f)
            .coerceIn(0f, 1f)
        if (confidence < MIN_CANDIDATE_CONFIDENCE) return null
        return DetectedStar(
            x = centroidX,
            y = centroidY,
            flux = flux,
            localBackground = localBackground.takeIf { it.isFinite() } ?: globalBackground,
            localContrast = contrast,
            width = width,
            ellipticity = ellipticity,
            confidence = confidence
        )
    }

    private fun isDeterministicLocalMaximum(
        image: ArgbPixelImage,
        mask: SkyMask,
        centerX: Int,
        centerY: Int,
        value: Int
    ): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = centerX + dx
            val y = centerY + dy
            if (!mask.contains(x, y)) continue
            val other = pixelLuminance(image.pixelAt(x, y))
            if (other > value || (other == value && (y < centerY || y == centerY && x < centerX))) {
                return false
            }
        }
        return true
    }

    private fun isSingleChannelSpike(color: Int, background: Float, contrast: Float): Boolean {
        val deltas = floatArrayOf(
            (color ushr 16 and 0xFF) - background,
            (color ushr 8 and 0xFF) - background,
            (color and 0xFF) - background
        ).sortedDescending()
        return deltas[0] > maxOf(18f, contrast * 0.8f) &&
            deltas[1] < maxOf(5f, deltas[0] * 0.32f)
    }

    private fun percentile(histogram: IntArray, total: Long, fraction: Float): Int {
        val target = maxOf(1L, (total * fraction).toLong())
        var accumulated = 0L
        histogram.forEachIndexed { value, count ->
            accumulated += count
            if (accumulated >= target) return value
        }
        return 255
    }

    companion object {
        private const val ANALYSIS_RADIUS = 5
        private const val CORE_RADIUS = 4
        private const val MIN_DIAMETER = ANALYSIS_RADIUS * 2 + 1
        private const val ANNULUS_INNER_SQUARED = 16
        private const val ANNULUS_OUTER_SQUARED = 25
        private const val MIN_ANNULUS_PIXELS = 12
        private const val MIN_SUPPORT_PIXELS = 2
        private const val MAX_SUPPORT_PIXELS = 36
        private const val MIN_WIDTH = 0.55f
        private const val MAX_WIDTH = 4.8f
        private const val MAX_ELLIPTICITY = 0.78f
        private const val IDEAL_WIDTH = 2.1f
        private const val IDEAL_WIDTH_RANGE = 2.2f
        private const val MIN_CANDIDATE_CONFIDENCE = 0.18f
    }
}
