package com.example.astrophoto.processing.jpeg.v2.enhancement

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.postprocessing.FileBackedSkyStatistics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.StarCoreIndex
import com.example.astrophoto.processing.jpeg.v2.postprocessing.linearChannel
import com.example.astrophoto.processing.jpeg.v2.postprocessing.linearLuminance
import com.example.astrophoto.processing.jpeg.v2.postprocessing.packLinear
import com.example.astrophoto.processing.jpeg.v2.postprocessing.smoothStep
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ProtectedLuminanceDenoiseMetrics(
    val appliedStrength: Float,
    val noiseMapMean: Float,
    val noiseMapP90: Float,
    val noiseMapMaximum: Float,
    val eligiblePixelCount: Int,
    val changedPixelCount: Int,
    val protectedStarPixelCount: Int
) {
    companion object {
        val NONE = ProtectedLuminanceDenoiseMetrics(0f, 0f, 0f, 0f, 0, 0, 0)
    }
}

data class PreparedLuminanceNoiseMap(
    val plane: FileBackedFloatPlane,
    val metrics: ProtectedLuminanceDenoiseMetrics
)

data class ProtectedLuminanceDenoiseResult(
    val image: FileBackedImage,
    val metrics: ProtectedLuminanceDenoiseMetrics
)

/**
 * Builds a bounded high-frequency noise map, then smooths only dark sky pixels.
 * Confirmed star cores, compact unlisted peaks, foreground and strong edges remain untouched.
 */
class ProtectedLuminanceDenoiser(
    private val statistics: FileBackedSkyStatistics = FileBackedSkyStatistics()
) {
    fun prepareNoiseMap(
        baseline: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        confirmedStars: List<DetectedStar>,
        store: ResultCandidateStore
    ): PreparedLuminanceNoiseMap {
        require(baseline.width == effectiveSkyAlpha.width && baseline.height == effectiveSkyAlpha.height)
        val sky = FileBackedImageReader(baseline).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
                statistics.calculate(image, alpha, confirmedStars)
            }
        }
        val starCores = StarCoreIndex.create(
            baseline.width,
            baseline.height,
            confirmedStars,
            radiusScale = STAR_PROTECTION_RADIUS_SCALE
        )
        val noiseLow = maxOf(MIN_NOISE_LOW, sky.luminanceMad * NOISE_LOW_MAD_SCALE)
        val noiseHigh = maxOf(MIN_NOISE_HIGH, sky.luminanceMad * NOISE_HIGH_MAD_SCALE, noiseLow * 2f)
        val peakLimit = maxOf(MIN_PEAK_LIMIT, sky.luminanceMad * PEAK_MAD_SCALE)
        val darkStart = maxOf(
            sky.highPercentile,
            sky.luminanceMedian + maxOf(sky.luminanceMad * DARK_MAD_SCALE, MIN_DARK_RANGE)
        )
        val darkEnd = maxOf(
            darkStart + MIN_DARK_FADE_RANGE,
            sky.starBrightnessMedian * STAR_BRIGHTNESS_PROTECTION_SCALE
        )
        val writer = store.createFloatPlaneWriter("luminance-noise-map", baseline.width, baseline.height)
        val row = FloatArray(baseline.width)
        val histogram = LongArray(NOISE_HISTOGRAM_BINS)
        var eligible = 0L
        var protected = 0L
        var weightSum = 0.0
        var maximum = 0f
        try {
            FileBackedImageReader(baseline, cachedRows = 5).use { image ->
                FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 5).use { alpha ->
                    for (y in 0 until baseline.height) {
                        for (x in 0 until baseline.width) {
                            row[x] = 0f
                            if (
                                x == 0 || y == 0 || x == baseline.width - 1 || y == baseline.height - 1 ||
                                alpha.alphaAt(x, y) < FULL_SKY_ALPHA
                            ) continue
                            if (starCores.contains(x, y)) {
                                protected++
                                continue
                            }
                            val center = linearLuminance(image.argbAt(x, y))
                            val neighbors = floatArrayOf(
                                linearLuminance(image.argbAt(x - 1, y - 1)),
                                linearLuminance(image.argbAt(x, y - 1)),
                                linearLuminance(image.argbAt(x + 1, y - 1)),
                                linearLuminance(image.argbAt(x - 1, y)),
                                linearLuminance(image.argbAt(x + 1, y)),
                                linearLuminance(image.argbAt(x - 1, y + 1)),
                                linearLuminance(image.argbAt(x, y + 1)),
                                linearLuminance(image.argbAt(x + 1, y + 1))
                            )
                            val maximumNeighbor = neighbors.maxOrNull() ?: center
                            if (center > maximumNeighbor + peakLimit) {
                                protected++
                                continue
                            }
                            val neighborMean = neighbors.average().toFloat()
                            val residual = abs(center - neighborMean)
                            var squaredDifference = (center - neighborMean) * (center - neighborMean)
                            neighbors.forEach { neighbor ->
                                squaredDifference += (neighbor - neighborMean) * (neighbor - neighborMean)
                            }
                            val localDeviation = sqrt(squaredDifference / (neighbors.size + 1))
                            val noiseSignal = maxOf(residual, localDeviation * LOCAL_DEVIATION_WEIGHT)
                            val noiseWeight = smoothStep(noiseLow, noiseHigh, noiseSignal)
                            val darkWeight = 1f - smoothStep(darkStart, darkEnd, center)
                            val weight = (noiseWeight * darkWeight).coerceIn(0f, 1f)
                            if (weight <= MIN_MAP_WEIGHT) continue
                            row[x] = weight
                            eligible++
                            weightSum += weight
                            maximum = maxOf(maximum, weight)
                            histogram[(weight * histogram.lastIndex).roundToInt().coerceIn(0, histogram.lastIndex)]++
                        }
                        writer.writeRow(y, row)
                    }
                }
            }
            val plane = writer.finish()
            return PreparedLuminanceNoiseMap(
                plane = plane,
                metrics = ProtectedLuminanceDenoiseMetrics(
                    appliedStrength = MAXIMUM_BLEND,
                    noiseMapMean = if (eligible > 0L) (weightSum / eligible).toFloat() else 0f,
                    noiseMapP90 = histogramPercentile(histogram, eligible, 0.90f),
                    noiseMapMaximum = maximum,
                    eligiblePixelCount = eligible.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    changedPixelCount = 0,
                    protectedStarPixelCount = protected.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            )
        } catch (error: Throwable) {
            runCatching { writer.close() }
            runCatching { store.deleteTemporary(writer.plane) }
            throw error
        }
    }

    fun apply(
        input: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        confirmedStars: List<DetectedStar>,
        prepared: PreparedLuminanceNoiseMap,
        store: ResultCandidateStore
    ): ProtectedLuminanceDenoiseResult {
        require(input.width == prepared.plane.width && input.height == prepared.plane.height)
        require(input.width == effectiveSkyAlpha.width && input.height == effectiveSkyAlpha.height)
        if (prepared.metrics.eligiblePixelCount == 0) {
            val copyWriter = store.createTemporaryWriter("luminance-denoise-copy", input.width, input.height)
            try {
                FileBackedImageReader(input).use { image ->
                    val row = IntArray(input.width)
                    for (y in 0 until input.height) {
                        image.readArgbRow(y, row)
                        copyWriter.writeRow(y, row)
                    }
                }
                return ProtectedLuminanceDenoiseResult(copyWriter.finish(), prepared.metrics)
            } catch (error: Throwable) {
                runCatching { copyWriter.close() }
                runCatching { store.deleteTemporary(copyWriter.image) }
                throw error
            }
        }
        val sky = FileBackedImageReader(input).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
                statistics.calculate(image, alpha, confirmedStars)
            }
        }
        val starCores = StarCoreIndex.create(
            input.width,
            input.height,
            confirmedStars,
            radiusScale = STAR_PROTECTION_RADIUS_SCALE
        )
        val edgeLimit = maxOf(MIN_APPLY_EDGE_LIMIT, sky.luminanceMad * APPLY_EDGE_MAD_SCALE)
        val maximumChange = maxOf(MINIMUM_MAXIMUM_CHANGE, sky.luminanceMad * MAXIMUM_CHANGE_MAD_SCALE)
        val writer = store.createTemporaryWriter("luminance-denoised", input.width, input.height)
        val outputRow = IntArray(input.width)
        var changed = 0L
        try {
            FileBackedImageReader(input, cachedRows = 5).use { image ->
                FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 5).use { alpha ->
                    FileBackedFloatPlaneReader(prepared.plane, cachedRows = 5).use { noise ->
                        for (y in 0 until input.height) {
                            for (x in 0 until input.width) {
                                val color = image.argbAt(x, y)
                                outputRow[x] = color
                                if (
                                    x == 0 || y == 0 || x == input.width - 1 || y == input.height - 1 ||
                                    starCores.contains(x, y) || alpha.alphaAt(x, y) < FULL_SKY_ALPHA
                                ) continue
                                val mapWeight = noise.alphaAt(x, y)
                                if (mapWeight <= MIN_MAP_WEIGHT) continue
                                val center = linearLuminance(color)
                                if (center <= MINIMUM_LUMINANCE) continue
                                var weightedSum = center * CENTER_WEIGHT
                                var totalWeight = CENTER_WEIGHT
                                for (dy in -1..1) for (dx in -1..1) {
                                    if (dx == 0 && dy == 0) continue
                                    val neighbor = linearLuminance(image.argbAt(x + dx, y + dy))
                                    val difference = abs(neighbor - center)
                                    if (difference > edgeLimit) continue
                                    val weight = 1f / (1f + difference / edgeLimit)
                                    weightedSum += neighbor * weight
                                    totalWeight += weight
                                }
                                if (totalWeight <= CENTER_WEIGHT) continue
                                val localMean = weightedSum / totalWeight
                                val requestedChange = (localMean - center) * mapWeight * MAXIMUM_BLEND
                                val target = (center + requestedChange.coerceIn(-maximumChange, maximumChange))
                                    .coerceIn(MINIMUM_LUMINANCE, 1f)
                                val scale = target / center
                                val denoised = packLinear(
                                    linearChannel(color, 16) * scale,
                                    linearChannel(color, 8) * scale,
                                    linearChannel(color, 0) * scale
                                )
                                if (denoised != color) {
                                    outputRow[x] = denoised
                                    changed++
                                }
                            }
                            writer.writeRow(y, outputRow)
                        }
                    }
                }
            }
            return ProtectedLuminanceDenoiseResult(
                image = writer.finish(),
                metrics = prepared.metrics.copy(
                    changedPixelCount = changed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            )
        } catch (error: Throwable) {
            runCatching { writer.close() }
            runCatching { store.deleteTemporary(writer.image) }
            throw error
        }
    }

    private fun histogramPercentile(histogram: LongArray, count: Long, fraction: Float): Float {
        if (count <= 0L) return 0f
        val target = (count * fraction.coerceIn(0f, 1f)).toLong().coerceAtLeast(1L)
        var accumulated = 0L
        histogram.forEachIndexed { index, value ->
            accumulated += value
            if (accumulated >= target) return index.toFloat() / histogram.lastIndex
        }
        return 1f
    }

    companion object {
        private const val FULL_SKY_ALPHA = 0.98f
        private const val STAR_PROTECTION_RADIUS_SCALE = 2.4f
        private const val NOISE_LOW_MAD_SCALE = 0.05f
        private const val NOISE_HIGH_MAD_SCALE = 0.8f
        private const val PEAK_MAD_SCALE = 2.5f
        private const val DARK_MAD_SCALE = 8f
        private const val LOCAL_DEVIATION_WEIGHT = 0.8f
        private const val STAR_BRIGHTNESS_PROTECTION_SCALE = 0.65f
        private const val APPLY_EDGE_MAD_SCALE = 5f
        private const val MAXIMUM_CHANGE_MAD_SCALE = 0.9f
        private const val MIN_NOISE_LOW = 0.00008f
        private const val MIN_NOISE_HIGH = 0.0007f
        private const val MIN_PEAK_LIMIT = 0.0015f
        private const val MIN_DARK_RANGE = 0.018f
        private const val MIN_DARK_FADE_RANGE = 0.04f
        private const val MIN_APPLY_EDGE_LIMIT = 0.018f
        private const val MINIMUM_MAXIMUM_CHANGE = 0.0012f
        private const val MINIMUM_LUMINANCE = 1e-6f
        private const val MIN_MAP_WEIGHT = 0.005f
        private const val CENTER_WEIGHT = 2f
        private const val MAXIMUM_BLEND = 0.68f
        private const val NOISE_HISTOGRAM_BINS = 257
    }
}
