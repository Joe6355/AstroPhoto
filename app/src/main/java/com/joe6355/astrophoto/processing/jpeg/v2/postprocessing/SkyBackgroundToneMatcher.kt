package com.joe6355.astrophoto.processing.jpeg.v2.postprocessing

import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import kotlin.math.floor
import kotlin.math.sqrt

internal data class SkyBackgroundToneMatch(
    val linearOffset: Float,
    val processedMedian: Float
)

/** Removes only the broad sky lift that otherwise reveals the foreground mask silhouette. */
internal object SkyBackgroundToneMatcher {
    /** Match broad processing lift locally: one global median exposes a mask on a gradient. */
    fun matchLocal(
        before: FileBackedImage,
        processed: FileBackedImage,
        alphaPlane: FileBackedFloatPlane,
        store: ResultCandidateStore,
        budget: JpegMemoryBudget,
        tracker: PipelineMemoryTracker,
        cancellationCheck: () -> Unit = {}
    ): FileBackedImage {
        require(before.width == processed.width && before.height == processed.height)
        require(before.width == alphaPlane.width && before.height == alphaPlane.height)
        val columns = (before.width + CELL_SIZE - 1) / CELL_SIZE
        val rows = (before.height + CELL_SIZE - 1) / CELL_SIZE
        val fixedBytes = before.width.toLong() * 96L + columns.toLong() * rows * 24L + 8192L
        val cachedRows = ((budget.safeWorkingBudgetBytes - fixedBytes) /
            (before.width.toLong() * 20L)).coerceIn(1L, CELL_SIZE.toLong()).toInt()
        val estimatedBytes = fixedBytes + before.width.toLong() * cachedRows * 20L
        budget.requireAllocation(ImageAllocationEstimate("local-background-matching", estimatedBytes))
        tracker.recordBoundary("local-background-matching", estimatedBytes, 0)
        val offsets = Array(3) { FloatArray(columns * rows) }
        val owner = IntArray(columns * rows) { -1 }
        val queue = IntArray(owner.size)
        var head = 0
        var tail = 0
        val samples = Array(3) { FloatArray((CELL_SIZE / SAMPLE_STEP + 1) * (CELL_SIZE / SAMPLE_STEP + 1)) }
        // One cell of decoded rows avoids rereading the same scanlines for every column.
        FileBackedImageReader(before, cachedRows = cachedRows).use { base ->
            FileBackedImageReader(processed, cachedRows = cachedRows).use { result ->
                FileBackedFloatPlaneReader(alphaPlane, cachedRows = cachedRows).use { alpha ->
                    for (gy in 0 until rows) for (gx in 0 until columns) {
                        if (gx == 0) cancellationCheck()
                        var count = 0
                        for (y in gy * CELL_SIZE until minOf(before.height, (gy + 1) * CELL_SIZE) step SAMPLE_STEP) {
                            for (x in gx * CELL_SIZE until minOf(before.width, (gx + 1) * CELL_SIZE) step SAMPLE_STEP) {
                                val a = alpha.alphaAt(x, y)
                                if (a <= 0.5f) continue
                                val original = base.linearRgbAt(x, y)
                                val candidate = result.linearRgbAt(x, y)
                                val weight = sqrt(a)
                                samples[0][count] = (LinearRgb16.red(candidate) - LinearRgb16.red(original)) / weight
                                samples[1][count] = (LinearRgb16.green(candidate) - LinearRgb16.green(original)) / weight
                                samples[2][count] = (LinearRgb16.blue(candidate) - LinearRgb16.blue(original)) / weight
                                count++
                            }
                        }
                        if (count < MIN_SAMPLES) continue
                        val index = gy * columns + gx
                        for (channel in 0..2) {
                            samples[channel].sort(0, count)
                            val middle = count / 2
                            val median = if (count % 2 == 1) samples[channel][middle]
                            else (samples[channel][middle - 1] + samples[channel][middle]) * 0.5f
                            // Preserve background subtraction/neutralization; remove only added lift.
                            offsets[channel][index] = median.coerceAtLeast(0f)
                        }
                        owner[index] = index
                        queue[tail++] = index
                    }
                }
            }
        }
        if (tail == 0 || offsets.all { channel -> channel.all { it == 0f } }) return processed
        // Bounded linear-time extension through masked cells, with no foreground samples.
        while (head < tail) {
            val index = queue[head++]
            val x = index % columns
            val y = index / columns
            fun visit(next: Int) {
                if (owner[next] >= 0) return
                owner[next] = owner[index]
                queue[tail++] = next
            }
            if (x > 0) visit(index - 1)
            if (x + 1 < columns) visit(index + 1)
            if (y > 0) visit(index - columns)
            if (y + 1 < rows) visit(index + columns)
        }
        val output = store.createTemporaryWriter("local-background-match", before.width, before.height)
        try {
            FileBackedImageReader(before, cachedRows = 3).use { original ->
                FileBackedImageReader(processed, cachedRows = 2).use { image ->
                    FileBackedFloatPlaneReader(alphaPlane, cachedRows = 2).use { alpha ->
                        val row = LongArray(before.width)
                        for (y in 0 until before.height) {
                            cancellationCheck()
                            val fy = y.toFloat() / CELL_SIZE - 0.5f
                            val iy = floor(fy).toInt()
                            val y0 = iy.coerceIn(0, rows - 1)
                            val y1 = (iy + 1).coerceIn(0, rows - 1)
                            val wy = fy - iy
                            for (x in 0 until before.width) {
                                val color = image.linearRgbAt(x, y)
                                val a = alpha.alphaAt(x, y)
                                if (a <= 0f) { row[x] = color; continue }
                                val fx = x.toFloat() / CELL_SIZE - 0.5f
                                val ix = floor(fx).toInt()
                                val x0 = ix.coerceIn(0, columns - 1)
                                val x1 = (ix + 1).coerceIn(0, columns - 1)
                                val wx = fx - ix
                                val weight = sqrt(a)
                                fun offset(channel: Int): Float {
                                    val values = offsets[channel]
                                    val top = values[owner[y0 * columns + x0]] * (1f - wx) +
                                        values[owner[y0 * columns + x1]] * wx
                                    val bottom = values[owner[y1 * columns + x0]] * (1f - wx) +
                                        values[owner[y1 * columns + x1]] * wx
                                    return (top * (1f - wy) + bottom * wy) * weight
                                }
                                // A cell can contain a genuine dark structure. Its brighter
                                // neighbours must not make matching subtract that positive floor.
                                // A local lower envelope protects it without restoring grain peaks.
                                var redFloor = 1f
                                var greenFloor = 1f
                                var blueFloor = 1f
                                for (ny in maxOf(0, y - 1)..minOf(before.height - 1, y + 1)) {
                                    for (nx in maxOf(0, x - 1)..minOf(before.width - 1, x + 1)) {
                                        val value = original.linearRgbAt(nx, ny)
                                        redFloor = minOf(redFloor, LinearRgb16.red(value))
                                        greenFloor = minOf(greenFloor, LinearRgb16.green(value))
                                        blueFloor = minOf(blueFloor, LinearRgb16.blue(value))
                                    }
                                }
                                val red = LinearRgb16.red(color)
                                val green = LinearRgb16.green(color)
                                val blue = LinearRgb16.blue(color)
                                row[x] = LinearRgb16.pack(
                                    (red - offset(0)).coerceAtLeast(minOf(red, redFloor)),
                                    (green - offset(1)).coerceAtLeast(minOf(green, greenFloor)),
                                    (blue - offset(2)).coerceAtLeast(minOf(blue, blueFloor))
                                )
                            }
                            output.writeLinearRow(y, row)
                        }
                    }
                }
            }
            return output.finish()
        } catch (error: Throwable) {
            output.close()
            store.deleteTemporary(output.image)
            throw error
        }
    }

    fun calculate(
        before: SkyStatisticsResult,
        after: SkyStatisticsResult
    ): SkyBackgroundToneMatch {
        if (before.skyPixelCount == 0 || after.skyPixelCount == 0) {
            return SkyBackgroundToneMatch(0f, after.luminanceMedian)
        }
        val headroom = maxOf(before.luminanceMad * BACKGROUND_MAD_HEADROOM, MIN_HEADROOM)
        val targetMedian = before.luminanceMedian + headroom
        return SkyBackgroundToneMatch(
            linearOffset = (after.luminanceMedian - targetMedian).coerceAtLeast(0f),
            processedMedian = after.luminanceMedian
        )
    }

    fun apply(color: Int, alpha: Float, match: SkyBackgroundToneMatch): Int {
        if (alpha <= 0f || match.linearOffset <= MIN_OFFSET) return color
        val red = linearChannel(color, 16)
        val green = linearChannel(color, 8)
        val blue = linearChannel(color, 0)
        val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
        val shadowSafety = smoothStep(0f, match.processedMedian.coerceAtLeast(MIN_HEADROOM), luminance)
        val offset = match.linearOffset * sqrt(alpha.coerceIn(0f, 1f)) * shadowSafety
        return packLinear(
            (red - offset).coerceAtLeast(0f),
            (green - offset).coerceAtLeast(0f),
            (blue - offset).coerceAtLeast(0f)
        )
    }

    fun applyLinear(color: Long, alpha: Float, match: SkyBackgroundToneMatch): Long {
        if (alpha <= 0f || match.linearOffset <= MIN_OFFSET) return color
        val luminance = LinearRgb16.luminance(color)
        val shadowSafety = smoothStep(0f, match.processedMedian.coerceAtLeast(MIN_HEADROOM), luminance)
        val offset = match.linearOffset * sqrt(alpha.coerceIn(0f, 1f)) * shadowSafety
        return LinearRgb16.pack(
            (LinearRgb16.red(color) - offset).coerceAtLeast(0f),
            (LinearRgb16.green(color) - offset).coerceAtLeast(0f),
            (LinearRgb16.blue(color) - offset).coerceAtLeast(0f)
        )
    }

    private const val BACKGROUND_MAD_HEADROOM = 0.25f
    private const val MIN_HEADROOM = 1f / 32768f
    private const val MIN_OFFSET = 1f / 65536f
    private const val CELL_SIZE = 48
    private const val SAMPLE_STEP = 4
    private const val MIN_SAMPLES = 8
}
