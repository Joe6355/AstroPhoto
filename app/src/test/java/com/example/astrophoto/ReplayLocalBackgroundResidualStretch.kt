package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.AdaptiveStretchResult
import com.example.astrophoto.processing.jpeg.v2.postprocessing.OPERATION_ALPHA_THRESHOLD
import com.example.astrophoto.processing.jpeg.v2.postprocessing.STATISTICS_ALPHA_THRESHOLD
import com.example.astrophoto.processing.jpeg.v2.postprocessing.linearChannel
import com.example.astrophoto.processing.jpeg.v2.postprocessing.linearLuminance
import com.example.astrophoto.processing.jpeg.v2.postprocessing.packLinear
import com.example.astrophoto.processing.jpeg.v2.postprocessing.smoothStep
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class LocalResidualStretchParameters(
    val strength: Float,
    val medianStrictStarPsfWidth: Float,
    val innerRadius: Int,
    val outerRadius: Int,
    val requiredAnnulusSamples: Int,
    val noiseThreshold: Float,
    val upperThreshold: Float,
    val brightProtectionStart: Float,
    val brightProtectionEnd: Float
)

internal data class LocalResidualPreparedInput(
    val width: Int,
    val height: Int,
    val inputArgbSha256: String,
    val backgroundFloat32LeSha256: String,
    val localBackground: FloatArray,
    val medianStrictStarPsfWidth: Float,
    val innerRadius: Int,
    val outerRadius: Int,
    val requiredAnnulusSamples: Int,
    val noiseThreshold: Float,
    val upperThreshold: Float,
    val brightProtectionStart: Float,
    val brightProtectionEnd: Float
)

internal data class LocalResidualStretchDiagnostics(
    val parameters: LocalResidualStretchParameters,
    val validBackgroundPixels: Int,
    val negativeResidualPixels: Int,
    val belowNoisePixels: Int,
    val supportedPixels: Int,
    val changedPixels: Int,
    val negativeResidualChangedPixels: Int,
    val backgroundChangedPixels: Int,
    val meanPositiveLuminanceDelta: Double,
    val maximumPositiveLuminanceDelta: Double,
    val meanLinearChromaticityShift: Double,
    val maximumLinearChromaticityShift: Double
)

/** Test-only replacement for the global AdaptiveAsinh operation. */
internal class ReplayLocalBackgroundResidualStretch(
    private val strength: Float,
    private val medianStrictStarPsfWidth: Float,
    private val preparedInput: LocalResidualPreparedInput? = null
) : ReplayStretchOverride {
    lateinit var prepared: LocalResidualPreparedInput
        private set
    lateinit var diagnostics: LocalResidualStretchDiagnostics
        private set

    override fun apply(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        stars: List<DetectedStar>,
        statistics: SkyStatisticsResult
    ): AdaptiveStretchResult {
        require(strength in 0f..MAX_STRENGTH)
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        prepared = preparedInput?.also {
            require(it.width == image.width && it.height == image.height)
            require(it.inputArgbSha256 == ReplayDiagnosticHashing.sha256Argb(image))
        } ?: prepare(image, effectiveSkyAlpha, statistics, medianStrictStarPsfWidth)

        val output = image.pixels.copyOf()
        var validBackgroundPixels = 0
        var negativeResidualPixels = 0
        var belowNoisePixels = 0
        var supportedPixels = 0
        var changedPixels = 0
        var negativeResidualChangedPixels = 0
        var backgroundChangedPixels = 0
        var luminanceDeltaSum = 0.0
        var maximumLuminanceDelta = 0.0
        var chromaticityShiftSum = 0.0
        var maximumChromaticityShift = 0.0

        output.indices.forEach { index ->
            val x = index % image.width
            val y = index / image.width
            val alpha = effectiveSkyAlpha.alphaAt(x, y)
            if (alpha <= OPERATION_ALPHA_THRESHOLD) return@forEach
            val background = prepared.localBackground[index]
            if (!background.isFinite()) return@forEach
            validBackgroundPixels++
            val original = image.pixels[index]
            val luminance = linearLuminance(original)
            val signedResidual = luminance - background
            if (signedResidual <= 0f) {
                negativeResidualPixels++
                if (output[index] != original) negativeResidualChangedPixels++
                return@forEach
            }
            if (signedResidual <= prepared.noiseThreshold) {
                belowNoisePixels++
                if (output[index] != original) backgroundChangedPixels++
                return@forEach
            }
            val support = smoothStep(
                prepared.noiseThreshold,
                prepared.upperThreshold,
                signedResidual
            )
            if (support <= 0f) return@forEach
            supportedPixels++
            val brightProtection = 1f - smoothStep(
                prepared.brightProtectionStart,
                prepared.brightProtectionEnd,
                luminance
            )
            val enhancedResidual = signedResidual * (1f + strength * brightProtection)
            val effectiveSupport = support * sqrt(alpha.coerceIn(0f, 1f))
            val targetLuminance = (
                luminance + effectiveSupport * (enhancedResidual - signedResidual)
                ).coerceIn(0f, MAX_UNCLIPPED_VALUE)
            if (targetLuminance <= luminance) return@forEach

            val red = linearChannel(original, 16)
            val green = linearChannel(original, 8)
            val blue = linearChannel(original, 0)
            var scale = targetLuminance / luminance.coerceAtLeast(MIN_LUMINANCE)
            val maximumChannel = maxOf(red, green, blue)
            if (maximumChannel < MAX_UNCLIPPED_VALUE && maximumChannel * scale > MAX_UNCLIPPED_VALUE) {
                scale = MAX_UNCLIPPED_VALUE / maximumChannel.coerceAtLeast(MIN_LUMINANCE)
            }
            val packed = packLinear(red * scale, green * scale, blue * scale)
            output[index] = packed
            if (packed != original) {
                changedPixels++
                val actualDelta = (linearLuminance(packed) - luminance).coerceAtLeast(0f).toDouble()
                luminanceDeltaSum += actualDelta
                maximumLuminanceDelta = maxOf(maximumLuminanceDelta, actualDelta)
                val shift = linearChromaticityShift(original, packed)
                chromaticityShiftSum += shift
                maximumChromaticityShift = maxOf(maximumChromaticityShift, shift)
            }
        }

        val parameters = parameters(prepared, strength)
        diagnostics = LocalResidualStretchDiagnostics(
            parameters = parameters,
            validBackgroundPixels = validBackgroundPixels,
            negativeResidualPixels = negativeResidualPixels,
            belowNoisePixels = belowNoisePixels,
            supportedPixels = supportedPixels,
            changedPixels = changedPixels,
            negativeResidualChangedPixels = negativeResidualChangedPixels,
            backgroundChangedPixels = backgroundChangedPixels,
            meanPositiveLuminanceDelta = if (changedPixels == 0) 0.0 else luminanceDeltaSum / changedPixels,
            maximumPositiveLuminanceDelta = maximumLuminanceDelta,
            meanLinearChromaticityShift = if (changedPixels == 0) 0.0 else chromaticityShiftSum / changedPixels,
            maximumLinearChromaticityShift = maximumChromaticityShift
        )
        require(negativeResidualChangedPixels == 0)
        require(backgroundChangedPixels == 0)
        return AdaptiveStretchResult(
            ArgbPixelImage(image.width, image.height, output),
            StretchDiagnostics(
                blackPoint = minOf(statistics.lowPercentile, statistics.estimatedBlackPoint),
                whitePoint = maxOf(statistics.estimatedSafeWhitePoint, statistics.highPercentile),
                asinhStrength = 0f,
                highlightProtectionStrength = 1f,
                appliedBlend = strength,
                medianSafetyScale = 1f
            )
        )
    }

    private fun prepare(
        image: ArgbPixelImage,
        alpha: AlphaMask,
        statistics: SkyStatisticsResult,
        psfWidth: Float
    ): LocalResidualPreparedInput {
        val innerRadius = ceil(maxOf(2f, psfWidth * PSF_INNER_RADIUS_SCALE)).toInt()
        val outerRadius = (innerRadius + ANNULUS_WIDTH).coerceAtMost(MAX_OUTER_RADIUS)
        require(outerRadius > innerRadius)
        val innerBoxRadius = innerRadius - 1
        val requiredSamples = squareArea(outerRadius) - squareArea(innerBoxRadius)
        val stride = image.width + 1
        val integralSum = DoubleArray(stride * (image.height + 1))
        val integralCount = IntArray(stride * (image.height + 1))
        for (y in 0 until image.height) {
            var rowSum = 0.0
            var rowCount = 0
            for (x in 0 until image.width) {
                if (alpha.alphaAt(x, y) >= STATISTICS_ALPHA_THRESHOLD) {
                    rowSum += linearLuminance(image.pixelAt(x, y)).toDouble()
                    rowCount++
                }
                val target = (y + 1) * stride + x + 1
                integralSum[target] = integralSum[y * stride + x + 1] + rowSum
                integralCount[target] = integralCount[y * stride + x + 1] + rowCount
            }
        }
        val backgrounds = FloatArray(image.width * image.height) { Float.NaN }
        for (y in outerRadius until image.height - outerRadius) {
            for (x in outerRadius until image.width - outerRadius) {
                val outerCount = rectangle(integralCount, stride, x - outerRadius, y - outerRadius,
                    x + outerRadius, y + outerRadius)
                val innerCount = rectangle(integralCount, stride, x - innerBoxRadius, y - innerBoxRadius,
                    x + innerBoxRadius, y + innerBoxRadius)
                val count = outerCount - innerCount
                if (count != requiredSamples) continue
                val outerSum = rectangle(integralSum, stride, x - outerRadius, y - outerRadius,
                    x + outerRadius, y + outerRadius)
                val innerSum = rectangle(integralSum, stride, x - innerBoxRadius, y - innerBoxRadius,
                    x + innerBoxRadius, y + innerBoxRadius)
                backgrounds[y * image.width + x] = ((outerSum - innerSum) / count).toFloat()
            }
        }
        val noiseThreshold = maxOf(MIN_DETAIL, statistics.luminanceMad * NOISE_MAD_MULTIPLIER)
        val upperThreshold = noiseThreshold * SUPPORT_UPPER_MULTIPLIER
        val brightStart = maxOf(statistics.starBrightnessMedian, statistics.highPercentile)
        val brightEnd = maxOf(statistics.brightStarCorePercentile, statistics.estimatedSafeWhitePoint)
        return LocalResidualPreparedInput(
            width = image.width,
            height = image.height,
            inputArgbSha256 = ReplayDiagnosticHashing.sha256Argb(image),
            backgroundFloat32LeSha256 = floatArraySha256(backgrounds),
            localBackground = backgrounds,
            medianStrictStarPsfWidth = psfWidth,
            innerRadius = innerRadius,
            outerRadius = outerRadius,
            requiredAnnulusSamples = requiredSamples,
            noiseThreshold = noiseThreshold,
            upperThreshold = upperThreshold,
            brightProtectionStart = brightStart,
            brightProtectionEnd = brightEnd
        )
    }

    private fun parameters(prepared: LocalResidualPreparedInput, strength: Float) =
        LocalResidualStretchParameters(
            strength = strength,
            medianStrictStarPsfWidth = prepared.medianStrictStarPsfWidth,
            innerRadius = prepared.innerRadius,
            outerRadius = prepared.outerRadius,
            requiredAnnulusSamples = prepared.requiredAnnulusSamples,
            noiseThreshold = prepared.noiseThreshold,
            upperThreshold = prepared.upperThreshold,
            brightProtectionStart = prepared.brightProtectionStart,
            brightProtectionEnd = prepared.brightProtectionEnd
        )

    private fun rectangle(values: DoubleArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double =
        values[(y1 + 1) * stride + x1 + 1] - values[y0 * stride + x1 + 1] -
            values[(y1 + 1) * stride + x0] + values[y0 * stride + x0]

    private fun rectangle(values: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int =
        values[(y1 + 1) * stride + x1 + 1] - values[y0 * stride + x1 + 1] -
            values[(y1 + 1) * stride + x0] + values[y0 * stride + x0]

    private fun squareArea(radius: Int): Int = (radius * 2 + 1) * (radius * 2 + 1)

    private fun linearChromaticityShift(first: Int, second: Int): Double {
        fun fractions(color: Int): DoubleArray {
            val red = linearChannel(color, 16).toDouble()
            val green = linearChannel(color, 8).toDouble()
            val blue = linearChannel(color, 0).toDouble()
            val sum = (red + green + blue).coerceAtLeast(1e-12)
            return doubleArrayOf(red / sum, green / sum, blue / sum)
        }
        val before = fractions(first)
        val after = fractions(second)
        return maxOf(
            kotlin.math.abs(before[0] - after[0]),
            kotlin.math.abs(before[1] - after[1]),
            kotlin.math.abs(before[2] - after[2])
        )
    }

    private fun floatArraySha256(values: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { value ->
            if (buffer.remaining() < Float.SIZE_BYTES) {
                digest.update(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            buffer.putFloat(value)
        }
        if (buffer.position() > 0) digest.update(buffer.array(), 0, buffer.position())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LOW_STRENGTH = 0.12f
        const val MEDIUM_STRENGTH = 0.24f
        const val STRONG_STRENGTH = 0.36f
        private const val MAX_STRENGTH = STRONG_STRENGTH
        private const val PSF_INNER_RADIUS_SCALE = 1.4f
        private const val ANNULUS_WIDTH = 3
        private const val MAX_OUTER_RADIUS = 9
        private const val MIN_DETAIL = 0.0015f
        private const val NOISE_MAD_MULTIPLIER = 2.2f
        private const val SUPPORT_UPPER_MULTIPLIER = 2f
        private const val MIN_LUMINANCE = 0.000001f
        private const val MAX_UNCLIPPED_VALUE = 0.995f
    }
}
