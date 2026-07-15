package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val STATISTICS_ALPHA_THRESHOLD = 0.98f
internal const val OPERATION_ALPHA_THRESHOLD = 0.0001f

internal fun linearChannel(color: Int, shift: Int): Float =
    SrgbTransfer.srgbToLinear((color ushr shift and 0xFF) / 255f)

internal fun linearLuminance(color: Int): Float =
    0.2126f * linearChannel(color, 16) +
        0.7152f * linearChannel(color, 8) +
        0.0722f * linearChannel(color, 0)

internal fun packLinear(red: Float, green: Float, blue: Float): Int {
    fun channel(value: Float): Int =
        (SrgbTransfer.linearToSrgb(value.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
    return OPAQUE_ALPHA or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

internal fun histogramBin(value: Float, bins: Int): Int =
    (value.coerceIn(0f, 1f) * (bins - 1)).roundToInt()

internal fun histogramValue(bin: Int, bins: Int): Float =
    bin.toFloat() / (bins - 1).coerceAtLeast(1)

internal fun histogramPercentile(histogram: IntArray, count: Long, fraction: Float): Float {
    if (count <= 0L) return 0f
    val target = ceil(count * fraction.coerceIn(0f, 1f)).toLong().coerceAtLeast(1L)
    var accumulated = 0L
    histogram.forEachIndexed { index, value ->
        accumulated += value
        if (accumulated >= target) return histogramValue(index, histogram.size)
    }
    return 1f
}

internal fun sortedPercentile(values: List<Float>, fraction: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val index = ((sorted.lastIndex) * fraction.coerceIn(0f, 1f)).roundToInt()
    return sorted[index]
}

internal fun createStarCoreMask(
    width: Int,
    height: Int,
    stars: List<DetectedStar>,
    radiusScale: Float = 1.8f
): BooleanArray {
    val result = BooleanArray(width * height)
    stars.forEach { star ->
        if (!star.confidence.isFinite() || star.confidence < 0.15f) return@forEach
        val radius = ceil(maxOf(2f, star.width * radiusScale)).toInt().coerceAtMost(8)
        val centerX = star.x.roundToInt()
        val centerY = star.y.roundToInt()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy > radius * radius) continue
            val x = centerX + dx
            val y = centerY + dy
            if (x in 0 until width && y in 0 until height) result[y * width + x] = true
        }
    }
    return result
}

internal fun effectiveSkyMask(mask: AlphaMask, threshold: Float = STATISTICS_ALPHA_THRESHOLD): BooleanArray =
    BooleanArray(mask.width * mask.height) { index ->
        mask.alphaAt(index % mask.width, index / mask.width) >= threshold
    }

internal fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun maximumChannelDifference(first: Int, second: Int): Int = maxOf(
    kotlin.math.abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
    kotlin.math.abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
    kotlin.math.abs((first and 0xFF) - (second and 0xFF))
)

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
