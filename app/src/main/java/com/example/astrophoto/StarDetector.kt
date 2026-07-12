package com.example.astrophoto

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    operator fun contains(point: Pair<Int, Int>): Boolean =
        point.first in left until right && point.second in top until bottom
}

data class AstroRoi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toPixelRect(width: Int, height: Int): PixelRect {
        require(width > 0 && height > 0) { "ROI image dimensions must be positive" }
        val safeLeft = left.coerceIn(0f, 0.95f)
        val safeTop = top.coerceIn(0f, 0.95f)
        val safeRight = right.coerceIn(safeLeft + 0.05f, 1f)
        val safeBottom = bottom.coerceIn(safeTop + 0.05f, 1f)
        return PixelRect(
            (safeLeft * width).roundToInt().coerceIn(0, width - 1),
            (safeTop * height).roundToInt().coerceIn(0, height - 1),
            (safeRight * width).roundToInt().coerceIn(1, width),
            (safeBottom * height).roundToInt().coerceIn(1, height)
        )
    }

    fun toRect(width: Int, height: Int): Rect {
        val rect = toPixelRect(width, height)
        return Rect(rect.left, rect.top, rect.right, rect.bottom)
    }

    companion object {
        val Full = AstroRoi(0f, 0f, 1f, 1f)
        val Top70 = AstroRoi(0f, 0f, 1f, 0.70f)
        val Top50 = AstroRoi(0f, 0f, 1f, 0.50f)
        val Center70 = AstroRoi(0.15f, 0.15f, 0.85f, 0.85f)
    }
}

enum class StarDetectionSensitivity { LOW, MEDIUM, HIGH }

data class DetectedStar(
    val x: Int,
    val y: Int,
    val brightness: Int,
    val localContrast: Float,
    val radius: Float,
    val confidence: Float
)

data class StarDetectionResult(
    val stars: List<DetectedStar>,
    val background: Float,
    val noise: Float,
    val roi: PixelRect,
    val confidence: Float
)

class StarDetector {
    fun detect(
        bitmap: Bitmap,
        roi: AstroRoi = AstroRoi.Full,
        sensitivity: StarDetectionSensitivity = StarDetectionSensitivity.MEDIUM,
        maxStars: Int = 260
    ): StarDetectionResult {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return detect(ArgbPixelImage(bitmap.width, bitmap.height, pixels), roi, sensitivity, maxStars)
    }

    fun detect(
        image: ArgbPixelImage,
        roi: AstroRoi = AstroRoi.Full,
        sensitivity: StarDetectionSensitivity = StarDetectionSensitivity.MEDIUM,
        maxStars: Int = 260
    ): StarDetectionResult {
        require(maxStars >= 0) { "Maximum star count must not be negative" }
        val rect = roi.toPixelRect(image.width, image.height)
        val sampleStep = maxOf(1, minOf(rect.width, rect.height) / 700)
        val histogram = IntArray(256)
        var sampleCount = 0L
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                histogram[pixelLuminance(image.pixelAt(x, y))]++
                sampleCount++
                x += sampleStep
            }
            y += sampleStep
        }

        val total = sampleCount.coerceAtLeast(1L)
        val median = percentile(histogram, total, 0.50).toFloat()
        val p84 = percentile(histogram, total, 0.84).toFloat()
        val p96 = percentile(histogram, total, 0.96).toFloat()
        val noise = maxOf(4f, p84 - median, (p96 - median) * 0.35f)
        val multiplier = when (sensitivity) {
            StarDetectionSensitivity.LOW -> 3.4f
            StarDetectionSensitivity.MEDIUM -> 2.45f
            StarDetectionSensitivity.HIGH -> 1.75f
        }
        val threshold = (median + noise * multiplier + 5f).coerceIn(8f, 245f)
        val candidates = mutableListOf<DetectedStar>()
        val margin = maxOf(3, sampleStep * 2)
        y = rect.top + margin
        while (y < rect.bottom - margin) {
            var x = rect.left + margin
            while (x < rect.right - margin) {
                val value = pixelLuminance(image.pixelAt(x, y))
                if (value >= threshold && isLocalMaximum(image, x, y, sampleStep, value)) {
                    val local = localStats(image, x, y, sampleStep, threshold)
                    val contrast = value - local.background
                    if (
                        contrast > noise * 0.9f &&
                        local.brightCount in 1..local.maxBrightCount &&
                        (local.saturatedCount <= 2 || contrast > noise * 4f)
                    ) {
                        val confidence = (
                            contrast / (noise * 5f + 1f) +
                                (255 - abs(value - 180)) / 255f * 0.25f
                            ).coerceIn(0.05f, 1f)
                        candidates += DetectedStar(
                            local.centroidX,
                            local.centroidY,
                            value,
                            contrast,
                            local.radius,
                            confidence
                        )
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val rankedCandidates = candidates.sortedWith(
            compareByDescending<DetectedStar> { it.confidence }
                .thenByDescending { it.localContrast }
                .thenBy { it.y }
                .thenBy { it.x }
        )
        val distinctCandidates = mutableListOf<DetectedStar>()
        for (candidate in rankedCandidates) {
            if (distinctCandidates.size >= maxStars) break
            val duplicate = distinctCandidates.any { kept ->
                abs(candidate.x - kept.x) <= sampleStep && abs(candidate.y - kept.y) <= sampleStep
            }
            if (!duplicate) distinctCandidates += candidate
        }
        val stars = distinctCandidates
            .sortedByDescending { it.brightness }
        val confidence = when {
            stars.size >= 24 -> 1f
            stars.size >= 10 -> 0.75f
            stars.size >= 4 -> 0.45f
            stars.isNotEmpty() -> 0.2f
            else -> 0f
        }
        return StarDetectionResult(stars, median, noise, rect, confidence)
    }

    private data class LocalStats(
        val background: Float,
        val brightCount: Int,
        val saturatedCount: Int,
        val maxBrightCount: Int,
        val radius: Float,
        val centroidX: Int,
        val centroidY: Int
    )

    private fun localStats(
        image: ArgbPixelImage,
        centerX: Int,
        centerY: Int,
        step: Int,
        threshold: Float
    ): LocalStats {
        var sum = 0
        var count = 0
        var brightCount = 0
        var saturatedCount = 0
        var centroidWeight = 0L
        var weightedX = 0L
        var weightedY = 0L
        val radius = maxOf(2, step * 3)
        val maxBrightCount = if (step <= 1) 14 else if (step == 2) 10 else 8
        var y = centerY - radius
        while (y <= centerY + radius) {
            if (y in 0 until image.height) {
                var x = centerX - radius
                while (x <= centerX + radius) {
                    if (x in 0 until image.width) {
                        val value = pixelLuminance(image.pixelAt(x, y))
                        sum += value
                        count++
                        if (value >= threshold) {
                            brightCount++
                            val weight = (value - threshold.toInt() + 1).coerceAtLeast(1)
                            centroidWeight += weight
                            weightedX += x.toLong() * weight
                            weightedY += y.toLong() * weight
                        }
                        if (value >= 248) saturatedCount++
                    }
                    x += step
                }
            }
            y += step
        }
        return LocalStats(
            sum.toFloat() / count.coerceAtLeast(1),
            brightCount,
            saturatedCount,
            maxBrightCount,
            sqrt(brightCount.coerceAtLeast(1).toFloat()),
            if (centroidWeight > 0) (weightedX.toDouble() / centroidWeight).roundToInt() else centerX,
            if (centroidWeight > 0) (weightedY.toDouble() / centroidWeight).roundToInt() else centerY
        )
    }

    private fun isLocalMaximum(
        image: ArgbPixelImage,
        x: Int,
        y: Int,
        step: Int,
        value: Int
    ): Boolean {
        for (dy in -step..step step step) {
            for (dx in -step..step step step) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until image.width || ny !in 0 until image.height) continue
                if (pixelLuminance(image.pixelAt(nx, ny)) > value) return false
            }
        }
        return true
    }

    companion object {
        fun luminance(color: Int): Int = pixelLuminance(color)

        fun percentile(histogram: IntArray, total: Long, percentile: Double): Int {
            val target = (total * percentile).toLong().coerceAtLeast(1L)
            var accumulated = 0L
            histogram.forEachIndexed { index, count ->
                accumulated += count
                if (accumulated >= target) return index
            }
            return histogram.lastIndex
        }
    }
}
