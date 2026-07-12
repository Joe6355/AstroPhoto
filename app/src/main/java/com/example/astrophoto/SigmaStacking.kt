package com.example.astrophoto

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun sigmaClipArgbFrames(
    frames: List<AveragePixelFrame>,
    sigmaThreshold: Double,
    iterations: Int = 1
): AveragePixelFrame {
    validateSigmaFrames(frames)
    require(sigmaThreshold.isFinite() && sigmaThreshold > 0.0) {
        "Sigma threshold must be finite and positive"
    }
    require(iterations > 0) { "Sigma iteration count must be positive" }

    val first = frames.first()
    val output = IntArray(first.pixels.size)
    val colors = IntArray(frames.size)
    val redValues = IntArray(frames.size)
    val greenValues = IntArray(frames.size)
    val blueValues = IntArray(frames.size)

    for (pixelIndex in output.indices) {
        frames.indices.forEach { frameIndex ->
            colors[frameIndex] = frames[frameIndex].pixels[pixelIndex]
        }
        output[pixelIndex] = sigmaClipArgbPixel(
            colors = colors,
            sigmaThreshold = sigmaThreshold,
            iterations = iterations,
            redValues = redValues,
            greenValues = greenValues,
            blueValues = blueValues
        )
    }
    return AveragePixelFrame(first.width, first.height, output)
}

internal fun sigmaClipArgbPixel(
    colors: IntArray,
    sigmaThreshold: Double,
    iterations: Int,
    redValues: IntArray,
    greenValues: IntArray,
    blueValues: IntArray
): Int {
    require(colors.isNotEmpty()) { "Sigma clipping requires at least one pixel sample" }
    require(sigmaThreshold.isFinite() && sigmaThreshold > 0.0) {
        "Sigma threshold must be finite and positive"
    }
    require(iterations > 0) { "Sigma iteration count must be positive" }
    require(
        redValues.size >= colors.size &&
            greenValues.size >= colors.size &&
            blueValues.size >= colors.size
    ) {
        "Sigma channel buffers are shorter than the pixel samples"
    }

    colors.indices.forEach { index ->
        val color = colors[index]
        redValues[index] = color ushr 16 and 0xFF
        greenValues[index] = color ushr 8 and 0xFF
        blueValues[index] = color and 0xFF
    }
    val red = sigmaClipChannel(redValues, colors.size, sigmaThreshold, iterations)
    val green = sigmaClipChannel(greenValues, colors.size, sigmaThreshold, iterations)
    val blue = sigmaClipChannel(blueValues, colors.size, sigmaThreshold, iterations)
    return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
}

private fun sigmaClipChannel(
    values: IntArray,
    initialCount: Int,
    sigmaThreshold: Double,
    iterations: Int
): Int {
    var count = initialCount

    repeat(iterations) {
        var sum = 0L
        for (index in 0 until count) sum += values[index]
        val mean = sum.toDouble() / count
        var squaredDifferenceSum = 0.0
        for (index in 0 until count) {
            val difference = values[index] - mean
            squaredDifferenceSum += difference * difference
        }
        val standardDeviation = sqrt(squaredDifferenceSum / count)
        if (standardDeviation == 0.0) return mean.roundToInt().coerceIn(0, 255)

        val threshold = sigmaThreshold * standardDeviation
        var acceptedCount = 0
        for (index in 0 until count) {
            val value = values[index]
            if (abs(value - mean) <= threshold) {
                values[acceptedCount] = value
                acceptedCount++
            }
        }
        if (acceptedCount == 0) return mean.roundToInt().coerceIn(0, 255)
        if (acceptedCount == count) return mean.roundToInt().coerceIn(0, 255)
        count = acceptedCount
    }

    var finalSum = 0L
    for (index in 0 until count) finalSum += values[index]
    return (finalSum.toDouble() / count).roundToInt().coerceIn(0, 255)
}

private fun validateSigmaFrames(frames: List<AveragePixelFrame>) {
    require(frames.isNotEmpty()) { "Sigma clipping requires at least one frame" }
    val first = frames.first()
    frames.forEach { frame ->
        require(frame.width > 0 && frame.height > 0) {
            "Sigma frame dimensions must be positive"
        }
        val expected = frame.width.toLong() * frame.height.toLong()
        require(expected <= Int.MAX_VALUE && frame.pixels.size == expected.toInt()) {
            "Sigma frame pixel count does not match its dimensions"
        }
        require(frame.width == first.width && frame.height == first.height) {
            "Sigma clipping requires equal frame dimensions"
        }
    }
}

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
