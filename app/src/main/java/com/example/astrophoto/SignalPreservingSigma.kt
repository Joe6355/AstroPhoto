package com.example.astrophoto

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun signalPreservingSigmaStack(
    frames: List<ArgbPixelImage>,
    sigma: Double
): ArgbPixelImage {
    require(frames.isNotEmpty()) { "Signal-preserving Sigma requires at least one frame" }
    require(sigma.isFinite() && sigma > 0.0) { "Sigma must be positive and finite" }
    val first = frames.first()
    frames.forEach { frame ->
        require(frame.width == first.width && frame.height == first.height) {
            "Signal-preserving Sigma requires equal frame dimensions"
        }
    }
    val output = IntArray(first.pixels.size)
    val red = IntArray(frames.size)
    val green = IntArray(frames.size)
    val blue = IntArray(frames.size)
    val sortedScratch = IntArray(frames.size)
    output.indices.forEach { pixel ->
        frames.indices.forEach { index ->
            val color = frames[index].pixels[pixel]
            red[index] = color ushr 16 and 0xFF
            green[index] = color ushr 8 and 0xFF
            blue[index] = color and 0xFF
        }
        output[pixel] = 0xFF000000.toInt() or
            (signalPreservingSigmaChannel(red, sigma, sortedScratch = sortedScratch) shl 16) or
            (signalPreservingSigmaChannel(green, sigma, sortedScratch = sortedScratch) shl 8) or
            signalPreservingSigmaChannel(blue, sigma, sortedScratch = sortedScratch)
    }
    return ArgbPixelImage(first.width, first.height, output)
}

internal fun signalPreservingSigmaChannel(
    values: IntArray,
    sigma: Double,
    count: Int = values.size,
    sortedScratch: IntArray? = null
): Int {
    require(count in 1..values.size)
    require(sigma.isFinite() && sigma > 0.0)
    require(sortedScratch == null || sortedScratch.size >= count)
    val effectiveSigma = maxOf(2.0, sigma)
    var sum = 0L
    for (index in 0 until count) sum += values[index]
    val mean = sum.toDouble() / count
    var squaredDifferenceSum = 0.0
    for (index in 0 until count) {
        val value = values[index]
        val difference = value - mean
        squaredDifferenceSum += difference * difference
    }
    val standardDeviation = sqrt(squaredDifferenceSum / count)
    val threshold = effectiveSigma * standardDeviation
    val sorted = if (count >= 3) {
        (sortedScratch ?: IntArray(count)).also {
            values.copyInto(it, endIndex = count)
            java.util.Arrays.sort(it, 0, count)
        }
    } else {
        null
    }
    val preserveBrightFloor = sorted?.let {
        val maximum = it[count - 1]
        val second = it[count - 2]
        if (
            maximum >= mean + threshold &&
            second >= mean + threshold * 0.65 &&
            maximum - second <= 45
        ) second else null
    }
    val isolatedHigh = sorted?.let {
        val maximum = it[count - 1]
        val second = it[count - 2]
        if (maximum - second > ISOLATED_HIGH_GAP) maximum else null
    }
    var acceptedSum = 0L
    var acceptedCount = 0
    for (index in 0 until count) {
        val value = values[index]
        if (value != isolatedHigh && (
            standardDeviation == 0.0 || abs(value - mean) <= threshold ||
            (preserveBrightFloor != null && value >= preserveBrightFloor)
        )) {
            acceptedSum += value
            acceptedCount++
        }
    }
    return if (acceptedCount > 0) {
        (acceptedSum.toDouble() / acceptedCount).roundToInt().coerceIn(0, 255)
    } else {
        mean.roundToInt().coerceIn(0, 255)
    }
}

private const val ISOLATED_HIGH_GAP = 45
