package com.example.astrophoto.processing.jpeg.v2.composition

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.CompositeDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.CompositeResult
import kotlin.math.abs
import kotlin.math.roundToInt

class SkyForegroundComposer {
    fun compose(
        stackedSky: ArgbPixelImage,
        reference: ArgbPixelImage,
        featheredSkyMask: AlphaMask,
        validCoverage: AlphaMask = AlphaMask.full(reference.width, reference.height),
        precomputedEffectiveSkyAlpha: AlphaMask? = null
    ): CompositeResult {
        require(stackedSky.width == reference.width && stackedSky.height == reference.height)
        require(featheredSkyMask.width == reference.width && featheredSkyMask.height == reference.height)
        require(validCoverage.width == reference.width && validCoverage.height == reference.height)
        require(
            precomputedEffectiveSkyAlpha == null ||
                (precomputedEffectiveSkyAlpha.width == reference.width &&
                    precomputedEffectiveSkyAlpha.height == reference.height)
        )
        val started = System.nanoTime()
        val effectiveValues = if (precomputedEffectiveSkyAlpha == null) {
            FloatArray(reference.pixels.size)
        } else {
            null
        }
        val output = IntArray(reference.pixels.size)
        var skyCoverageSum = 0.0
        var skyMaskWeight = 0.0
        var fallbackSum = 0.0
        var maximumForegroundDifference = 0
        for (index in output.indices) {
            val x = index % reference.width
            val y = index / reference.width
            val maskAlpha = featheredSkyMask.alphaAt(x, y)
            val coverage = validCoverage.alphaAt(x, y)
            val effectiveAlpha = precomputedEffectiveSkyAlpha?.alphaAt(x, y)
                ?: (maskAlpha * coverage).coerceIn(0f, 1f)
            effectiveValues?.set(index, effectiveAlpha)
            if (maskAlpha > 0f) {
                skyCoverageSum += coverage
                skyMaskWeight += 1.0
            }
            fallbackSum += 1f - effectiveAlpha
            val referenceColor = reference.pixels[index]
            val resultColor = when {
                effectiveAlpha <= EXACT_ALPHA_EPSILON -> referenceColor
                effectiveAlpha >= 1f - EXACT_ALPHA_EPSILON -> stackedSky.pixels[index]
                else -> linearBlend(stackedSky.pixels[index], referenceColor, effectiveAlpha)
            } or OPAQUE_ALPHA
            output[index] = resultColor
            if (maskAlpha <= EXACT_ALPHA_EPSILON) {
                maximumForegroundDifference = maxOf(
                    maximumForegroundDifference,
                    maximumChannelDifference(referenceColor, resultColor)
                )
            }
        }
        val effectiveMask = precomputedEffectiveSkyAlpha ?: AlphaMask(
            reference.width,
            reference.height,
            checkNotNull(effectiveValues)
        )
        val outputImage = ArgbPixelImage(reference.width, reference.height, output)
        val sharpnessBefore = foregroundSharpness(reference, effectiveMask)
        val sharpnessAfter = foregroundSharpness(outputImage, effectiveMask)
        return CompositeResult(
            image = outputImage,
            effectiveSkyAlpha = effectiveMask,
            diagnostics = CompositeDiagnostics(
                validSkyCoverageRatio = if (skyMaskWeight > 0.0) {
                    (skyCoverageSum / skyMaskWeight).toFloat()
                } else {
                    0f
                },
                referenceFallbackRatio = (fallbackSum / output.size).toFloat(),
                foregroundSharpnessBefore = sharpnessBefore,
                foregroundSharpnessAfter = sharpnessAfter,
                maximumForegroundChannelDifference = maximumForegroundDifference,
                outputWidth = reference.width,
                outputHeight = reference.height,
                cropApplied = false,
                compositionDurationMillis = (System.nanoTime() - started) / 1_000_000L
            )
        )
    }

    private fun linearBlend(stacked: Int, reference: Int, alpha: Float): Int {
        fun blendChannel(shift: Int): Int {
            val stackedSrgb = (stacked ushr shift and 0xFF) / 255f
            val referenceSrgb = (reference ushr shift and 0xFF) / 255f
            val linear = SrgbTransfer.srgbToLinear(stackedSrgb) * alpha +
                SrgbTransfer.srgbToLinear(referenceSrgb) * (1f - alpha)
            return (SrgbTransfer.linearToSrgb(linear) * 255f).roundToInt().coerceIn(0, 255)
        }
        return OPAQUE_ALPHA or
            (blendChannel(16) shl 16) or
            (blendChannel(8) shl 8) or
            blendChannel(0)
    }

    private fun foregroundSharpness(image: ArgbPixelImage, alpha: AlphaMask): Float {
        var sum = 0L
        var count = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (alpha.alphaAt(x, y) > FOREGROUND_ALPHA_LIMIT) continue
            val current = pixelLuminance(image.pixelAt(x, y))
            if (x + 1 < image.width && alpha.alphaAt(x + 1, y) <= FOREGROUND_ALPHA_LIMIT) {
                sum += abs(current - pixelLuminance(image.pixelAt(x + 1, y)))
                count++
            }
            if (y + 1 < image.height && alpha.alphaAt(x, y + 1) <= FOREGROUND_ALPHA_LIMIT) {
                sum += abs(current - pixelLuminance(image.pixelAt(x, y + 1)))
                count++
            }
        }
        return sum.toFloat() / count.coerceAtLeast(1L)
    }

    private fun maximumChannelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    companion object {
        private const val EXACT_ALPHA_EPSILON = 0.0001f
        private const val FOREGROUND_ALPHA_LIMIT = 0.001f
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
    }
}
