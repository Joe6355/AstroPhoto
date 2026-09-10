package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer

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
    blueValues: IntArray,
    count: Int = colors.size
): Int {
    require(count in 1..colors.size) {
        "Sigma clipping requires at least one pixel sample"
    }
    require(sigmaThreshold.isFinite() && sigmaThreshold > 0.0) {
        "Sigma threshold must be finite and positive"
    }
    require(iterations > 0) { "Sigma iteration count must be positive" }
    require(
        redValues.size >= count &&
            greenValues.size >= count &&
            blueValues.size >= count
    ) {
        "Sigma channel buffers are shorter than the pixel samples"
    }

    for (index in 0 until count) {
        val color = colors[index]
        redValues[index] = color ushr 16 and 0xFF
        greenValues[index] = color ushr 8 and 0xFF
        blueValues[index] = color and 0xFF
    }
    val red = sigmaClipChannel(redValues, count, sigmaThreshold, iterations)
    val green = sigmaClipChannel(greenValues, count, sigmaThreshold, iterations)
    val blue = sigmaClipChannel(blueValues, count, sigmaThreshold, iterations)
    return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
}

private fun sigmaClipChannel(
    values: IntArray,
    initialCount: Int,
    sigmaThreshold: Double,
    iterations: Int
): Int {
    return sigmaClipChannelMean(values, initialCount, sigmaThreshold, iterations).roundToInt().coerceIn(0, 255)
}

private fun sigmaClipChannelMean(
    values: IntArray,
    initialCount: Int,
    sigmaThreshold: Double,
    iterations: Int
): Double {
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
        if (standardDeviation == 0.0) return mean

        val threshold = sigmaThreshold * standardDeviation
        var acceptedCount = 0
        for (index in 0 until count) {
            val value = values[index]
            if (abs(value - mean) <= threshold) {
                values[acceptedCount] = value
                acceptedCount++
            }
        }
        if (acceptedCount == 0) return mean
        if (acceptedCount == count) return mean
        count = acceptedCount
    }

    var finalSum = 0L
    for (index in 0 until count) finalSum += values[index]
    return finalSum.toDouble() / count
}

internal fun sigmaClipLinearRgbPixel(
    colors: IntArray, channels: Array<IntArray>, count: Int, sigmaThreshold: Double, iterations: Int = 1
): Long {
    require(count in 1..colors.size && channels.size == 3)
    require(sigmaThreshold.isFinite() && sigmaThreshold > 0.0 && iterations > 0)
    for (channel in 0..2) for (index in 0 until count) {
        channels[channel][index] = colors[index] ushr ((2 - channel) * 8) and 255
    }
    return LinearRgb16.pack(
        SrgbTransfer.srgbToLinear((sigmaClipChannelMean(channels[0], count, sigmaThreshold, iterations) / 255.0).toFloat()),
        SrgbTransfer.srgbToLinear((sigmaClipChannelMean(channels[1], count, sigmaThreshold, iterations) / 255.0).toFloat()),
        SrgbTransfer.srgbToLinear((sigmaClipChannelMean(channels[2], count, sigmaThreshold, iterations) / 255.0).toFloat())
    )
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
