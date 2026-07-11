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

class AstroStretch {
    fun applyInPlace(
        bitmap: Bitmap,
        mode: AstroStretchMode
    ): AstroStretchResult {
        if (mode == AstroStretchMode.OFF) {
            return AstroStretchResult(mode, 0, 255, 0f, 0f)
        }
        val histogram = IntArray(256)
        val row = IntArray(bitmap.width)
        var total = 0L
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (color in row) {
                histogram[StarDetector.luminance(color)]++
                total++
            }
        }
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
        val denominator = ln(1.0 + strength)
        val range = (white - black).coerceAtLeast(1).toFloat()
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width) {
                val color = row[x]
                val red = stretchChannel(color ushr 16 and 0xFF, black, range, strength, denominator, gamma)
                val green = stretchChannel(color ushr 8 and 0xFF, black, range, strength, denominator, gamma)
                val blue = stretchChannel(color and 0xFF, black, range, strength, denominator, gamma)
                row[x] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        val clipping = clippingStats(bitmap)
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

    private fun clippingStats(bitmap: Bitmap): Pair<Float, Float> {
        val row = IntArray(bitmap.width)
        var black = 0L
        var white = 0L
        var total = 0L
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (color in row) {
                val lum = StarDetector.luminance(color)
                if (lum <= 2) black++
                if (lum >= 253) white++
                total++
            }
        }
        val safeTotal = total.coerceAtLeast(1L).toFloat()
        return black * 100f / safeTotal to white * 100f / safeTotal
    }
}
