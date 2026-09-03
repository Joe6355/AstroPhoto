package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedSkyStatistics
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.ExperimentalStarEnhancementSupport
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.linearChannel
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.linearLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.packLinear
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.smoothStep
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import kotlin.math.ceil
import kotlin.math.roundToInt

data class ProtectedFaintStarEnhancementMetrics(
    val discoveredStarCount: Int,
    val consideredStarCount: Int,
    val enhancedStarCount: Int,
    val changedPixelCount: Int,
    val maximumResidualGain: Float
) {
    companion object {
        val NONE = ProtectedFaintStarEnhancementMetrics(0, 0, 0, 0, 0f)
    }
}

data class ProtectedFaintStarEnhancementResult(
    val image: FileBackedImage,
    val metrics: ProtectedFaintStarEnhancementMetrics
)

/** Raises only compact multi-pixel stellar residuals above a measured local annulus. */
class ProtectedFaintStarEnhancer(
    private val statistics: FileBackedSkyStatistics = FileBackedSkyStatistics()
) {
    fun apply(
        input: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        confirmedStars: List<DetectedStar>,
        store: ResultCandidateStore,
        strength: Float = PRODUCTION_STRENGTH
    ): ProtectedFaintStarEnhancementResult {
        require(input.width == effectiveSkyAlpha.width && input.height == effectiveSkyAlpha.height)
        require(strength in 0f..1f)
        val sky = FileBackedImageReader(input).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
                statistics.calculate(image, alpha, confirmedStars)
            }
        }
        val noiseFloor = maxOf(MINIMUM_NOISE_FLOOR, sky.luminanceMad * NOISE_MAD_SCALE)
        val discoveredStars = FileBackedImageReader(input, cachedRows = 20).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 20).use { alpha ->
                ExperimentalStarEnhancementSupport.discoverCompactCandidates(
                    width = input.width,
                    height = input.height,
                    knownStars = confirmedStars,
                    statistics = sky,
                    colorAt = image::argbAt,
                    alphaAt = alpha::alphaAt,
                    defectAt = { _, _ -> 0f }
                )
            }
        }
        val activeStars = confirmedStars + discoveredStars
        val changes = mutableMapOf<Long, Int>()
        var considered = 0
        var enhanced = 0
        var maximumGain = 0f
        FileBackedImageReader(input, cachedRows = 20).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 20).use { alpha ->
                activeStars.forEach { star ->
                    if (!validShape(star)) return@forEach
                    considered++
                    val centerX = star.x.roundToInt()
                    val centerY = star.y.roundToInt()
                    val radius = ceil(maxOf(1f, star.width * CORE_RADIUS_SCALE)).toInt().coerceAtMost(3)
                    if (
                        centerX - radius < 0 || centerX + radius >= input.width ||
                        centerY - radius < 0 || centerY + radius >= input.height ||
                        alpha.alphaAt(centerX, centerY) < FULL_SKY_ALPHA
                    ) return@forEach
                    val background = annulusMedian(image, alpha, centerX, centerY, star.width)
                    if (!background.isFinite()) return@forEach
                    val centerLuminance = linearLuminance(image.argbAt(centerX, centerY))
                    val centerDetail = centerLuminance - background
                    if (centerDetail <= noiseFloor) return@forEach
                    val supportThreshold = maxOf(noiseFloor * 0.55f, centerDetail * MIN_CORE_DETAIL_FRACTION)
                    var support = 0
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val x = centerX + dx
                        val y = centerY + dy
                        if (
                            alpha.alphaAt(x, y) >= FULL_SKY_ALPHA &&
                            linearLuminance(image.argbAt(x, y)) - background >= supportThreshold
                        ) support++
                    }
                    if (support !in MINIMUM_SUPPORT..MAXIMUM_SUPPORT) return@forEach
                    val brightStart = maxOf(
                        sky.highPercentile,
                        sky.estimatedSafeWhitePoint * BRIGHT_PROTECTION_WHITE_FRACTION
                    )
                    val brightEnd = maxOf(brightStart + MINIMUM_BRIGHT_PROTECTION_RANGE, sky.estimatedSafeWhitePoint)
                    val brightProtection = 1f - smoothStep(brightStart, brightEnd, centerLuminance)
                    val residualGain = (strength * star.confidence.coerceIn(0f, 1f) * brightProtection)
                        .coerceIn(0f, MAXIMUM_RESIDUAL_GAIN)
                    if (residualGain <= MINIMUM_EFFECTIVE_GAIN) return@forEach
                    var starChanged = false
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        val distanceSquared = dx * dx + dy * dy
                        if (distanceSquared > radius * radius) continue
                        val x = centerX + dx
                        val y = centerY + dy
                        if (alpha.alphaAt(x, y) < FULL_SKY_ALPHA) continue
                        val key = y.toLong() * input.width + x
                        val color = changes[key] ?: image.argbAt(x, y)
                        val luminance = linearLuminance(color)
                        val localDetail = luminance - background
                        if (localDetail < supportThreshold || luminance <= MINIMUM_LUMINANCE) continue
                        val radialWeight = 1f - distanceSquared.toFloat() / (radius * radius + 1f)
                        val localGain = residualGain * radialWeight
                        val target = (background + localDetail * (1f + localGain))
                            .coerceAtMost(MAXIMUM_ENHANCED_LUMINANCE)
                        val scale = target / luminance
                        changes[key] = packLinear(
                            linearChannel(color, 16) * scale,
                            linearChannel(color, 8) * scale,
                            linearChannel(color, 0) * scale
                        )
                        maximumGain = maxOf(maximumGain, localGain)
                        starChanged = true
                    }
                    if (starChanged) enhanced++
                }
            }
        }
        val writer = store.createTemporaryWriter("protected-faint-stars", input.width, input.height)
        val row = IntArray(input.width)
        try {
            FileBackedImageReader(input).use { image ->
                for (y in 0 until input.height) {
                    image.readArgbRow(y, row)
                    for (x in 0 until input.width) {
                        changes[y.toLong() * input.width + x]?.let { row[x] = it }
                    }
                    writer.writeRow(y, row)
                }
            }
            return ProtectedFaintStarEnhancementResult(
                image = writer.finish(),
                metrics = ProtectedFaintStarEnhancementMetrics(
                    discoveredStarCount = discoveredStars.size,
                    consideredStarCount = considered,
                    enhancedStarCount = enhanced,
                    changedPixelCount = changes.size,
                    maximumResidualGain = maximumGain
                )
            )
        } catch (error: Throwable) {
            runCatching { writer.close() }
            runCatching { store.deleteTemporary(writer.image) }
            throw error
        }
    }

    private fun annulusMedian(
        image: FileBackedImageReader,
        alpha: FileBackedFloatPlaneReader,
        centerX: Int,
        centerY: Int,
        width: Float
    ): Float {
        val inner = ceil(maxOf(2f, width * ANNULUS_INNER_SCALE)).toInt()
        val outer = (inner + ANNULUS_WIDTH).coerceAtMost(MAXIMUM_ANNULUS_RADIUS)
        if (
            centerX - outer < 0 || centerX + outer >= image.width ||
            centerY - outer < 0 || centerY + outer >= image.height
        ) return Float.NaN
        val values = FloatArray((outer * 2 + 1) * (outer * 2 + 1))
        var count = 0
        for (dy in -outer..outer) for (dx in -outer..outer) {
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < inner * inner || distanceSquared > outer * outer) continue
            val x = centerX + dx
            val y = centerY + dy
            if (alpha.alphaAt(x, y) < FULL_SKY_ALPHA) continue
            values[count++] = linearLuminance(image.argbAt(x, y))
        }
        if (count < MINIMUM_ANNULUS_SAMPLES) return Float.NaN
        values.sort(0, count)
        return values[count / 2]
    }

    private fun validShape(star: DetectedStar): Boolean =
        star.confidence >= MINIMUM_CONFIDENCE && star.width in MINIMUM_WIDTH..MAXIMUM_WIDTH &&
            star.ellipticity <= MAXIMUM_ELLIPTICITY && star.localContrast > 0f

    companion object {
        internal const val PRODUCTION_STRENGTH = 0.62f
        private const val MINIMUM_CONFIDENCE = 0.28f
        private const val MINIMUM_WIDTH = 0.65f
        private const val MAXIMUM_WIDTH = 4.6f
        private const val MAXIMUM_ELLIPTICITY = 0.66f
        private const val CORE_RADIUS_SCALE = 0.58f
        private const val ANNULUS_INNER_SCALE = 1.4f
        private const val ANNULUS_WIDTH = 3
        private const val MAXIMUM_ANNULUS_RADIUS = 9
        private const val MINIMUM_ANNULUS_SAMPLES = 12
        private const val MINIMUM_SUPPORT = 3
        private const val MAXIMUM_SUPPORT = 24
        private const val MIN_CORE_DETAIL_FRACTION = 0.18f
        private const val NOISE_MAD_SCALE = 0.65f
        private const val MINIMUM_NOISE_FLOOR = 1f / 8191f
        private const val MAXIMUM_RESIDUAL_GAIN = 0.62f
        private const val MINIMUM_EFFECTIVE_GAIN = 0.01f
        private const val MINIMUM_LUMINANCE = 1e-6f
        private const val MAXIMUM_ENHANCED_LUMINANCE = 0.985f
        private const val FULL_SKY_ALPHA = 0.98f
        private const val BRIGHT_PROTECTION_WHITE_FRACTION = 0.65f
        private const val MINIMUM_BRIGHT_PROTECTION_RANGE = 0.08f
    }
}
