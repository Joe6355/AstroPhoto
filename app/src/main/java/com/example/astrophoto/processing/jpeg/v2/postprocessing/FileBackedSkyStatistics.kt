package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class StarCoreIndex(
    private val width: Int,
    private val rows: Array<List<Interval>>
) {
    data class Interval(val left: Int, val right: Int, val centerX: Int, val centerY: Int, val radiusSquared: Int)

    fun contains(x: Int, y: Int): Boolean =
        y in rows.indices && x in 0 until width && rows[y].any { interval ->
            x in interval.left..interval.right &&
                (x - interval.centerX) * (x - interval.centerX) +
                (y - interval.centerY) * (y - interval.centerY) <= interval.radiusSquared
        }

    companion object {
        fun create(
            width: Int,
            height: Int,
            stars: List<DetectedStar>,
            radiusScale: Float = 1.8f
        ): StarCoreIndex {
            val mutableRows = Array(height) { mutableListOf<Interval>() }
            stars.forEach { star ->
                if (!star.confidence.isFinite() || star.confidence < 0.15f) return@forEach
                val radius = ceil(maxOf(2f, star.width * radiusScale)).toInt().coerceAtMost(8)
                val centerX = star.x.roundToInt()
                val centerY = star.y.roundToInt()
                for (y in maxOf(0, centerY - radius)..minOf(height - 1, centerY + radius)) {
                    mutableRows[y] += Interval(
                        left = maxOf(0, centerX - radius),
                        right = minOf(width - 1, centerX + radius),
                        centerX = centerX,
                        centerY = centerY,
                        radiusSquared = radius * radius
                    )
                }
            }
            return StarCoreIndex(width, Array(height) { mutableRows[it].toList() })
        }
    }
}

class FileBackedSkyStatistics {
    fun calculate(
        image: ArgbPixelSource,
        effectiveSkyAlpha: AlphaPixelSource,
        stars: List<DetectedStar> = emptyList()
    ): SkyStatisticsResult {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val starCores = StarCoreIndex.create(image.width, image.height, stars)
        val luminanceHistogram = IntArray(HISTOGRAM_BINS)
        val redHistogram = IntArray(HISTOGRAM_BINS)
        val greenHistogram = IntArray(HISTOGRAM_BINS)
        val blueHistogram = IntArray(HISTOGRAM_BINS)
        val clipping = LongArray(3)
        val grid = Grid.create(image.width, image.height)
        val gridHistograms = IntArray(grid.cellCount * GRADIENT_BINS)
        val gridCounts = IntArray(grid.cellCount)
        var skyCount = 0L
        var backgroundCount = 0L
        var chromaDifference = 0.0
        var chromaPairs = 0L

        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (effectiveSkyAlpha.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD) continue
            skyCount++
            val color = image.argbAt(x, y)
            val red = linearChannel(color, 16)
            val green = linearChannel(color, 8)
            val blue = linearChannel(color, 0)
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            if (red <= CLIP_LIMIT || red >= 1f - CLIP_LIMIT) clipping[0]++
            if (green <= CLIP_LIMIT || green >= 1f - CLIP_LIMIT) clipping[1]++
            if (blue <= CLIP_LIMIT || blue >= 1f - CLIP_LIMIT) clipping[2]++
            if (starCores.contains(x, y) || maxOf(red, green, blue) >= SATURATED_BACKGROUND_LIMIT) continue
            backgroundCount++
            luminanceHistogram[histogramBin(luminance, HISTOGRAM_BINS)]++
            redHistogram[histogramBin(red, HISTOGRAM_BINS)]++
            greenHistogram[histogramBin(green, HISTOGRAM_BINS)]++
            blueHistogram[histogramBin(blue, HISTOGRAM_BINS)]++
            val cell = grid.cellIndex(x, y)
            gridHistograms[cell * GRADIENT_BINS + histogramBin(luminance, GRADIENT_BINS)]++
            gridCounts[cell]++
            if (x > 0 && effectiveSkyAlpha.alphaAt(x - 1, y) >= STATISTICS_ALPHA_THRESHOLD &&
                !starCores.contains(x - 1, y)
            ) {
                val neighbor = image.argbAt(x - 1, y)
                val nr = linearChannel(neighbor, 16)
                val ng = linearChannel(neighbor, 8)
                val nb = linearChannel(neighbor, 0)
                val neighborLuminance = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
                if (abs(luminance - neighborLuminance) <= CHROMA_EDGE_LIMIT) {
                    chromaDifference += hypot(
                        ((red - luminance) - (nr - neighborLuminance)).toDouble(),
                        ((blue - luminance) - (nb - neighborLuminance)).toDouble()
                    )
                    chromaPairs++
                }
            }
        }
        if (backgroundCount == 0L || skyCount == 0L) return SkyStatisticsResult.EMPTY

        val median = histogramPercentile(luminanceHistogram, backgroundCount, 0.50f)
        val channelMedian = LinearRgb(
            histogramPercentile(redHistogram, backgroundCount, 0.50f),
            histogramPercentile(greenHistogram, backgroundCount, 0.50f),
            histogramPercentile(blueHistogram, backgroundCount, 0.50f)
        )
        val luminanceDeviation = IntArray(HISTOGRAM_BINS)
        val redDeviation = IntArray(HISTOGRAM_BINS)
        val greenDeviation = IntArray(HISTOGRAM_BINS)
        val blueDeviation = IntArray(HISTOGRAM_BINS)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (effectiveSkyAlpha.alphaAt(x, y) < STATISTICS_ALPHA_THRESHOLD || starCores.contains(x, y)) continue
            val color = image.argbAt(x, y)
            val red = linearChannel(color, 16)
            val green = linearChannel(color, 8)
            val blue = linearChannel(color, 0)
            if (maxOf(red, green, blue) >= SATURATED_BACKGROUND_LIMIT) continue
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            luminanceDeviation[histogramBin(abs(luminance - median), HISTOGRAM_BINS)]++
            redDeviation[histogramBin(abs(red - channelMedian.red), HISTOGRAM_BINS)]++
            greenDeviation[histogramBin(abs(green - channelMedian.green), HISTOGRAM_BINS)]++
            blueDeviation[histogramBin(abs(blue - channelMedian.blue), HISTOGRAM_BINS)]++
        }
        val mad = histogramPercentile(luminanceDeviation, backgroundCount, 0.50f)
        val channelMad = LinearRgb(
            histogramPercentile(redDeviation, backgroundCount, 0.50f),
            histogramPercentile(greenDeviation, backgroundCount, 0.50f),
            histogramPercentile(blueDeviation, backgroundCount, 0.50f)
        )
        val gradient = gradientEstimate(grid, gridHistograms, gridCounts, mad)
        val reliableStars = stars.filter { star ->
            val starX = star.x.roundToInt()
            val starY = star.y.roundToInt()
            star.confidence >= MIN_RELIABLE_STAR_CONFIDENCE &&
                star.width in MIN_RELIABLE_STAR_WIDTH..MAX_RELIABLE_STAR_WIDTH &&
                star.ellipticity <= MAX_RELIABLE_STAR_ELLIPTICITY &&
                starX in 0 until image.width && starY in 0 until image.height &&
                effectiveSkyAlpha.alphaAt(starX, starY) >= STATISTICS_ALPHA_THRESHOLD
        }
        val starBrightness = reliableStars.map { star ->
            linearLuminance(
                image.argbAt(
                    star.x.roundToInt().coerceIn(0, image.width - 1),
                    star.y.roundToInt().coerceIn(0, image.height - 1)
                )
            )
        }
        val starContrast = reliableStars.map { (it.localContrast / 255f).coerceIn(0f, 1f) }
        val skyRatio = skyCount.toFloat() / (image.width.toLong() * image.height).coerceAtLeast(1L)
        val low = histogramPercentile(luminanceHistogram, backgroundCount, LOW_PERCENTILE)
        val high = histogramPercentile(luminanceHistogram, backgroundCount, HIGH_PERCENTILE)
        val starHigh = sortedPercentile(starBrightness, 0.90f)
        val brightCore = sortedPercentile(starBrightness, 0.97f)
        return SkyStatisticsResult(
            skyPixelCount = skyCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            skyCoverageRatio = skyRatio,
            luminanceMedian = median,
            luminanceMad = mad,
            lowPercentile = low,
            highPercentile = high,
            channelMedian = channelMedian,
            channelMad = channelMad,
            channelClippingPercent = LinearRgb(
                clipping[0] * 100f / skyCount,
                clipping[1] * 100f / skyCount,
                clipping[2] * 100f / skyCount
            ),
            chromaNoiseEstimate = if (chromaPairs > 0L) (chromaDifference / chromaPairs).toFloat() else 0f,
            largeScaleGradientStrength = gradient.strength,
            gradientDirectionX = gradient.directionX,
            gradientDirectionY = gradient.directionY,
            starBrightnessMedian = sortedPercentile(starBrightness, 0.50f),
            starBrightnessHighPercentile = starHigh,
            reliableStarCount = reliableStars.size,
            medianStarLocalContrast = sortedPercentile(starContrast, 0.50f),
            brightStarCorePercentile = brightCore,
            estimatedBlackPoint = (median - maxOf(2.5f * mad, MIN_BLACK_OFFSET)).coerceAtLeast(0f),
            estimatedSafeWhitePoint = maxOf(
                MIN_SAFE_WHITE_POINT,
                high + MIN_WHITE_HEADROOM,
                starHigh * 1.06f,
                brightCore
            ).coerceAtMost(1f),
            confidence = (
                (backgroundCount / MIN_CONFIDENT_SAMPLE_COUNT.toFloat()).coerceIn(0f, 1f) * 0.48f +
                    (skyRatio / MIN_CONFIDENT_SKY_RATIO).coerceIn(0f, 1f) * 0.34f +
                    (backgroundCount.toFloat() / skyCount).coerceIn(0f, 1f) * 0.18f
                ).coerceIn(0f, 1f)
        )
    }

    private data class GradientEstimate(val strength: Float, val directionX: Float, val directionY: Float)

    private fun gradientEstimate(
        grid: Grid,
        histograms: IntArray,
        counts: IntArray,
        globalMad: Float
    ): GradientEstimate {
        val medians = FloatArray(grid.cellCount) { Float.NaN }
        counts.indices.forEach { cell ->
            if (counts[cell] < MIN_GRADIENT_CELL_SAMPLES) return@forEach
            val histogram = histograms.copyOfRange(cell * GRADIENT_BINS, (cell + 1) * GRADIENT_BINS)
            val p16 = histogramPercentile(histogram, counts[cell].toLong(), 0.16f)
            val p84 = histogramPercentile(histogram, counts[cell].toLong(), 0.84f)
            if (p84 - p16 <= maxOf(MIN_CELL_TEXTURE_LIMIT, globalMad * 8f)) {
                medians[cell] = histogramPercentile(histogram, counts[cell].toLong(), 0.50f)
            }
        }
        fun halfAverage(horizontal: Boolean, upperOrLeft: Boolean): Float {
            var sum = 0f
            var count = 0
            for (row in 0 until grid.rows) for (column in 0 until grid.columns) {
                val included = if (horizontal) {
                    if (upperOrLeft) column < grid.columns / 2 else column >= grid.columns / 2
                } else {
                    if (upperOrLeft) row < grid.rows / 2 else row >= grid.rows / 2
                }
                val value = medians[row * grid.columns + column]
                if (included && value.isFinite()) {
                    sum += value
                    count++
                }
            }
            return if (count > 0) sum / count else 0f
        }
        val directionX = halfAverage(true, false) - halfAverage(true, true)
        val directionY = halfAverage(false, false) - halfAverage(false, true)
        return GradientEstimate(hypot(directionX, directionY), directionX, directionY)
    }

    private data class Grid(val columns: Int, val rows: Int, val width: Int, val height: Int) {
        val cellCount = columns * rows
        fun cellIndex(x: Int, y: Int): Int {
            val column = (x.toLong() * columns / width).toInt().coerceIn(0, columns - 1)
            val row = (y.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
            return row * columns + column
        }
        companion object {
            fun create(width: Int, height: Int) = Grid(
                (width / TARGET_GRADIENT_CELL_SIZE).coerceIn(4, 20),
                (height / TARGET_GRADIENT_CELL_SIZE).coerceIn(3, 14),
                width,
                height
            )
        }
    }

    companion object {
        private const val HISTOGRAM_BINS = 4096
        private const val GRADIENT_BINS = 256
        private const val TARGET_GRADIENT_CELL_SIZE = 256
        private const val MIN_GRADIENT_CELL_SAMPLES = 12
        private const val LOW_PERCENTILE = 0.05f
        private const val HIGH_PERCENTILE = 0.995f
        private const val CLIP_LIMIT = 0.0001f
        private const val SATURATED_BACKGROUND_LIMIT = 0.985f
        private const val CHROMA_EDGE_LIMIT = 0.035f
        private const val MIN_CELL_TEXTURE_LIMIT = 0.025f
        private const val MIN_CONFIDENT_SAMPLE_COUNT = 4096
        private const val MIN_CONFIDENT_SKY_RATIO = 0.08f
        private const val MIN_RELIABLE_STAR_CONFIDENCE = 0.25f
        private const val MIN_RELIABLE_STAR_WIDTH = 0.55f
        private const val MAX_RELIABLE_STAR_WIDTH = 4.8f
        private const val MAX_RELIABLE_STAR_ELLIPTICITY = 0.78f
        private const val MIN_BLACK_OFFSET = 1f / 4095f
        private const val MIN_SAFE_WHITE_POINT = 0.42f
        private const val MIN_WHITE_HEADROOM = 0.12f
    }
}
