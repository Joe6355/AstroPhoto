package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactMask
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.postprocessing.FileBackedSkyStatistics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.histogramBin
import com.example.astrophoto.processing.jpeg.v2.postprocessing.histogramPercentile
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FileBackedResultQualityAnalyzer(
    private val starDetector: JpegStarDetector = JpegStarDetector(),
    private val skyStatistics: FileBackedSkyStatistics = FileBackedSkyStatistics(),
    private val staticArtifactMask: StaticArtifactMask? = null
) {
    fun analyze(
        image: FileBackedImage,
        reference: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane
    ): ResultQualityMetrics {
        require(image.width == reference.width && image.height == reference.height)
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val sample = sampleForStarDetection(image, effectiveSkyAlpha)
        val sampleArtifactMask = staticArtifactMask?.scaledTo(sample.image.width, sample.image.height)
        val sampleStars = starDetector.detect(
            sample.image,
            sample.binarySky,
            staticArtifactMask = sampleArtifactMask
        ).stars
        val scaleX = image.width.toFloat() / sample.image.width
        val scaleY = image.height.toFloat() / sample.image.height
        val widthScale = (scaleX + scaleY) * 0.5f
        val fullStars = sampleStars.map { star ->
            star.copy(x = star.x * scaleX, y = star.y * scaleY, width = star.width * widthScale)
        }
        val sky = FileBackedImageReader(image).use { imageReader ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha).use { alpha ->
                skyStatistics.calculate(imageReader, alpha, fullStars)
            }
        }
        val starWidths = fullStars.map { it.width }.sorted()
        val starEllipticities = fullStars.map { it.ellipticity }.sorted()
        val starContrasts = fullStars.map { it.localContrast }.sorted()
        val clippedStars = sampleStars.count { star ->
            val x = star.x.roundToInt().coerceIn(0, sample.image.width - 1)
            val y = star.y.roundToInt().coerceIn(0, sample.image.height - 1)
            maximumChannel(sample.image.pixelAt(x, y)) >= BRIGHT_CLIP_CHANNEL
        }
        val incremental = incrementalMetrics(image, reference, effectiveSkyAlpha, sky.luminanceMedian, sky.luminanceMad)
        return ResultQualityMetrics(
            width = image.width,
            height = image.height,
            aspectRatio = image.width.toDouble() / image.height,
            retainedValidAreaRatio = incremental.validPixels.toFloat() /
                (image.width.toLong() * image.height).coerceAtLeast(1L),
            reliableStarCount = fullStars.size,
            medianStarLocalContrast = median(starContrasts),
            medianStarWidth = median(starWidths),
            medianStarEllipticity = median(starEllipticities),
            brightStarClippingPercent = if (fullStars.isEmpty()) 0f else clippedStars * 100f / fullStars.size,
            suspiciousPointCount = incremental.suspiciousPoints,
            skyMedian = sky.luminanceMedian,
            skyMad = sky.luminanceMad,
            skyLowPercentile = sky.lowPercentile,
            skyHighPercentile = sky.highPercentile,
            channelMedian = sky.channelMedian,
            channelClippingPercent = sky.channelClippingPercent,
            chromaNoiseEstimate = sky.chromaNoiseEstimate,
            banding = fileBackedBanding(image, effectiveSkyAlpha, sky.luminanceMad),
            gradientResidual = sky.largeScaleGradientStrength,
            foregroundSharpness = incremental.foregroundSharpness,
            foregroundEdgeDifference = incremental.foregroundEdgeDifference,
            foregroundMeanPixelDifference = incremental.foregroundMeanDifference,
            foregroundMaximumPixelDifference = incremental.foregroundMaximumDifference,
            invalidBorderRatio = incremental.invalidBorder.toFloat() / incremental.borderPixels.coerceAtLeast(1L),
            blackBorderRatio = incremental.blackBorder.toFloat() / incremental.borderPixels.coerceAtLeast(1L),
            processingConfidence = sky.confidence
        )
    }

    fun detectStars(
        image: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane
    ): List<DetectedStar> {
        val sample = sampleForStarDetection(image, effectiveSkyAlpha)
        val detected = starDetector.detect(
            sample.image,
            sample.binarySky,
            staticArtifactMask = staticArtifactMask?.scaledTo(sample.image.width, sample.image.height)
        ).stars
        val scaleX = image.width.toFloat() / sample.image.width
        val scaleY = image.height.toFloat() / sample.image.height
        val widthScale = (scaleX + scaleY) * 0.5f
        return detected.map { it.copy(x = it.x * scaleX, y = it.y * scaleY, width = it.width * widthScale) }
    }

    private data class StarSample(val image: ArgbPixelImage, val binarySky: SkyMask)

    private fun sampleForStarDetection(
        image: FileBackedImage,
        alphaPlane: FileBackedFloatPlane
    ): StarSample {
        val scale = minOf(1f, MAX_STAR_ANALYSIS_DIMENSION.toFloat() / maxOf(image.width, image.height))
        val width = maxOf(1, (image.width * scale).roundToInt())
        val height = maxOf(1, (image.height * scale).roundToInt())
        val pixels = IntArray(width * height)
        val sky = BooleanArray(width * height)
        FileBackedImageReader(image).use { reader ->
            FileBackedFloatPlaneReader(alphaPlane).use { alpha ->
                for (y in 0 until height) for (x in 0 until width) {
                    val sourceX = (x.toLong() * image.width / width).toInt().coerceIn(0, image.width - 1)
                    val sourceY = (y.toLong() * image.height / height).toInt().coerceIn(0, image.height - 1)
                    val index = y * width + x
                    pixels[index] = reader.argbAt(sourceX, sourceY)
                    sky[index] = alpha.alphaAt(sourceX, sourceY) >= SKY_ALPHA_THRESHOLD
                }
            }
        }
        return StarSample(ArgbPixelImage(width, height, pixels), SkyMask(width, height, sky))
    }

    private data class IncrementalMetrics(
        val validPixels: Long,
        val foregroundSharpness: Float,
        val foregroundEdgeDifference: Float,
        val foregroundMeanDifference: Float,
        val foregroundMaximumDifference: Int,
        val borderPixels: Long,
        val invalidBorder: Long,
        val blackBorder: Long,
        val suspiciousPoints: Int
    )

    private fun incrementalMetrics(
        image: FileBackedImage,
        reference: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        background: Float,
        mad: Float
    ): IncrementalMetrics {
        var valid = 0L
        var sharpnessSum = 0L
        var edgeDifferenceSum = 0L
        var edgeCount = 0L
        var pixelDifferenceSum = 0L
        var foregroundCount = 0L
        var maximumDifference = 0
        var borderPixels = 0L
        var invalidBorder = 0L
        var blackBorder = 0L
        var suspicious = 0
        val band = maxOf(1, minOf(image.width, image.height) / 80)
        val suspiciousThreshold = background + maxOf(mad * 8f, MIN_SUSPICIOUS_DETAIL)
        FileBackedImageReader(image, cachedRows = 5).use { candidate ->
            FileBackedImageReader(reference, cachedRows = 5).use { referenceReader ->
                FileBackedFloatPlaneReader(alphaPlane, cachedRows = 5).use { alpha ->
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        val color = candidate.argbAt(x, y)
                        if (color ushr 24 and 0xFF == 255) valid++
                        val isBorder = x < band || x >= image.width - band || y < band || y >= image.height - band
                        if (isBorder) {
                            borderPixels++
                            if (color ushr 24 and 0xFF != 255) invalidBorder++
                            if (pixelLuminance(color) <= BLACK_BORDER_LUMINANCE) blackBorder++
                        }
                        if (alpha.alphaAt(x, y) <= FOREGROUND_ALPHA_THRESHOLD) {
                            val difference = maximumChannelDifference(color, referenceReader.argbAt(x, y))
                            pixelDifferenceSum += difference
                            foregroundCount++
                            maximumDifference = maxOf(maximumDifference, difference)
                            fun edge(nx: Int, ny: Int) {
                                if (nx !in 0 until image.width || ny !in 0 until image.height ||
                                    alpha.alphaAt(nx, ny) > FOREGROUND_ALPHA_THRESHOLD
                                ) return
                                val candidateEdge = abs(pixelLuminance(color) - pixelLuminance(candidate.argbAt(nx, ny)))
                                val referenceEdge = abs(
                                    pixelLuminance(referenceReader.argbAt(x, y)) -
                                        pixelLuminance(referenceReader.argbAt(nx, ny))
                                )
                                sharpnessSum += candidateEdge
                                edgeDifferenceSum += abs(candidateEdge - referenceEdge)
                                edgeCount++
                            }
                            edge(x + 1, y)
                            edge(x, y + 1)
                        }
                        if (x in 1 until image.width - 1 && y in 1 until image.height - 1 &&
                            alpha.alphaAt(x, y) >= SKY_ALPHA_THRESHOLD &&
                            qualityLinearLuminance(color) >= suspiciousThreshold &&
                            isSuspicious(candidate, x, y, color, background)
                        ) suspicious++
                    }
                }
            }
        }
        return IncrementalMetrics(
            valid,
            sharpnessSum.toFloat() / edgeCount.coerceAtLeast(1L),
            edgeDifferenceSum.toFloat() / edgeCount.coerceAtLeast(1L),
            pixelDifferenceSum.toFloat() / foregroundCount.coerceAtLeast(1L),
            maximumDifference,
            borderPixels,
            invalidBorder,
            blackBorder,
            suspicious
        )
    }

    private fun isSuspicious(
        image: FileBackedImageReader,
        x: Int,
        y: Int,
        color: Int,
        background: Float
    ): Boolean {
        val luminance = qualityLinearLuminance(color)
        var localMaximum = true
        var support = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val neighbor = qualityLinearLuminance(image.argbAt(x + dx, y + dy))
            if (neighbor > luminance) localMaximum = false
            if (neighbor - background >= (luminance - background) * MIN_POINT_SUPPORT_FRACTION) support++
        }
        if (!localMaximum) return false
        val deltas = floatArrayOf(
            qualityLinearChannel(color, 16) - background,
            qualityLinearChannel(color, 8) - background,
            qualityLinearChannel(color, 0) - background
        ).sortedDescending()
        val singleChannel = deltas[0] > MIN_SINGLE_CHANNEL_DETAIL &&
            deltas[1] < deltas[0] * MAX_SECOND_CHANNEL_FRACTION
        return support == 0 || singleChannel
    }

    private fun fileBackedBanding(
        image: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        skyMad: Float
    ): BandingMetrics {
        val rowHistograms = IntArray(image.height * BANDING_BINS)
        val columnHistograms = IntArray(image.width * BANDING_BINS)
        val rowCounts = IntArray(image.height)
        val columnCounts = IntArray(image.width)
        FileBackedImageReader(image).use { reader ->
            FileBackedFloatPlaneReader(alphaPlane).use { alpha ->
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (alpha.alphaAt(x, y) < SKY_ALPHA_THRESHOLD) continue
                    val bin = histogramBin(qualityLinearLuminance(reader.argbAt(x, y)), BANDING_BINS)
                    rowHistograms[y * BANDING_BINS + bin]++
                    columnHistograms[x * BANDING_BINS + bin]++
                    rowCounts[y]++
                    columnCounts[x]++
                }
            }
        }
        val rows = lineMedians(rowHistograms, rowCounts, maxOf(4, (image.width * MIN_LINE_SKY_FRACTION).toInt()))
        val columns = lineMedians(
            columnHistograms,
            columnCounts,
            maxOf(4, (image.height * MIN_LINE_SKY_FRACTION).toInt())
        )
        val normalization = maxOf(skyMad * MAD_NORMALIZATION, MIN_NORMALIZATION)
        val horizontal = normalizedResidual(rows, normalization)
        val vertical = normalizedResidual(columns, normalization)
        return BandingMetrics(horizontal, vertical, sqrt(horizontal * horizontal + vertical * vertical))
    }

    private fun lineMedians(histograms: IntArray, counts: IntArray, minimum: Int): FloatArray =
        FloatArray(counts.size) { line ->
            if (counts[line] < minimum) Float.NaN else histogramPercentile(
                histograms.copyOfRange(line * BANDING_BINS, (line + 1) * BANDING_BINS),
                counts[line].toLong(),
                0.5f
            )
        }

    private fun normalizedResidual(values: FloatArray, normalization: Float): Float {
        val radius = (values.size / 24).coerceIn(2, 48)
        var residual = 0f
        var abrupt = 0f
        var count = 0
        values.indices.forEach { index ->
            val value = values[index]
            if (!value.isFinite()) return@forEach
            var sum = 0f
            var samples = 0
            for (neighbor in maxOf(0, index - radius)..minOf(values.lastIndex, index + radius)) {
                if (values[neighbor].isFinite()) {
                    sum += values[neighbor]
                    samples++
                }
            }
            if (samples < 3) return@forEach
            residual += abs(value - sum / samples)
            if (index > 0 && index < values.lastIndex && values[index - 1].isFinite() && values[index + 1].isFinite()) {
                abrupt += abs(value - (values[index - 1] + values[index + 1]) * 0.5f)
            }
            count++
        }
        return if (count == 0) 0f else ((residual + abrupt * 0.75f) / count / normalization).coerceAtLeast(0f)
    }

    private fun median(values: List<Float>): Float = when {
        values.isEmpty() -> 0f
        values.size % 2 == 1 -> values[values.size / 2]
        else -> (values[values.size / 2 - 1] + values[values.size / 2]) * 0.5f
    }

    private fun maximumChannel(color: Int) = maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF)
    private fun maximumChannelDifference(first: Int, second: Int) = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    companion object {
        private const val MAX_STAR_ANALYSIS_DIMENSION = 960
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val FOREGROUND_ALPHA_THRESHOLD = 0.001f
        private const val BRIGHT_CLIP_CHANNEL = 254
        private const val BLACK_BORDER_LUMINANCE = 2
        private const val MIN_SUSPICIOUS_DETAIL = 0.008f
        private const val MIN_POINT_SUPPORT_FRACTION = 0.22f
        private const val MIN_SINGLE_CHANNEL_DETAIL = 0.04f
        private const val MAX_SECOND_CHANNEL_FRACTION = 0.30f
        private const val BANDING_BINS = 256
        private const val MIN_LINE_SKY_FRACTION = 0.08f
        private const val MAD_NORMALIZATION = 1.4826f
        private const val MIN_NORMALIZATION = 0.002f
    }
}
