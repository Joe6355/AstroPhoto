package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class GlobalToneAnchors(
    val toeStart: Double,
    val toeEnd: Double
) {
    init {
        require(toeStart in 0.0..1.0)
        require(toeEnd in 0.0..1.0)
        require(toeEnd > toeStart)
    }
}

data class GlobalTonePixelResult(
    val argb: Int,
    val scaleLimited: Boolean,
    val maximumLinearChannel: Double
)

/**
 * Spatially invariant luminance transform approved by the fixed-gain replay.
 * Pixel output depends only on the encoded input RGB and shared scalar parameters.
 */
class GlobalToneTransform(
    private val epsilon: Double = EPSILON
) {
    fun transformArgb(
        argb: Int,
        anchors: GlobalToneAnchors,
        gain: Double = APPROVED_GAIN
    ): GlobalTonePixelResult {
        require(gain in 0.0..1.0)
        val red = decode8(argb ushr 16 and 0xFF)
        val green = decode8(argb ushr 8 and 0xFF)
        val blue = decode8(argb and 0xFF)
        val adjustment = adjustment(red, green, blue, anchors, gain)
        val alpha = argb ushr 24 and 0xFF
        return GlobalTonePixelResult(
            argb = (alpha shl 24) or
                (encode8(red * adjustment.scale) shl 16) or
                (encode8(green * adjustment.scale) shl 8) or
                encode8(blue * adjustment.scale),
            scaleLimited = adjustment.scaleLimited,
            maximumLinearChannel = adjustment.maximumLinearChannel
        )
    }

    private data class Adjustment(val scale: Double, val scaleLimited: Boolean, val maximumLinearChannel: Double)

    private fun adjustment(red: Double, green: Double, blue: Double, anchors: GlobalToneAnchors, gain: Double): Adjustment {
        val luminance = linearLuminance(red, green, blue)
        val maximumInput = max(red, max(green, blue))
        if (luminance <= epsilon) return Adjustment(1.0, false, maximumInput)
        val requestedScale = toneLuminance(luminance, anchors, gain) / max(luminance, epsilon)
        val gamutScale = 1.0 / max(maximumInput, epsilon)
        val scale = min(requestedScale, gamutScale)
        return Adjustment(scale, gamutScale + SCALE_LIMIT_TOLERANCE < requestedScale, maximumInput * scale)
    }

    fun transformLinearRow(
        source: LongArray,
        destination: LongArray,
        anchors: GlobalToneAnchors,
        gain: Double = APPROVED_GAIN,
        pixelCount: Int = source.size
    ): GlobalToneRowMetrics {
        require(gain in 0.0..1.0 && pixelCount in 0..min(source.size, destination.size))
        var limited = 0
        var maximum = 0.0
        for (index in 0 until pixelCount) {
            val color = source[index]
            val red = LinearRgb16.red(color).toDouble()
            val green = LinearRgb16.green(color).toDouble()
            val blue = LinearRgb16.blue(color).toDouble()
            val adjustment = adjustment(red, green, blue, anchors, gain)
            destination[index] = LinearRgb16.pack(
                (red * adjustment.scale).toFloat(),
                (green * adjustment.scale).toFloat(),
                (blue * adjustment.scale).toFloat()
            )
            if (adjustment.scaleLimited) limited++
            maximum = max(maximum, adjustment.maximumLinearChannel)
        }
        return GlobalToneRowMetrics(limited, maximum)
    }

    fun transformRow(
        source: IntArray,
        destination: IntArray,
        anchors: GlobalToneAnchors,
        gain: Double = APPROVED_GAIN,
        pixelCount: Int = source.size
    ): GlobalToneRowMetrics {
        require(pixelCount in 0..min(source.size, destination.size))
        var scaleLimited = 0
        var maximumLinearChannel = 0.0
        for (index in 0 until pixelCount) {
            val result = transformArgb(source[index], anchors, gain)
            destination[index] = result.argb
            if (result.scaleLimited) scaleLimited++
            maximumLinearChannel = max(maximumLinearChannel, result.maximumLinearChannel)
        }
        return GlobalToneRowMetrics(scaleLimited, maximumLinearChannel)
    }

    fun toneLuminance(
        value: Double,
        anchors: GlobalToneAnchors,
        gain: Double = APPROVED_GAIN
    ): Double {
        require(gain in 0.0..1.0)
        val luminance = value.coerceIn(0.0, 1.0)
        if (luminance <= epsilon) return luminance
        val toeWeight = smoothStep(anchors.toeStart, anchors.toeEnd, luminance)
        val lift = gain * luminance * (1.0 - luminance).pow(4.0)
        return (luminance + toeWeight * lift).coerceIn(0.0, 1.0)
    }

    fun slopeAt(
        value: Double,
        anchors: GlobalToneAnchors,
        gain: Double = APPROVED_GAIN
    ): Double {
        val lower = (value - SLOPE_STEP).coerceAtLeast(0.0)
        val upper = (value + SLOPE_STEP).coerceAtMost(1.0)
        if (upper <= lower) return 1.0
        return (toneLuminance(upper, anchors, gain) -
            toneLuminance(lower, anchors, gain)) / (upper - lower)
    }

    private fun decode8(value: Int): Double = LINEAR_CHANNEL_LUT[value.coerceIn(0, 255)]

    private fun encode8(value: Double): Int =
        (SrgbTransfer.linearToSrgb(value) * 255.0).roundToInt().coerceIn(0, 255)

    private fun linearLuminance(red: Double, green: Double, blue: Double): Double =
        REC_709_RED * red + REC_709_GREEN * green + REC_709_BLUE * blue

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    companion object {
        const val APPROVED_GAIN = 0.40
        const val EPSILON = 1e-6
        private const val SCALE_LIMIT_TOLERANCE = 1e-12
        private const val SLOPE_STEP = 1e-6
        private const val REC_709_RED = 0.2126
        private const val REC_709_GREEN = 0.7152
        private const val REC_709_BLUE = 0.0722
        private val LINEAR_CHANNEL_LUT = DoubleArray(256) { encoded ->
            SrgbTransfer.srgbToLinear(encoded / 255.0)
        }
    }
}

data class GlobalToneRowMetrics(
    val scaleLimitedPixelCount: Int,
    val maximumLinearChannel: Double
)
