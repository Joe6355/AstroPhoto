package com.example.astrophoto

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AstroRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRect(width: Int, height: Int): Rect {
        val safeLeft = left.coerceIn(0f, 0.95f)
        val safeTop = top.coerceIn(0f, 0.95f)
        val safeRight = right.coerceIn(safeLeft + 0.05f, 1f)
        val safeBottom = bottom.coerceIn(safeTop + 0.05f, 1f)
        return Rect(
            (safeLeft * width).roundToInt().coerceIn(0, width - 1),
            (safeTop * height).roundToInt().coerceIn(0, height - 1),
            (safeRight * width).roundToInt().coerceIn(1, width),
            (safeBottom * height).roundToInt().coerceIn(1, height)
        )
    }

    companion object {
        val Full = AstroRoi(0f, 0f, 1f, 1f)
        val Top70 = AstroRoi(0f, 0f, 1f, 0.70f)
        val Top50 = AstroRoi(0f, 0f, 1f, 0.50f)
        val Center70 = AstroRoi(0.15f, 0.15f, 0.85f, 0.85f)
    }
}

enum class StarDetectionSensitivity {
    LOW,
    MEDIUM,
    HIGH
}

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
    val roi: Rect,
    val confidence: Float
)

class StarDetector {
    fun detect(
        bitmap: Bitmap,
        roi: AstroRoi = AstroRoi.Full,
        sensitivity: StarDetectionSensitivity = StarDetectionSensitivity.MEDIUM,
        maxStars: Int = 260
    ): StarDetectionResult {
        val rect = roi.toRect(bitmap.width, bitmap.height)
        val sampleStep = maxOf(1, minOf(rect.width(), rect.height()) / 700)
        val histogram = IntArray(256)
        val row = IntArray(rect.width())
        var sampleCount = 0L

        var y = rect.top
        while (y < rect.bottom) {
            bitmap.getPixels(row, 0, rect.width(), rect.left, y, rect.width(), 1)
            var x = 0
            while (x < rect.width()) {
                histogram[luminance(row[x])]++
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
                val value = luminance(bitmap.getPixel(x, y))
                if (value >= threshold && isLocalMaximum(bitmap, x, y, sampleStep, value)) {
                    val local = localStats(bitmap, x, y, sampleStep, threshold)
                    val contrast = value - local.background
                    val isolatedEnough = local.brightCount in 1..local.maxBrightCount
                    val notSaturatedBlob = local.saturatedCount <= 2 || contrast > noise * 4f
                    if (contrast > noise * 0.9f && isolatedEnough && notSaturatedBlob) {
                        val confidence = (
                            contrast / (noise * 5f + 1f) +
                                (255 - abs(value - 180)) / 255f * 0.25f
                            ).coerceIn(0.05f, 1f)
                        candidates += DetectedStar(
                            x = x,
                            y = y,
                            brightness = value,
                            localContrast = contrast,
                            radius = local.radius,
                            confidence = confidence
                        )
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val stars = candidates
            .sortedWith(
                compareByDescending<DetectedStar> { it.confidence }
                    .thenByDescending { it.localContrast }
            )
            .take(maxStars)
            .sortedByDescending { it.brightness }
        val confidence = when {
            stars.size >= 24 -> 1f
            stars.size >= 10 -> 0.75f
            stars.size >= 4 -> 0.45f
            stars.isNotEmpty() -> 0.2f
            else -> 0f
        }
        return StarDetectionResult(
            stars = stars,
            background = median,
            noise = noise,
            roi = rect,
            confidence = confidence
        )
    }

    private data class LocalStats(
        val background: Float,
        val brightCount: Int,
        val saturatedCount: Int,
        val maxBrightCount: Int,
        val radius: Float
    )

    private fun localStats(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        step: Int,
        threshold: Float
    ): LocalStats {
        var sum = 0
        var count = 0
        var brightCount = 0
        var saturatedCount = 0
        val radius = maxOf(2, step * 3)
        val maxBrightCount = when {
            step <= 1 -> 14
            step == 2 -> 10
            else -> 8
        }
        var y = centerY - radius
        while (y <= centerY + radius) {
            if (y in 0 until bitmap.height) {
                var x = centerX - radius
                while (x <= centerX + radius) {
                    if (x in 0 until bitmap.width) {
                        val value = luminance(bitmap.getPixel(x, y))
                        sum += value
                        count++
                        if (value >= threshold) brightCount++
                        if (value >= 248) saturatedCount++
                    }
                    x += step
                }
            }
            y += step
        }
        val estimatedRadius = sqrt(brightCount.coerceAtLeast(1).toFloat())
        return LocalStats(
            background = sum.toFloat() / count.coerceAtLeast(1),
            brightCount = brightCount,
            saturatedCount = saturatedCount,
            maxBrightCount = maxBrightCount,
            radius = estimatedRadius
        )
    }

    private fun isLocalMaximum(
        bitmap: Bitmap,
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
                if (nx !in 0 until bitmap.width || ny !in 0 until bitmap.height) continue
                if (luminance(bitmap.getPixel(nx, ny)) > value) return false
            }
        }
        return true
    }

    companion object {
        fun luminance(color: Int): Int {
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            return (red * 77 + green * 150 + blue * 29) ushr 8
        }

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
