package com.example.astrophoto

import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

data class LinearRgbImage(
    val width: Int,
    val height: Int,
    val pixels: FloatArray
) {
    init {
        require(width > 0 && height > 0) { "Linear image dimensions must be positive" }
        require(pixels.size.toLong() == width.toLong() * height * 3L) {
            "Linear RGB sample count does not match dimensions"
        }
        require(pixels.all(Float::isFinite)) { "Linear RGB samples must be finite" }
    }

    fun channelAt(x: Int, y: Int, channel: Int): Float =
        pixels[(y * width + x) * 3 + channel]
}

fun developAstroRaw(
    frame: AstroRawFrame,
    maxOutputPixels: Long = DEFAULT_LINEAR_RAW_PIXELS
): LinearRgbImage {
    require(maxOutputPixels > 0L) { "Linear RAW pixel limit must be positive" }
    val metadata = frame.metadata
    var binSize = 1
    while (
        ceil(metadata.width / binSize.toDouble()).toLong() *
        ceil(metadata.height / binSize.toDouble()).toLong() > maxOutputPixels
    ) {
        binSize *= 2
    }
    val outputWidth = maxOf(1, metadata.width / binSize)
    val outputHeight = maxOf(1, metadata.height / binSize)
    val output = FloatArray(outputWidth * outputHeight * 3)
    val sensorRgb = FloatArray(3)
    val sensorCounts = IntArray(3)

    for (outputY in 0 until outputHeight) {
        for (outputX in 0 until outputWidth) {
            val sensorX = outputX * binSize
            val sensorY = outputY * binSize
            if (binSize == 1) {
                demosaicSensorPixel(frame, sensorX, sensorY, sensorRgb)
            } else {
                binSensorBlock(frame, sensorX, sensorY, binSize, sensorRgb, sensorCounts)
            }
            val matrix = metadata.colorTransform
            val red = matrix[0] * sensorRgb[0] + matrix[1] * sensorRgb[1] +
                matrix[2] * sensorRgb[2]
            val green = matrix[3] * sensorRgb[0] + matrix[4] * sensorRgb[1] +
                matrix[5] * sensorRgb[2]
            val blue = matrix[6] * sensorRgb[0] + matrix[7] * sensorRgb[1] +
                matrix[8] * sensorRgb[2]
            val index = (outputY * outputWidth + outputX) * 3
            output[index] = red.coerceIn(0f, MAX_LINEAR_VALUE)
            output[index + 1] = green.coerceIn(0f, MAX_LINEAR_VALUE)
            output[index + 2] = blue.coerceIn(0f, MAX_LINEAR_VALUE)
        }
    }
    return LinearRgbImage(outputWidth, outputHeight, output)
}

fun toneMapLinearToArgb(image: LinearRgbImage): ArgbPixelImage {
    val histogram = IntArray(LINEAR_HISTOGRAM_BINS)
    for (index in image.pixels.indices step 3) {
        val luminance = linearLuminance(
            image.pixels[index],
            image.pixels[index + 1],
            image.pixels[index + 2]
        )
        histogram[linearHistogramIndex(luminance)]++
    }
    val pixelCount = image.width.toLong() * image.height
    val black = linearPercentile(histogram, pixelCount, 0.001)
    val white = maxOf(
        black + MIN_LINEAR_TONE_RANGE,
        linearPercentile(histogram, pixelCount, 0.9995)
    )
    val output = IntArray(image.width * image.height)
    output.indices.forEach { pixel ->
        val source = pixel * 3
        val red = displayLinearChannel(image.pixels[source], black, white)
        val green = displayLinearChannel(image.pixels[source + 1], black, white)
        val blue = displayLinearChannel(image.pixels[source + 2], black, white)
        output[pixel] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }
    return ArgbPixelImage(image.width, image.height, output)
}

private fun demosaicSensorPixel(
    frame: AstroRawFrame,
    x: Int,
    y: Int,
    output: FloatArray
) {
    val color = cfaColor(frame.metadata.cfaArrangement, x, y)
    val red = if (color == CFA_RED) calibratedSample(frame, x, y)
    else averageNeighbourColor(frame, x, y, CFA_RED)
    val green = if (color == CFA_GREEN) calibratedSample(frame, x, y)
    else averageNeighbourColor(frame, x, y, CFA_GREEN)
    val blue = if (color == CFA_BLUE) calibratedSample(frame, x, y)
    else averageNeighbourColor(frame, x, y, CFA_BLUE)
    output[0] = red
    output[1] = green
    output[2] = blue
}

private fun binSensorBlock(
    frame: AstroRawFrame,
    left: Int,
    top: Int,
    size: Int,
    output: FloatArray,
    counts: IntArray
) {
    output.fill(0f)
    counts.fill(0)
    val right = minOf(frame.metadata.width, left + size)
    val bottom = minOf(frame.metadata.height, top + size)
    for (y in top until bottom) {
        for (x in left until right) {
            val color = cfaColor(frame.metadata.cfaArrangement, x, y)
            output[color] += calibratedSample(frame, x, y)
            counts[color]++
        }
    }
    for (channel in 0..2) {
        if (counts[channel] > 0) {
            output[channel] /= counts[channel]
        } else {
            output[channel] = averageNeighbourColor(frame, left, top, channel)
        }
    }
}

private fun averageNeighbourColor(
    frame: AstroRawFrame,
    x: Int,
    y: Int,
    targetColor: Int
): Float {
    for (radius in 1..2) {
        var sum = 0f
        var count = 0
        for (dy in -radius..radius) {
            val sampleY = y + dy
            if (sampleY !in 0 until frame.metadata.height) continue
            for (dx in -radius..radius) {
                val sampleX = x + dx
                if (sampleX !in 0 until frame.metadata.width) continue
                if (cfaColor(frame.metadata.cfaArrangement, sampleX, sampleY) == targetColor) {
                    sum += calibratedSample(frame, sampleX, sampleY)
                    count++
                }
            }
        }
        if (count > 0) return sum / count
    }
    return calibratedSample(frame, x, y)
}

private fun calibratedSample(frame: AstroRawFrame, x: Int, y: Int): Float {
    val metadata = frame.metadata
    val blackIndex = (y and 1) * 2 + (x and 1)
    val black = metadata.blackLevels[blackIndex]
    val value = frame.sampleAt(x, y).toFloat()
    val normalized = ((value - black) / (metadata.whiteLevel - black).coerceAtLeast(1f))
        .coerceAtLeast(0f)
    val gainIndex = when (cfaColor(metadata.cfaArrangement, x, y)) {
        CFA_RED -> 0
        CFA_BLUE -> 3
        else -> if (y and 1 == 0) 1 else 2
    }
    return normalized * metadata.colorGains[gainIndex] *
        (metadata.postRawSensitivityBoost / 100f)
}

private fun cfaColor(arrangement: Int, x: Int, y: Int): Int {
    val index = (y and 1) * 2 + (x and 1)
    return when (arrangement) {
        0 -> when (index) {
            0 -> CFA_RED
            3 -> CFA_BLUE
            else -> CFA_GREEN
        }
        1 -> when (index) {
            1 -> CFA_RED
            2 -> CFA_BLUE
            else -> CFA_GREEN
        }
        2 -> when (index) {
            2 -> CFA_RED
            1 -> CFA_BLUE
            else -> CFA_GREEN
        }
        3 -> when (index) {
            3 -> CFA_RED
            0 -> CFA_BLUE
            else -> CFA_GREEN
        }
        else -> error("Unsupported RAW CFA arrangement")
    }
}

private fun linearLuminance(red: Float, green: Float, blue: Float): Float =
    red * 0.2126f + green * 0.7152f + blue * 0.0722f

private fun linearHistogramIndex(value: Float): Int =
    (value.coerceIn(0f, MAX_LINEAR_VALUE) / MAX_LINEAR_VALUE *
        (LINEAR_HISTOGRAM_BINS - 1)).roundToInt()

private fun linearPercentile(histogram: IntArray, total: Long, percentile: Double): Float {
    val target = (total * percentile).toLong().coerceIn(0L, total - 1L)
    var cumulative = 0L
    histogram.forEachIndexed { index, count ->
        cumulative += count
        if (cumulative > target) {
            return index.toFloat() * MAX_LINEAR_VALUE / (histogram.size - 1)
        }
    }
    return MAX_LINEAR_VALUE
}

private fun displayLinearChannel(value: Float, black: Float, white: Float): Int {
    val normalized = ((value - black) / (white - black)).coerceIn(0f, 1f).toDouble()
    val stretched = asinh(LINEAR_STRETCH * normalized) / asinh(LINEAR_STRETCH)
    val srgb = if (stretched <= 0.0031308) {
        12.92 * stretched
    } else {
        1.055 * stretched.pow(1.0 / 2.4) - 0.055
    }
    return (srgb * 255.0).roundToInt().coerceIn(0, 255)
}

private const val CFA_RED = 0
private const val CFA_GREEN = 1
private const val CFA_BLUE = 2
private const val DEFAULT_LINEAR_RAW_PIXELS = 4_000_000L
private const val MAX_LINEAR_VALUE = 4f
private const val LINEAR_HISTOGRAM_BINS = 8192
private const val MIN_LINEAR_TONE_RANGE = 1f / 1024f
private const val LINEAR_STRETCH = 10.0
