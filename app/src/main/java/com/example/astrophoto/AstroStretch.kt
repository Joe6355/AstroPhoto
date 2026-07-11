package com.example.astrophoto

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class AstroStretchMode {
    OFF,
    NATURAL,
    SOFT,
    MEDIUM,
    STRONG,
    EXTREME_PREVIEW
}

data class AstroStretchResult(
    val mode: AstroStretchMode,
    val blackPoint: Int,
    val whitePoint: Int,
    val clippedBlackPercent: Float,
    val clippedWhitePercent: Float,
    val warning: String? = null
)

internal data class AstroStretchParameters(
    val blackPoint: Int,
    val whitePoint: Int,
    val strength: Double,
    val gamma: Double
) {
    val range = (whitePoint - blackPoint).coerceAtLeast(1).toFloat()
    val denominator = ln(1.0 + strength)
}

class AstroStretch {
    fun applyInPlace(bitmap: Bitmap, mode: AstroStretchMode): AstroStretchResult {
        if (mode == AstroStretchMode.OFF) return offResult()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = applyInPlace(ArgbPixelImage(bitmap.width, bitmap.height, pixels), mode)
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }

    fun applyInPlace(image: ArgbPixelImage, mode: AstroStretchMode): AstroStretchResult {
        if (mode == AstroStretchMode.OFF) return offResult()
        val histogram = luminanceHistogram(image)
        val total = image.pixels.size.toLong()
        val blackPercentile = when (mode) {
            AstroStretchMode.NATURAL -> 0.0005
            AstroStretchMode.SOFT -> 0.001
            AstroStretchMode.MEDIUM -> 0.002
            AstroStretchMode.STRONG -> 0.003
            AstroStretchMode.EXTREME_PREVIEW -> 0.004
            AstroStretchMode.OFF -> 0.0
        }
        val whitePercentile = when (mode) {
            AstroStretchMode.NATURAL -> 0.9995
            AstroStretchMode.SOFT -> 0.9985
            AstroStretchMode.MEDIUM -> 0.998
            AstroStretchMode.STRONG -> 0.997
            AstroStretchMode.EXTREME_PREVIEW -> 0.996
            AstroStretchMode.OFF -> 1.0
        }
        val rawBlack = StarDetector.percentile(histogram, total, blackPercentile)
        val black = rawBlack.coerceIn(0, if (mode == AstroStretchMode.EXTREME_PREVIEW) 42 else 30)
        val white = StarDetector.percentile(histogram, total, whitePercentile)
            .coerceIn(black + 32, 255)
        val strength = when (mode) {
            AstroStretchMode.NATURAL -> 1.8
            AstroStretchMode.SOFT -> 4.0
            AstroStretchMode.MEDIUM -> 7.5
            AstroStretchMode.STRONG -> 12.0
            AstroStretchMode.EXTREME_PREVIEW -> 18.0
            AstroStretchMode.OFF -> 1.0
        }
        val gamma = when (mode) {
            AstroStretchMode.NATURAL -> 1.04
            AstroStretchMode.SOFT -> 1.13
            AstroStretchMode.MEDIUM -> 1.28
            AstroStretchMode.STRONG -> 1.45
            AstroStretchMode.EXTREME_PREVIEW -> 1.65
            AstroStretchMode.OFF -> 1.0
        }
        applyLogStretchInPlace(
            image,
            AstroStretchParameters(black, white, strength, gamma)
        )
        val clipping = clippingStats(image)
        return AstroStretchResult(
            mode = mode,
            blackPoint = black,
            whitePoint = white,
            clippedBlackPercent = clipping.first,
            clippedWhitePercent = clipping.second,
            warning = when {
                clipping.first > 8f -> "Тени могут быть зажаты, stretch ослаблен не был"
                clipping.second > 3f -> "Есть риск пересвета после stretch"
                else -> null
            }
        )
    }

    private fun offResult() = AstroStretchResult(AstroStretchMode.OFF, 0, 255, 0f, 0f)
}

internal fun applyManualAstroStretchInPlace(image: ArgbPixelImage): AstroStretchParameters {
    val histogram = luminanceHistogram(image)
    val parameters = manualAstroStretchParameters(histogram, image.pixels.size.toLong())
    applyLogStretchInPlace(image, parameters)
    return parameters
}

internal fun manualAstroStretchParameters(
    histogram: IntArray,
    total: Long
): AstroStretchParameters {
    require(histogram.size == 256) { "Stretch histogram must contain 256 bins" }
    require(total > 0) { "Stretch pixel count must be positive" }
    val black = StarDetector.percentile(histogram, total, 0.002).coerceIn(0, 28)
    val white = StarDetector.percentile(histogram, total, 0.998).coerceIn(black + 24, 255)
    return AstroStretchParameters(black, white, strength = 8.0, gamma = 1.0)
}

private fun luminanceHistogram(image: ArgbPixelImage): IntArray {
    val histogram = IntArray(256)
    image.pixels.forEach { histogram[pixelLuminance(it)]++ }
    return histogram
}

private fun applyLogStretchInPlace(
    image: ArgbPixelImage,
    parameters: AstroStretchParameters
) {
    image.pixels.indices.forEach { index ->
        image.pixels[index] = stretchArgbColor(image.pixels[index], parameters)
    }
}

internal fun stretchArgbColor(
    color: Int,
    parameters: AstroStretchParameters
): Int {
    val red = stretchChannel(
        color ushr 16 and 0xFF,
        parameters.blackPoint,
        parameters.range,
        parameters.strength,
        parameters.denominator,
        parameters.gamma
    )
    val green = stretchChannel(
        color ushr 8 and 0xFF,
        parameters.blackPoint,
        parameters.range,
        parameters.strength,
        parameters.denominator,
        parameters.gamma
    )
    val blue = stretchChannel(
        color and 0xFF,
        parameters.blackPoint,
        parameters.range,
        parameters.strength,
        parameters.denominator,
        parameters.gamma
    )
    return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}

private fun stretchChannel(
    value: Int,
    black: Int,
    range: Float,
    strength: Double,
    denominator: Double,
    gamma: Double
): Int {
    val normalized = ((value - black) / range).coerceIn(0f, 1f).toDouble()
    val logCurve = ln(1.0 + strength * normalized) / denominator
    val lifted = logCurve.pow(1.0 / gamma)
    return (lifted * 255.0).roundToInt().coerceIn(0, 255)
}

private fun clippingStats(image: ArgbPixelImage): Pair<Float, Float> {
    var black = 0L
    var white = 0L
    image.pixels.forEach { color ->
        val luminance = pixelLuminance(color)
        if (luminance <= 2) black++
        if (luminance >= 253) white++
    }
    val total = image.pixels.size.toFloat().coerceAtLeast(1f)
    return black * 100f / total to white * 100f / total
}
