package com.joe6355.astrophoto.processing.jpeg.v2.quality

import com.joe6355.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.histogramBin
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import kotlin.math.abs
import kotlin.math.sqrt

/** Measures the actual RGB8 output with fine linear-luminance medians, using bounded histograms. */
internal class FileBackedOutputBandingEstimator {
    fun estimate(image: FileBackedImage, alpha: FileBackedFloatPlane, skyMad: Float): BandingMetrics =
        FileBackedImageReader(image).use { reader ->
            FileBackedFloatPlaneReader(alpha).use { sky -> estimate(reader, sky, skyMad) }
        }

    fun estimate(image: ArgbPixelSource, alpha: AlphaPixelSource, skyMad: Float): BandingMetrics {
        require(image.width == alpha.width && image.height == alpha.height)
        val rows = IntArray(image.height * BINS)
        val columns = IntArray(image.width * BINS)
        val rowCounts = IntArray(image.height)
        val columnCounts = IntArray(image.width)
        visit(image, alpha) { x, y, code ->
            rows[y * BINS + (code ushr 8)]++
            columns[x * BINS + (code ushr 8)]++
            rowCounts[y]++
            columnCounts[x]++
        }
        val selectedRows = selectMedianBins(rows, rowCounts, maxOf(4, (image.width * 0.08f).toInt()))
        val selectedColumns = selectMedianBins(columns, columnCounts, maxOf(4, (image.height * 0.08f).toInt()))
        rows.fill(0)
        columns.fill(0)
        // Reuse the same 256-bin arrays to resolve the lower eight bits of each median.
        // A full 65536-bin histogram per line would exceed the mobile memory budget.
        visit(image, alpha) { x, y, code ->
            val coarse = code ushr 8
            if (coarse == selectedRows.bins[y]) rows[y * BINS + (code and 255)]++
            if (coarse == selectedColumns.bins[x]) columns[x * BINS + (code and 255)]++
        }
        val normalization = maxOf(skyMad * 1.4826f, 0.002f)
        val horizontal = normalizedResidual(resolveMedians(rows, selectedRows), normalization)
        val vertical = normalizedResidual(resolveMedians(columns, selectedColumns), normalization)
        return BandingMetrics(horizontal, vertical, sqrt(horizontal * horizontal + vertical * vertical))
    }

    private inline fun visit(
        image: ArgbPixelSource,
        alpha: AlphaPixelSource,
        consume: (x: Int, y: Int, luminanceCode: Int) -> Unit
    ) {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (alpha.alphaAt(x, y) >= 0.98f) {
                // argbAt also accounts for quantization in the PNG the user will see.
                consume(x, y, histogramBin(qualityLinearLuminance(image.argbAt(x, y)), 65536))
            }
        }
    }

    private data class MedianBins(val bins: IntArray, val ranks: IntArray)

    private fun selectMedianBins(histograms: IntArray, counts: IntArray, minimum: Int): MedianBins {
        val bins = IntArray(counts.size) { -1 }
        val ranks = IntArray(counts.size)
        for (line in counts.indices) {
            if (counts[line] < minimum) continue
            val target = (counts[line] + 1) / 2
            var accumulated = 0
            for (bin in 0 until BINS) {
                val count = histograms[line * BINS + bin]
                if (accumulated + count >= target) {
                    bins[line] = bin
                    ranks[line] = target - accumulated
                    break
                }
                accumulated += count
            }
        }
        return MedianBins(bins, ranks)
    }

    private fun resolveMedians(histograms: IntArray, selection: MedianBins): FloatArray =
        FloatArray(selection.bins.size) { line ->
            val coarse = selection.bins[line]
            if (coarse < 0) Float.NaN else {
                var accumulated = 0
                var median = Float.NaN
                for (bin in 0 until BINS) {
                    accumulated += histograms[line * BINS + bin]
                    if (accumulated >= selection.ranks[line]) {
                        median = (coarse * BINS + bin) / 65535f
                        break
                    }
                }
                median
            }
        }

    private fun normalizedResidual(values: FloatArray, normalization: Float): Float {
        val radius = (values.size / 24).coerceIn(2, 48)
        var residual = 0.0
        var abrupt = 0.0
        var count = 0
        for (index in values.indices) {
            val value = values[index].toDouble()
            if (!value.isFinite()) continue
            var sum = 0.0
            var samples = 0
            for (neighbor in maxOf(0, index - radius)..minOf(values.lastIndex, index + radius)) {
                if (values[neighbor].isFinite()) {
                    sum += values[neighbor]
                    samples++
                }
            }
            if (samples < 3) continue
            residual += abs(value - sum / samples)
            if (index > 0 && index < values.lastIndex && values[index - 1].isFinite() && values[index + 1].isFinite()) {
                abrupt += abs(value - (values[index - 1].toDouble() + values[index + 1]) * 0.5)
            }
            count++
        }
        return if (count == 0) 0f else ((residual + abrupt * 0.75) / count / normalization).coerceAtLeast(0.0).toFloat()
    }

    companion object { private const val BINS = 256 }
}
