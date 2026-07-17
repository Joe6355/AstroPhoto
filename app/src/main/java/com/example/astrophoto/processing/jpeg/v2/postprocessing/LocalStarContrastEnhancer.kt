package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.example.astrophoto.processing.jpeg.v2.model.StarEnhancementDiagnostics
import kotlin.math.ceil
import kotlin.math.roundToInt

data class LocalStarEnhancementResult(
    val image: ArgbPixelImage,
    val diagnostics: StarEnhancementDiagnostics
)

class LocalStarContrastEnhancer {
    fun apply(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        stars: List<DetectedStar>,
        statistics: SkyStatisticsResult,
        strength: Float,
        maximumDetailGain: Float,
        maximumWidthGrowth: Float,
        minimumContrastGain: Float = 0f
    ): LocalStarEnhancementResult {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        if (strength <= 0f || maximumDetailGain <= 1f || stars.isEmpty()) {
            return LocalStarEnhancementResult(
                image.copy(pixels = image.pixels.copyOf()),
                StarEnhancementDiagnostics(strength, stars.size, 0, stars.size, 0f)
            )
        }
        val output = image.pixels.copyOf()
        var enhanced = 0
        var rejected = 0
        val noiseFloor = maxOf(MIN_DETAIL, statistics.luminanceMad * 2.2f)
        stars.forEach { star ->
            val centerX = star.x.roundToInt()
            val centerY = star.y.roundToInt()
            if (!validShape(star) || centerX !in 0 until image.width || centerY !in 0 until image.height ||
                effectiveSkyAlpha.alphaAt(centerX, centerY) < STATISTICS_ALPHA_THRESHOLD
            ) {
                rejected++
                return@forEach
            }
            val localBackground = annulusMedian(image, effectiveSkyAlpha, centerX, centerY, star.width)
            if (!localBackground.isFinite()) {
                rejected++
                return@forEach
            }
            val centerColor = image.pixelAt(centerX, centerY)
            val centerLuminance = linearLuminance(centerColor)
            val detail = centerLuminance - localBackground
            if (detail <= noiseFloor || centerLuminance >= SATURATED_STAR_LIMIT ||
                isSingleChannelSpike(centerColor, localBackground, detail)
            ) {
                rejected++
                return@forEach
            }
            val coreRadius = ceil(maxOf(1f, star.width * 0.55f)).toInt().coerceAtMost(3)
            var support = 0
            for (dy in -coreRadius..coreRadius) for (dx in -coreRadius..coreRadius) {
                if (dx * dx + dy * dy > coreRadius * coreRadius) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                if (linearLuminance(image.pixelAt(x, y)) - localBackground >= detail * MIN_CORE_DETAIL_FRACTION) {
                    support++
                }
            }
            if (support !in MIN_STAR_SUPPORT..MAX_STAR_SUPPORT) {
                rejected++
                return@forEach
            }
            val brightProtection = 1f - smoothStep(
                maxOf(statistics.starBrightnessMedian, statistics.highPercentile),
                maxOf(statistics.brightStarCorePercentile, statistics.estimatedSafeWhitePoint),
                centerLuminance
            )
            val requestedGain = maxOf(
                minimumContrastGain,
                (maximumDetailGain - 1f) * strength.coerceIn(0f, 1f) *
                    star.confidence.coerceIn(0f, 1f)
            )
            val detailMultiplier = 1f + requestedGain * brightProtection
            if (detailMultiplier <= 1.001f) {
                rejected++
                return@forEach
            }
            var changed = false
            for (dy in -coreRadius..coreRadius) for (dx in -coreRadius..coreRadius) {
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared > coreRadius * coreRadius) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until image.width || y !in 0 until image.height ||
                    effectiveSkyAlpha.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD
                ) continue
                val index = y * image.width + x
                val color = output[index]
                val luminance = linearLuminance(color)
                val localDetail = luminance - localBackground
                if (localDetail < detail * MIN_CORE_DETAIL_FRACTION || luminance <= MIN_DETAIL) continue
                val radialWeight = 1f - distanceSquared.toFloat() /
                    (coreRadius * coreRadius + 1f)
                val gain = 1f + (detailMultiplier - 1f) * radialWeight
                val targetLuminance = (localBackground + localDetail * gain)
                    .coerceAtMost(MAX_ENHANCED_VALUE)
                val scale = targetLuminance / luminance
                output[index] = packLinear(
                    linearChannel(color, 16) * scale,
                    linearChannel(color, 8) * scale,
                    linearChannel(color, 0) * scale
                )
                changed = true
            }
            if (changed) enhanced++ else rejected++
        }
        // Only pixels already above a fixed fraction of the original core detail are changed;
        // therefore the support boundary and measured star width cannot expand.
        val measuredWidthGrowth = 0f.coerceAtMost(maximumWidthGrowth)
        return LocalStarEnhancementResult(
            ArgbPixelImage(image.width, image.height, output),
            StarEnhancementDiagnostics(
                strength = strength,
                considered = stars.size,
                enhanced = enhanced,
                rejected = rejected,
                maximumMeasuredWidthGrowth = measuredWidthGrowth
            )
        )
    }

    private fun validShape(star: DetectedStar): Boolean =
        star.confidence >= MIN_STAR_CONFIDENCE &&
            star.width in MIN_STAR_WIDTH..MAX_STAR_WIDTH &&
            star.ellipticity <= MAX_STAR_ELLIPTICITY &&
            star.localContrast > 0f

    private fun annulusMedian(
        image: ArgbPixelImage,
        mask: AlphaMask,
        centerX: Int,
        centerY: Int,
        width: Float
    ): Float {
        val inner = ceil(maxOf(2f, width * 1.4f)).toInt()
        val outer = (inner + 3).coerceAtMost(9)
        val values = FloatArray((outer * 2 + 1) * (outer * 2 + 1))
        var count = 0
        for (dy in -outer..outer) for (dx in -outer..outer) {
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < inner * inner || distanceSquared > outer * outer) continue
            val x = centerX + dx
            val y = centerY + dy
            if (x !in 0 until image.width || y !in 0 until image.height ||
                mask.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD
            ) continue
            values[count++] = linearLuminance(image.pixelAt(x, y))
        }
        if (count < MIN_ANNULUS_SAMPLES) return Float.NaN
        values.sort(0, count)
        return values[count / 2]
    }

    private fun isSingleChannelSpike(color: Int, background: Float, detail: Float): Boolean {
        val deltas = floatArrayOf(
            linearChannel(color, 16) - background,
            linearChannel(color, 8) - background,
            linearChannel(color, 0) - background
        ).sortedDescending()
        return deltas[0] > maxOf(MIN_SINGLE_CHANNEL_SPIKE, detail * 0.8f) &&
            deltas[1] < maxOf(MIN_SECOND_CHANNEL_SIGNAL, deltas[0] * 0.28f)
    }

    companion object {
        private const val MIN_STAR_CONFIDENCE = 0.32f
        private const val MIN_STAR_WIDTH = 0.65f
        private const val MAX_STAR_WIDTH = 4.4f
        private const val MAX_STAR_ELLIPTICITY = 0.62f
        private const val MIN_STAR_SUPPORT = 2
        private const val MAX_STAR_SUPPORT = 24
        private const val MIN_ANNULUS_SAMPLES = 12
        private const val MIN_CORE_DETAIL_FRACTION = 0.18f
        private const val MIN_DETAIL = 0.0015f
        private const val MIN_SINGLE_CHANNEL_SPIKE = 0.06f
        private const val MIN_SECOND_CHANNEL_SIGNAL = 0.012f
        private const val SATURATED_STAR_LIMIT = 0.985f
        private const val MAX_ENHANCED_VALUE = 0.985f
    }
}
