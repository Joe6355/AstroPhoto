package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer

fun medianArgbFrames(frames: List<AveragePixelFrame>): AveragePixelFrame {
    validateMedianFrames(frames)
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
        output[pixelIndex] = medianArgbPixel(
            colors = colors,
            redValues = redValues,
            greenValues = greenValues,
            blueValues = blueValues
        )
    }
    return AveragePixelFrame(first.width, first.height, output)
}

internal fun medianArgbPixel(
    colors: IntArray,
    redValues: IntArray,
    greenValues: IntArray,
    blueValues: IntArray,
    count: Int = colors.size
): Int {
    require(count in 1..colors.size) { "Median requires at least one pixel sample" }
    require(
        redValues.size >= count &&
            greenValues.size >= count &&
            blueValues.size >= count
    ) {
        "Median channel buffers are shorter than the pixel samples"
    }

    for (index in 0 until count) {
        val color = colors[index]
        redValues[index] = color ushr 16 and 0xFF
        greenValues[index] = color ushr 8 and 0xFF
        blueValues[index] = color and 0xFF
    }
    java.util.Arrays.sort(redValues, 0, count)
    java.util.Arrays.sort(greenValues, 0, count)
    java.util.Arrays.sort(blueValues, 0, count)
    val red = medianChannel(redValues, count)
    val green = medianChannel(greenValues, count)
    val blue = medianChannel(blueValues, count)
    return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
}

private fun medianChannel(values: IntArray, count: Int): Int {
    return medianChannelPrecise(values, count).toInt().coerceIn(0, 255)
}

private fun medianChannelPrecise(values: IntArray, count: Int): Float {
    val middle = count / 2
    return if (count % 2 == 1) {
        values[middle].toFloat()
    } else {
        (values[middle - 1] + values[middle]) / 2f
    }
}

/** Same per-channel median, retaining the half-code value for even sample counts. */
internal fun medianLinearRgbPixel(colors: IntArray, channels: Array<IntArray>, count: Int): Long {
    require(count in 1..colors.size && channels.size == 3)
    for (channel in 0..2) {
        for (index in 0 until count) channels[channel][index] = colors[index] ushr ((2 - channel) * 8) and 255
        java.util.Arrays.sort(channels[channel], 0, count)
    }
    return LinearRgb16.pack(
        SrgbTransfer.srgbToLinear(medianChannelPrecise(channels[0], count) / 255f),
        SrgbTransfer.srgbToLinear(medianChannelPrecise(channels[1], count) / 255f),
        SrgbTransfer.srgbToLinear(medianChannelPrecise(channels[2], count) / 255f)
    )
}

private fun validateMedianFrames(frames: List<AveragePixelFrame>) {
    require(frames.isNotEmpty()) { "Median requires at least one frame" }
    val first = frames.first()
    frames.forEach { frame ->
        require(frame.width > 0 && frame.height > 0) {
            "Median frame dimensions must be positive"
        }
        val expected = frame.width.toLong() * frame.height.toLong()
        require(expected <= Int.MAX_VALUE && frame.pixels.size == expected.toInt()) {
            "Median frame pixel count does not match its dimensions"
        }
        require(frame.width == first.width && frame.height == first.height) {
            "Median requires equal frame dimensions"
        }
    }
}

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
