package com.example.astrophoto

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

data class StarAlignmentResult(
    val dx: Int,
    val dy: Int,
    val applied: Boolean,
    val confidence: Float,
    val referenceStars: Int,
    val candidateStars: Int,
    val matchedStars: Int,
    val method: String,
    val warning: String? = null
)

class StarAlignment(
    private val detector: StarDetector = StarDetector()
) {
    fun align(
        reference: Bitmap,
        candidate: Bitmap,
        roi: AstroRoi,
        sensitivity: StarDetectionSensitivity,
        maxShiftPx: Int = 30,
        aggressive: Boolean = false
    ): StarAlignmentResult {
        val referenceDetection = detector.detect(reference, roi, sensitivity)
        val candidateDetection = detector.detect(candidate, roi, sensitivity)
        val starShift = matchStars(
            referenceDetection.stars,
            candidateDetection.stars,
            maxShiftPx,
            aggressive
        )
        if (starShift != null && starShift.applied) {
            return starShift.copy(
                referenceStars = referenceDetection.stars.size,
                candidateStars = candidateDetection.stars.size
            )
        }

        val fallback = imageSafeAlignment(
            reference = reference,
            candidate = candidate,
            roi = roi,
            maxShiftPx = maxShiftPx,
            aggressive = aggressive
        )
        return fallback.copy(
            referenceStars = referenceDetection.stars.size,
            candidateStars = candidateDetection.stars.size,
            warning = starShift?.warning ?: fallback.warning
        )
    }

    private fun matchStars(
        referenceStars: List<DetectedStar>,
        candidateStars: List<DetectedStar>,
        maxShiftPx: Int,
        aggressive: Boolean
    ): StarAlignmentResult? {
        if (referenceStars.size < 3 || candidateStars.size < 3) {
            return StarAlignmentResult(
                dx = 0,
                dy = 0,
                applied = false,
                confidence = 0f,
                referenceStars = referenceStars.size,
                candidateStars = candidateStars.size,
                matchedStars = 0,
                method = "STAR_SAFE",
                warning = "Мало звёзд для уверенного star alignment"
            )
        }

        val refs = referenceStars.take(90)
        val candidates = candidateStars.take(110)
        val buckets = linkedMapOf<Pair<Int, Int>, Int>()
        refs.forEach { ref ->
            candidates.forEach { candidate ->
                val dx = (candidate.x - ref.x).coerceIn(-maxShiftPx, maxShiftPx)
                val dy = (candidate.y - ref.y).coerceIn(-maxShiftPx, maxShiftPx)
                if (abs(candidate.x - ref.x) <= maxShiftPx &&
                    abs(candidate.y - ref.y) <= maxShiftPx
                ) {
                    val key = dx to dy
                    buckets[key] = (buckets[key] ?: 0) + 1
                }
            }
        }
        val best = buckets.maxByOrNull { it.value } ?: return null
        val bestDx = best.key.first
        val bestDy = best.key.second
        var matched = 0
        var distanceSum = 0f
        refs.forEach { ref ->
            val nearest = candidates.minByOrNull { candidate ->
                abs(candidate.x - ref.x - bestDx) + abs(candidate.y - ref.y - bestDy)
            }
            if (nearest != null) {
                val distance =
                    abs(nearest.x - ref.x - bestDx) +
                        abs(nearest.y - ref.y - bestDy)
                if (distance <= if (aggressive) 5 else 3) {
                    matched++
                    distanceSum += distance
                }
            }
        }
        val averageDistance = if (matched > 0) distanceSum / matched else 99f
        val base = minOf(referenceStars.size, candidateStars.size).coerceAtLeast(1)
        val confidence = (
            matched.toFloat() / base.toFloat() *
                (1f - averageDistance / 12f).coerceIn(0.25f, 1f)
            ).coerceIn(0f, 1f)
        val minMatches = if (aggressive) 3 else 4
        val minConfidence = if (aggressive) 0.18f else 0.25f
        val applied = matched >= minMatches &&
            confidence >= minConfidence &&
            abs(bestDx) <= maxShiftPx &&
            abs(bestDy) <= maxShiftPx
        return StarAlignmentResult(
            dx = if (applied) bestDx else 0,
            dy = if (applied) bestDy else 0,
            applied = applied,
            confidence = confidence,
            referenceStars = referenceStars.size,
            candidateStars = candidateStars.size,
            matchedStars = matched,
            method = if (aggressive) "STAR_AGGRESSIVE" else "STAR_SAFE",
            warning = if (applied) null else "Star alignment неуверенный, сдвиг не применён"
        )
    }

    private fun imageSafeAlignment(
        reference: Bitmap,
        candidate: Bitmap,
        roi: AstroRoi,
        maxShiftPx: Int,
        aggressive: Boolean
    ): StarAlignmentResult {
        val rect = roi.toRect(reference.width, reference.height)
        val step = maxOf(4, minOf(rect.width(), rect.height()) / 140)
        var bestScore = Double.MAX_VALUE
        var zeroScore = Double.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        for (dy in -maxShiftPx..maxShiftPx) {
            for (dx in -maxShiftPx..maxShiftPx) {
                var diff = 0L
                var samples = 0
                var y = rect.top
                while (y < rect.bottom) {
                    val cy = y + dy
                    if (cy in 0 until candidate.height) {
                        var x = rect.left
                        while (x < rect.right) {
                            val cx = x + dx
                            if (cx in 0 until candidate.width) {
                                val a = StarDetector.luminance(reference.getPixel(x, y))
                                val b = StarDetector.luminance(candidate.getPixel(cx, cy))
                                diff += abs(a - b)
                                samples++
                            }
                            x += step
                        }
                    }
                    y += step
                }
                if (samples == 0) continue
                val score = diff.toDouble() / samples
                if (dx == 0 && dy == 0) zeroScore = score
                if (score < bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        if (bestScore == Double.MAX_VALUE) {
            return StarAlignmentResult(
                dx = 0,
                dy = 0,
                applied = false,
                confidence = 0f,
                referenceStars = 0,
                candidateStars = 0,
                matchedStars = 0,
                method = "IMAGE_SAFE",
                warning = "Image alignment не нашёл сдвиг"
            )
        }
        val improvement = if (zeroScore > 0 && zeroScore < Double.MAX_VALUE) {
            ((zeroScore - bestScore) / zeroScore).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val applied = improvement >= if (aggressive) 0.015f else 0.03f
        return StarAlignmentResult(
            dx = if (applied) bestDx else 0,
            dy = if (applied) bestDy else 0,
            applied = applied,
            confidence = improvement,
            referenceStars = 0,
            candidateStars = 0,
            matchedStars = 0,
            method = "IMAGE_SAFE",
            warning = if (applied) {
                "Мало звёзд, использован fallback IMAGE_SAFE"
            } else {
                "Выравнивание неуверенное, кадр добавлен без сдвига"
            }
        )
    }
}
