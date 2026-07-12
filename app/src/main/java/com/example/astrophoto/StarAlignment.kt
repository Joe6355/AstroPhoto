package com.example.astrophoto

import android.graphics.Bitmap
import kotlin.math.abs

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
    ): StarAlignmentResult = align(
        reference.toArgbPixelImage(),
        candidate.toArgbPixelImage(),
        roi,
        sensitivity,
        maxShiftPx,
        aggressive
    )

    fun align(
        reference: ArgbPixelImage,
        candidate: ArgbPixelImage,
        roi: AstroRoi,
        sensitivity: StarDetectionSensitivity,
        maxShiftPx: Int = 30,
        aggressive: Boolean = false
    ): StarAlignmentResult {
        require(reference.width == candidate.width && reference.height == candidate.height) {
            "Alignment image dimensions must match"
        }
        require(maxShiftPx >= 0) { "Maximum alignment shift must not be negative" }
        val referenceDetection = detector.detect(reference, roi, sensitivity)
        val candidateDetection = detector.detect(candidate, roi, sensitivity)
        val starShift = matchDetectedStars(
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

        val fallback = imageSafeAlignment(reference, candidate, roi, maxShiftPx, aggressive)
        return fallback.copy(
            referenceStars = referenceDetection.stars.size,
            candidateStars = candidateDetection.stars.size,
            warning = starShift?.warning ?: fallback.warning
        )
    }

    internal fun matchDetectedStars(
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

        val byMatchPriority = compareByDescending<DetectedStar> { it.brightness }
            .thenByDescending { it.confidence }
            .thenBy { it.y }
            .thenBy { it.x }
        val byPosition = compareBy<DetectedStar> { it.x }.thenBy { it.y }
        val refs = referenceStars.sortedWith(byMatchPriority).take(90).sortedWith(byPosition)
        val candidates = candidateStars.sortedWith(byMatchPriority).take(110)
            .sortedWith(byPosition)
        val buckets = linkedMapOf<Pair<Int, Int>, Int>()
        refs.forEach { ref ->
            candidates.forEach { candidate ->
                val rawDx = candidate.x - ref.x
                val rawDy = candidate.y - ref.y
                if (abs(rawDx) <= maxShiftPx && abs(rawDy) <= maxShiftPx) {
                    val key = rawDx to rawDy
                    buckets[key] = (buckets[key] ?: 0) + 1
                }
            }
        }
        val best = buckets.entries.maxWithOrNull(
            compareBy<Map.Entry<Pair<Int, Int>, Int>> { it.value }
                .thenBy { -abs(it.key.first) - abs(it.key.second) }
                .thenBy { -it.key.second }
                .thenBy { -it.key.first }
        ) ?: return null
        val bestDx = best.key.first
        val bestDy = best.key.second
        val tolerance = if (aggressive) 5 else 3
        val usedCandidates = BooleanArray(candidates.size)
        var matched = 0
        var distanceSum = 0f
        refs.forEach { ref ->
            val nearest = candidates.indices
                .asSequence()
                .filterNot { usedCandidates[it] }
                .map { index ->
                    val candidate = candidates[index]
                    val distance = abs(candidate.x - ref.x - bestDx) +
                        abs(candidate.y - ref.y - bestDy)
                    index to distance
                }
                .filter { it.second <= tolerance }
                .minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            if (nearest != null) {
                usedCandidates[nearest.first] = true
                matched++
                distanceSum += nearest.second
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
        val applied = matched >= minMatches && confidence >= minConfidence
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

    internal fun imageSafeAlignment(
        reference: ArgbPixelImage,
        candidate: ArgbPixelImage,
        roi: AstroRoi,
        maxShiftPx: Int,
        aggressive: Boolean
    ): StarAlignmentResult {
        require(reference.width == candidate.width && reference.height == candidate.height) {
            "Alignment image dimensions must match"
        }
        val rect = roi.toPixelRect(reference.width, reference.height)
        val step = maxOf(4, minOf(rect.width, rect.height) / 140)
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
                                val a = pixelLuminance(reference.pixelAt(x, y))
                                val b = pixelLuminance(candidate.pixelAt(cx, cy))
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
                val distance = abs(dx) + abs(dy)
                val bestDistance = abs(bestDx) + abs(bestDy)
                if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        if (bestScore == Double.MAX_VALUE) {
            return failedImageAlignment("Image alignment не нашёл сдвиг")
        }
        val improvement = if (zeroScore > 0 && zeroScore < Double.MAX_VALUE) {
            ((zeroScore - bestScore) / zeroScore).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val confidence = minOf(
            improvement,
            imageCorrelation(reference, candidate, rect, bestDx, bestDy).toFloat()
        )
        val applied = confidence >= if (aggressive) 0.015f else 0.03f
        return StarAlignmentResult(
            dx = if (applied) bestDx else 0,
            dy = if (applied) bestDy else 0,
            applied = applied,
            confidence = confidence,
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

    private fun imageCorrelation(
        reference: ArgbPixelImage,
        candidate: ArgbPixelImage,
        rect: PixelRect,
        dx: Int,
        dy: Int
    ): Double {
        var count = 0L
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        val step = maxOf(1, minOf(rect.width, rect.height) / 200)
        var y = rect.top
        while (y < rect.bottom) {
            val candidateY = y + dy
            if (candidateY in 0 until candidate.height) {
                var x = rect.left
                while (x < rect.right) {
                    val candidateX = x + dx
                    if (candidateX in 0 until candidate.width) {
                        val a = pixelLuminance(reference.pixelAt(x, y)).toDouble()
                        val b = pixelLuminance(candidate.pixelAt(candidateX, candidateY)).toDouble()
                        count++
                        sumA += a
                        sumB += b
                        sumAA += a * a
                        sumBB += b * b
                        sumAB += a * b
                    }
                    x += step
                }
            }
            y += step
        }
        if (count < 2) return 0.0
        val covariance = count * sumAB - sumA * sumB
        val varianceA = count * sumAA - sumA * sumA
        val varianceB = count * sumBB - sumB * sumB
        val denominator = kotlin.math.sqrt(varianceA * varianceB)
        if (denominator <= 0.0) return 0.0
        val correlation = (covariance / denominator).coerceIn(0.0, 1.0)
        return (correlation - 3.0 / kotlin.math.sqrt(count.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun failedImageAlignment(warning: String) = StarAlignmentResult(
        dx = 0,
        dy = 0,
        applied = false,
        confidence = 0f,
        referenceStars = 0,
        candidateStars = 0,
        matchedStars = 0,
        method = "IMAGE_SAFE",
        warning = warning
    )

    private fun Bitmap.toArgbPixelImage(): ArgbPixelImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return ArgbPixelImage(width, height, pixels)
    }
}
