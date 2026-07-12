package com.example.astrophoto

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
    blueValues: IntArray
): Int {
    require(colors.isNotEmpty()) { "Median requires at least one pixel sample" }
    require(
        redValues.size >= colors.size &&
            greenValues.size >= colors.size &&
            blueValues.size >= colors.size
    ) {
        "Median channel buffers are shorter than the pixel samples"
    }

    colors.indices.forEach { index ->
        val color = colors[index]
        redValues[index] = color ushr 16 and 0xFF
        greenValues[index] = color ushr 8 and 0xFF
        blueValues[index] = color and 0xFF
    }
    java.util.Arrays.sort(redValues, 0, colors.size)
    java.util.Arrays.sort(greenValues, 0, colors.size)
    java.util.Arrays.sort(blueValues, 0, colors.size)
    val red = medianChannel(redValues, colors.size)
    val green = medianChannel(greenValues, colors.size)
    val blue = medianChannel(blueValues, colors.size)
    return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
}

private fun medianChannel(values: IntArray, count: Int): Int {
    val middle = count / 2
    return if (count % 2 == 1) {
        values[middle]
    } else {
        (values[middle - 1] + values[middle]) / 2
    }.coerceIn(0, 255)
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
