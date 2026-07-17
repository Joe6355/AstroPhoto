package com.example.astrophoto.processing.jpeg.v2.sampling

import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import kotlin.math.floor

data class SamplingFidelityResult(
    val kernel: String,
    val identityContrastRatio: Float,
    val productionContrastRatio: Float,
    val alternativeContrastRatio: Float
)

/** Bounded diagnostic only; it does not select or change the production interpolation kernel. */
class SamplingFidelityDiagnostic(
    private val sampler: TransformedBitmapSampler = TransformedBitmapSampler()
) {
    fun measure(
        source: ArgbPixelSource,
        patches: List<FullResolutionStarPatch>
    ): SamplingFidelityResult {
        val ratios = patches.take(MAX_PATCHES).mapNotNull { patch ->
            if (!hasMargin(source, patch.x, patch.y)) return@mapNotNull null
            val identity = contrast(source, patch.x, patch.y, 0f, direct = true)
            val production = contrast(source, patch.x, patch.y, HALF_PIXEL_PHASE, direct = false)
            if (identity <= 0.0001f) null else (production / identity).coerceIn(0f, 2f)
        }
        return SamplingFidelityResult(
            kernel = "BILINEAR",
            identityContrastRatio = if (ratios.isEmpty()) 0f else 1f,
            productionContrastRatio = median(ratios),
            alternativeContrastRatio = 0f
        )
    }

    private fun contrast(
        source: ArgbPixelSource,
        centerX: Float,
        centerY: Float,
        phase: Float,
        direct: Boolean
    ): Float {
        var maximum = 0f
        var borderSum = 0f
        var borderCount = 0
        for (y in -PATCH_RADIUS..PATCH_RADIUS) for (x in -PATCH_RADIUS..PATCH_RADIUS) {
            val luminance = if (direct) {
                luminance(source.argbAt(floor(centerX + x).toInt(), floor(centerY + y).toInt()))
            } else {
                val sample = sampler.sampleAt(source, centerX + x + phase, centerY + y + phase)
                    ?: return 0f
                0.2126f * sample.red + 0.7152f * sample.green + 0.0722f * sample.blue
            }
            maximum = maxOf(maximum, luminance)
            if (x == -PATCH_RADIUS || y == -PATCH_RADIUS || x == PATCH_RADIUS || y == PATCH_RADIUS) {
                borderSum += luminance
                borderCount++
            }
        }
        return (maximum - borderSum / borderCount.coerceAtLeast(1)).coerceAtLeast(0f)
    }

    private fun luminance(argb: Int): Float =
        0.2126f * (argb ushr 16 and 0xFF) / 255f +
            0.7152f * (argb ushr 8 and 0xFF) / 255f +
            0.0722f * (argb and 0xFF) / 255f

    private fun hasMargin(source: ArgbPixelSource, x: Float, y: Float): Boolean =
        x >= PATCH_RADIUS + 1 && y >= PATCH_RADIUS + 1 &&
            x <= source.width - PATCH_RADIUS - 2 && y <= source.height - PATCH_RADIUS - 2

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    companion object {
        private const val PATCH_RADIUS = 5
        private const val MAX_PATCHES = 16
        private const val HALF_PIXEL_PHASE = 0.5f
    }
}
