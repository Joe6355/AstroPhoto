package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedSkyStatistics
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.StarCoreIndex
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.linearChannel
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.linearLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.smoothStep
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
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
    val protectedStarPixelCount: Int,
    val highFrequencyNoiseMad: Float = 0f,
    val noiseEstimateSampleCount: Int = 0
) {
    companion object {
        val NONE = ProtectedLuminanceDenoiseMetrics(0f, 0f, 0f, 0f, 0, 0, 0)
    }
}

data class PreparedLuminanceNoiseMap(
    val plane: FileBackedFloatPlane,
    val metrics: ProtectedLuminanceDenoiseMetrics,
    val baselineStatistics: SkyStatisticsResult? = null
)

data class ProtectedLuminanceDenoiseResult(
    /** May be the input itself when no pixels qualify; callers must retain that file. */
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
        val noiseEstimate = estimateHighFrequencyNoise(baseline, effectiveSkyAlpha, starCores)
        val adaptive = noiseEstimate.samples >= MINIMUM_NOISE_SAMPLES
        val legacyLow = maxOf(MIN_NOISE_LOW, sky.luminanceMad * NOISE_LOW_MAD_SCALE)
        val legacyHigh = maxOf(MIN_NOISE_HIGH, sky.luminanceMad * NOISE_HIGH_MAD_SCALE, legacyLow * 2f)
        val noiseLow = if (adaptive) {
            minOf(legacyLow, maxOf(LINEAR_QUANTUM * 0.5f, noiseEstimate.mad * 0.25f))
        } else legacyLow
        val noiseHigh = if (adaptive) {
            minOf(legacyHigh, maxOf(LINEAR_QUANTUM * 2f, noiseEstimate.mad * 2f, noiseLow * 2f))
        } else legacyHigh
        val legacyPeakLimit = maxOf(MIN_PEAK_LIMIT, sky.luminanceMad * PEAK_MAD_SCALE)
        val peakLimit = if (adaptive) {
            minOf(legacyPeakLimit, maxOf(LINEAR_QUANTUM * 2f, noiseEstimate.mad * 3f))
        } else legacyPeakLimit
        val darkStart = maxOf(
            sky.highPercentile,
            sky.luminanceMedian + maxOf(sky.luminanceMad * DARK_MAD_SCALE, MIN_DARK_RANGE)
        )
        val darkEnd = maxOf(
            darkStart + MIN_DARK_FADE_RANGE,
            sky.starBrightnessMedian * STAR_BRIGHTNESS_PROTECTION_SCALE
        )
        val row = FloatArray(baseline.width)
        val histogram = LongArray(NOISE_HISTOGRAM_BINS)
        val neighbors = FloatArray(8)
        var eligible = 0L
        var protected = 0L
        var weightSum = 0.0
        var maximum = 0f
        val writer = store.createFloatPlaneWriter("luminance-noise-map", baseline.width, baseline.height)
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
                            val center = linearLuminance(image.linearRgbAt(x, y))
                            neighbors[0] = linearLuminance(image.linearRgbAt(x - 1, y - 1))
                            neighbors[1] = linearLuminance(image.linearRgbAt(x, y - 1))
                            neighbors[2] = linearLuminance(image.linearRgbAt(x + 1, y - 1))
                            neighbors[3] = linearLuminance(image.linearRgbAt(x - 1, y))
                            neighbors[4] = linearLuminance(image.linearRgbAt(x + 1, y))
                            neighbors[5] = linearLuminance(image.linearRgbAt(x - 1, y + 1))
                            neighbors[6] = linearLuminance(image.linearRgbAt(x, y + 1))
                            neighbors[7] = linearLuminance(image.linearRgbAt(x + 1, y + 1))
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
                baselineStatistics = sky,
                metrics = ProtectedLuminanceDenoiseMetrics(
                    appliedStrength = MAXIMUM_BLEND,
                    noiseMapMean = if (eligible > 0L) (weightSum / eligible).toFloat() else 0f,
                    noiseMapP90 = histogramPercentile(histogram, eligible, 0.90f),
                    noiseMapMaximum = maximum,
                    eligiblePixelCount = eligible.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    changedPixelCount = 0,
                    protectedStarPixelCount = protected.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    highFrequencyNoiseMad = noiseEstimate.mad,
                    noiseEstimateSampleCount = noiseEstimate.samples
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
            return ProtectedLuminanceDenoiseResult(input, prepared.metrics)
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
        val adaptive = prepared.metrics.noiseEstimateSampleCount >= MINIMUM_NOISE_SAMPLES
        val legacyEdgeLimit = maxOf(MIN_APPLY_EDGE_LIMIT, sky.luminanceMad * APPLY_EDGE_MAD_SCALE)
        val edgeLimit = if (adaptive) {
            minOf(legacyEdgeLimit, maxOf(LINEAR_QUANTUM * 8f, prepared.metrics.highFrequencyNoiseMad * 12f))
        } else legacyEdgeLimit
        val legacyMaximumChange = maxOf(MINIMUM_MAXIMUM_CHANGE, sky.luminanceMad * MAXIMUM_CHANGE_MAD_SCALE)
        val maximumChange = if (adaptive) {
            minOf(legacyMaximumChange, maxOf(LINEAR_QUANTUM, prepared.metrics.highFrequencyNoiseMad * 2f))
        } else legacyMaximumChange
        val outputRow = LongArray(input.width)
        var changed = 0L
        val writer = store.createTemporaryWriter("luminance-denoised", input.width, input.height)
        try {
            FileBackedImageReader(input, cachedRows = 5).use { image ->
                FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 5).use { alpha ->
                    FileBackedFloatPlaneReader(prepared.plane, cachedRows = 5).use { noise ->
                        for (y in 0 until input.height) {
                            for (x in 0 until input.width) {
                                val color = image.linearRgbAt(x, y)
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
                                    if (alpha.alphaAt(x + dx, y + dy) < FULL_SKY_ALPHA) continue
                                    val neighbor = linearLuminance(image.linearRgbAt(x + dx, y + dy))
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
                                val denoised = LinearRgb16.pack(
                                    linearChannel(color, 16) * scale,
                                    linearChannel(color, 8) * scale,
                                    linearChannel(color, 0) * scale
                                )
                                if (denoised != color) {
                                    outputRow[x] = denoised
                                    changed++
                                }
                            }
                            writer.writeLinearRow(y, outputRow)
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

    private data class NoiseEstimate(val mad: Float, val samples: Int)

    /** The diagonal difference cancels a planar sky gradient; no RGB8 or coarse sky histogram is involved. */
    private fun estimateHighFrequencyNoise(
        baseline: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane,
        starCores: StarCoreIndex
    ): NoiseEstimate {
        val histogram = LongArray(NOISE_ESTIMATE_BINS)
        val step = maxOf(1, maxOf(baseline.width, baseline.height) / NOISE_ESTIMATE_MAX_DIMENSION)
        var count = 0L
        FileBackedImageReader(baseline, cachedRows = 2).use { image ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 2).use { alpha ->
                for (y in 0 until baseline.height - 1 step step) for (x in 0 until baseline.width - 1 step step) {
                    if ((0..1).any { dy -> (0..1).any { dx ->
                            alpha.alphaAt(x + dx, y + dy) < FULL_SKY_ALPHA || starCores.contains(x + dx, y + dy)
                        } }) continue
                    val difference = linearLuminance(image.linearRgbAt(x, y)) -
                        linearLuminance(image.linearRgbAt(x + 1, y)) -
                        linearLuminance(image.linearRgbAt(x, y + 1)) +
                        linearLuminance(image.linearRgbAt(x + 1, y + 1))
                    histogram[(abs(difference) / NOISE_ESTIMATE_BIN_WIDTH).roundToInt()
                        .coerceIn(0, histogram.lastIndex)]++
                    count++
                }
            }
        }
        if (count == 0L) return NoiseEstimate(0f, 0)
        val medianBin = histogramPercentile(histogram, count, 0.5f) * histogram.lastIndex
        return NoiseEstimate(medianBin * NOISE_ESTIMATE_BIN_WIDTH, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
        private const val LINEAR_QUANTUM = 1f / 65535f
        private const val NOISE_ESTIMATE_BIN_WIDTH = LINEAR_QUANTUM / 4f
        private const val NOISE_ESTIMATE_BINS = 16_385
        private const val NOISE_ESTIMATE_MAX_DIMENSION = 512
        private const val MINIMUM_NOISE_SAMPLES = 256
    }
}
