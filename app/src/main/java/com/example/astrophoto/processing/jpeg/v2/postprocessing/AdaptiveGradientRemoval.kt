package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.GradientRemovalDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class GradientRemovalResult(
    val image: ArgbPixelImage,
    val diagnostics: GradientRemovalDiagnostics
)

class AdaptiveGradientRemoval {
    fun apply(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        stars: List<DetectedStar>,
        statistics: SkyStatisticsResult,
        strength: Float,
        maximumCorrection: Float
    ): GradientRemovalResult {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val grid = Grid.create(image.width, image.height)
        if (strength <= 0f || statistics.skyPixelCount == 0) {
            return GradientRemovalResult(
                image.copy(pixels = image.pixels.copyOf()),
                GradientRemovalDiagnostics(0f, grid.columns, grid.rows, 0, 0f)
            )
        }
        val starCores = createStarCoreMask(image.width, image.height, stars)
        val effective = effectiveSkyMask(effectiveSkyAlpha)
        val histograms = IntArray(grid.cellCount * CHANNELS_PER_CELL * MODEL_BINS)
        val counts = IntArray(grid.cellCount)
        for (index in image.pixels.indices) {
            if (!effective[index] || starCores[index]) continue
            val color = image.pixels[index]
            val red = linearChannel(color, 16)
            val green = linearChannel(color, 8)
            val blue = linearChannel(color, 0)
            if (maxOf(red, green, blue) >= SATURATED_SAMPLE_LIMIT) continue
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            val cell = grid.cellIndex(index % image.width, index / image.width)
            counts[cell]++
            increment(histograms, cell, 0, red)
            increment(histograms, cell, 1, green)
            increment(histograms, cell, 2, blue)
            increment(histograms, cell, 3, luminance)
        }
        val redModel = FloatArray(grid.cellCount) { Float.NaN }
        val greenModel = FloatArray(grid.cellCount) { Float.NaN }
        val blueModel = FloatArray(grid.cellCount) { Float.NaN }
        var validCells = 0
        counts.indices.forEach { cell ->
            val minimumSamples = maxOf(
                MIN_CELL_SAMPLES,
                (grid.approximateCellArea * MIN_SKY_CELL_FRACTION).roundToInt()
            )
            if (counts[cell] < minimumSamples) return@forEach
            val luminanceHistogram = channelHistogram(histograms, cell, 3)
            val p16 = histogramPercentile(luminanceHistogram, counts[cell].toLong(), 0.16f)
            val p84 = histogramPercentile(luminanceHistogram, counts[cell].toLong(), 0.84f)
            val textureLimit = maxOf(MIN_TEXTURE_LIMIT, statistics.luminanceMad * 9f)
            if (p84 - p16 > textureLimit) return@forEach
            redModel[cell] = histogramPercentile(
                channelHistogram(histograms, cell, 0), counts[cell].toLong(), 0.50f
            )
            greenModel[cell] = histogramPercentile(
                channelHistogram(histograms, cell, 1), counts[cell].toLong(), 0.50f
            )
            blueModel[cell] = histogramPercentile(
                channelHistogram(histograms, cell, 2), counts[cell].toLong(), 0.50f
            )
            validCells++
        }
        if (validCells == 0) {
            return GradientRemovalResult(
                image.copy(pixels = image.pixels.copyOf()),
                GradientRemovalDiagnostics(0f, grid.columns, grid.rows, 0, 0f)
            )
        }
        fillMissing(redModel, grid, statistics.channelMedian.red)
        fillMissing(greenModel, grid, statistics.channelMedian.green)
        fillMissing(blueModel, grid, statistics.channelMedian.blue)
        val smoothRed = smooth(smooth(redModel, grid), grid)
        val smoothGreen = smooth(smooth(greenModel, grid), grid)
        val smoothBlue = smooth(smooth(blueModel, grid), grid)
        val output = image.pixels.copyOf()
        val confidence = (validCells.toFloat() / grid.cellCount * statistics.confidence).coerceIn(0f, 1f)
        val appliedStrength = strength.coerceIn(0f, 1f) * confidence
        var maximumApplied = 0f
        for (index in output.indices) {
            val x = index % image.width
            val y = index / image.width
            val alpha = effectiveSkyAlpha.alphaAt(x, y)
            if (alpha <= OPERATION_ALPHA_THRESHOLD) continue
            val red = linearChannel(image.pixels[index], 16)
            val green = linearChannel(image.pixels[index], 8)
            val blue = linearChannel(image.pixels[index], 0)
            val modelRed = grid.sample(smoothRed, x, y)
            val modelGreen = grid.sample(smoothGreen, x, y)
            val modelBlue = grid.sample(smoothBlue, x, y)
            val maskScale = sqrt(alpha.coerceIn(0f, 1f))
            val correctionRed = ((modelRed - statistics.channelMedian.red) * appliedStrength * maskScale)
                .coerceIn(-maximumCorrection, maximumCorrection)
            val correctionGreen = ((modelGreen - statistics.channelMedian.green) * appliedStrength * maskScale)
                .coerceIn(-maximumCorrection, maximumCorrection)
            val correctionBlue = ((modelBlue - statistics.channelMedian.blue) * appliedStrength * maskScale)
                .coerceIn(-maximumCorrection, maximumCorrection)
            maximumApplied = maxOf(
                maximumApplied,
                abs(correctionRed), abs(correctionGreen), abs(correctionBlue)
            )
            output[index] = packLinear(
                (red - correctionRed).coerceAtLeast(0f),
                (green - correctionGreen).coerceAtLeast(0f),
                (blue - correctionBlue).coerceAtLeast(0f)
            )
        }
        return GradientRemovalResult(
            ArgbPixelImage(image.width, image.height, output),
            GradientRemovalDiagnostics(
                modelConfidence = confidence,
                gridColumns = grid.columns,
                gridRows = grid.rows,
                validCells = validCells,
                maximumCorrection = maximumApplied
            )
        )
    }

    private fun increment(histograms: IntArray, cell: Int, channel: Int, value: Float) {
        val offset = (cell * CHANNELS_PER_CELL + channel) * MODEL_BINS
        histograms[offset + histogramBin(value, MODEL_BINS)]++
    }

    private fun channelHistogram(histograms: IntArray, cell: Int, channel: Int): IntArray {
        val offset = (cell * CHANNELS_PER_CELL + channel) * MODEL_BINS
        return histograms.copyOfRange(offset, offset + MODEL_BINS)
    }

    private fun fillMissing(model: FloatArray, grid: Grid, fallback: Float) {
        val valid = model.indices.filter { model[it].isFinite() }
        model.indices.forEach { index ->
            if (model[index].isFinite()) return@forEach
            val row = index / grid.columns
            val column = index % grid.columns
            var weighted = 0f
            var weightSum = 0f
            valid.forEach { candidate ->
                val candidateRow = candidate / grid.columns
                val candidateColumn = candidate % grid.columns
                val distanceSquared = (row - candidateRow) * (row - candidateRow) +
                    (column - candidateColumn) * (column - candidateColumn)
                val weight = 1f / (1f + distanceSquared)
                weighted += model[candidate] * weight
                weightSum += weight
            }
            model[index] = if (weightSum > 0f) weighted / weightSum else fallback
        }
    }

    private fun smooth(source: FloatArray, grid: Grid): FloatArray = FloatArray(source.size) { index ->
        val row = index / grid.columns
        val column = index % grid.columns
        var sum = 0f
        var weightSum = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val x = column + dx
            val y = row + dy
            if (x !in 0 until grid.columns || y !in 0 until grid.rows) continue
            val weight = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 2f else 1f
            sum += source[y * grid.columns + x] * weight
            weightSum += weight
        }
        sum / weightSum.coerceAtLeast(1f)
    }

    private data class Grid(
        val columns: Int,
        val rows: Int,
        val width: Int,
        val height: Int
    ) {
        val cellCount = columns * rows
        val approximateCellArea = ((width.toLong() * height) / cellCount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun cellIndex(x: Int, y: Int): Int {
            val column = (x.toLong() * columns / width).toInt().coerceIn(0, columns - 1)
            val row = (y.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
            return row * columns + column
        }

        fun sample(values: FloatArray, x: Int, y: Int): Float {
            val gridX = ((x + 0.5f) * columns / width - 0.5f).coerceIn(0f, columns - 1f)
            val gridY = ((y + 0.5f) * rows / height - 0.5f).coerceIn(0f, rows - 1f)
            val left = floor(gridX).toInt()
            val top = floor(gridY).toInt()
            val right = minOf(columns - 1, left + 1)
            val bottom = minOf(rows - 1, top + 1)
            val fx = gridX - left
            val fy = gridY - top
            val upper = values[top * columns + left] * (1f - fx) +
                values[top * columns + right] * fx
            val lower = values[bottom * columns + left] * (1f - fx) +
                values[bottom * columns + right] * fx
            return upper * (1f - fy) + lower * fy
        }

        companion object {
            fun create(width: Int, height: Int): Grid {
                val shortSide = minOf(width, height)
                val targetCell = (shortSide / 8).coerceIn(MIN_CELL_SIZE, MAX_CELL_SIZE)
                return Grid(
                    columns = ((width + targetCell - 1) / targetCell).coerceIn(4, 24),
                    rows = ((height + targetCell - 1) / targetCell).coerceIn(3, 18),
                    width = width,
                    height = height
                )
            }
        }
    }

    companion object {
        private const val MODEL_BINS = 512
        private const val CHANNELS_PER_CELL = 4
        private const val MIN_CELL_SIZE = 24
        private const val MAX_CELL_SIZE = 320
        private const val MIN_CELL_SAMPLES = 16
        private const val MIN_SKY_CELL_FRACTION = 0.08f
        private const val MIN_TEXTURE_LIMIT = 0.035f
        private const val SATURATED_SAMPLE_LIMIT = 0.985f
    }
}
