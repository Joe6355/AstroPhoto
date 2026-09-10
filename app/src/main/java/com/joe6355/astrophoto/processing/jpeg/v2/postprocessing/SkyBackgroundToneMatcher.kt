package com.joe6355.astrophoto.processing.jpeg.v2.postprocessing

import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import kotlin.math.sqrt

internal data class SkyBackgroundToneMatch(
    val linearOffset: Float,
    val processedMedian: Float
)

/** Removes only the broad sky lift that otherwise reveals the foreground mask silhouette. */
internal object SkyBackgroundToneMatcher {
    fun calculate(
        before: SkyStatisticsResult,
        after: SkyStatisticsResult
    ): SkyBackgroundToneMatch {
        if (before.skyPixelCount == 0 || after.skyPixelCount == 0) {
            return SkyBackgroundToneMatch(0f, after.luminanceMedian)
        }
        val headroom = maxOf(before.luminanceMad * BACKGROUND_MAD_HEADROOM, MIN_HEADROOM)
        val targetMedian = before.luminanceMedian + headroom
        return SkyBackgroundToneMatch(
            linearOffset = (after.luminanceMedian - targetMedian).coerceAtLeast(0f),
            processedMedian = after.luminanceMedian
        )
    }

    fun apply(color: Int, alpha: Float, match: SkyBackgroundToneMatch): Int {
        if (alpha <= 0f || match.linearOffset <= MIN_OFFSET) return color
        val red = linearChannel(color, 16)
        val green = linearChannel(color, 8)
        val blue = linearChannel(color, 0)
        val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
        val shadowSafety = smoothStep(0f, match.processedMedian.coerceAtLeast(MIN_HEADROOM), luminance)
        val offset = match.linearOffset * sqrt(alpha.coerceIn(0f, 1f)) * shadowSafety
        return packLinear(
            (red - offset).coerceAtLeast(0f),
            (green - offset).coerceAtLeast(0f),
            (blue - offset).coerceAtLeast(0f)
        )
    }

    fun applyLinear(color: Long, alpha: Float, match: SkyBackgroundToneMatch): Long {
        if (alpha <= 0f || match.linearOffset <= MIN_OFFSET) return color
        val luminance = LinearRgb16.luminance(color)
        val shadowSafety = smoothStep(0f, match.processedMedian.coerceAtLeast(MIN_HEADROOM), luminance)
        val offset = match.linearOffset * sqrt(alpha.coerceIn(0f, 1f)) * shadowSafety
        return LinearRgb16.pack(
            (LinearRgb16.red(color) - offset).coerceAtLeast(0f),
            (LinearRgb16.green(color) - offset).coerceAtLeast(0f),
            (LinearRgb16.blue(color) - offset).coerceAtLeast(0f)
        )
    }

    private const val BACKGROUND_MAD_HEADROOM = 0.25f
    private const val MIN_HEADROOM = 1f / 32768f
    private const val MIN_OFFSET = 1f / 65536f
}
