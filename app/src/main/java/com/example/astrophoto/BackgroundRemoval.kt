package com.example.astrophoto

import android.graphics.Bitmap
import kotlin.math.roundToInt

enum class BackgroundRemovalMode {
    NONE,
    SOFT,
    STRONG,
    URBAN
}

data class BackgroundRemovalResult(
    val mode: BackgroundRemovalMode,
    val neutralOffset: Int,
    val strength: Float,
    val warning: String? = null
)

class BackgroundRemoval {
    fun applyInPlace(
        bitmap: Bitmap,
        mode: BackgroundRemovalMode,
        roi: AstroRoi = AstroRoi.Full
    ): BackgroundRemovalResult {
        if (mode == BackgroundRemovalMode.NONE) {
            return BackgroundRemovalResult(mode, neutralOffset = 0, strength = 0f)
        }
        val strength = when (mode) {
            BackgroundRemovalMode.SOFT -> 0.28f
            BackgroundRemovalMode.STRONG -> 0.48f
            BackgroundRemovalMode.URBAN -> 0.62f
            BackgroundRemovalMode.NONE -> 0f
        }
        val neutralOffset = when (mode) {
            BackgroundRemovalMode.SOFT -> 28
            BackgroundRemovalMode.STRONG -> 30
            BackgroundRemovalMode.URBAN -> 32
            BackgroundRemovalMode.NONE -> 0
        }
        val rect = roi.toRect(bitmap.width, bitmap.height)
        val model = buildBackgroundModel(bitmap, rect)
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width) {
                val bg = model.backgroundAt(x, y, bitmap.width, bitmap.height)
                val color = row[x]
                val red = correctChannel(color ushr 16 and 0xFF, bg.red, neutralOffset, strength)
                val green = correctChannel(color ushr 8 and 0xFF, bg.green, neutralOffset, strength)
                val blue = correctChannel(color and 0xFF, bg.blue, neutralOffset, strength)
                row[x] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        if (mode == BackgroundRemovalMode.URBAN) {
            neutralizeUrbanTint(bitmap, rect)
        }
        return BackgroundRemovalResult(
            mode = mode,
            neutralOffset = neutralOffset,
            strength = strength,
            warning = if (model.usedFallback) {
                "Фон оценён грубо: мало спокойных участков неба"
            } else {
                null
            }
        )
    }

    private data class Cell(
        var red: Float = 0f,
        var green: Float = 0f,
        var blue: Float = 0f,
        var count: Int = 0
    )

    private data class BackgroundRgb(
        val red: Float,
        val green: Float,
        val blue: Float
    )

    private data class BackgroundModel(
        val cells: Array<Array<Cell>>,
        val usedFallback: Boolean
    ) {
        fun backgroundAt(x: Int, y: Int, width: Int, height: Int): BackgroundRgb {
            val gx = ((x.toFloat() / width) * (cells[0].size - 1)).coerceIn(0f, cells[0].lastIndex.toFloat())
            val gy = ((y.toFloat() / height) * (cells.size - 1)).coerceIn(0f, cells.lastIndex.toFloat())
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val x1 = minOf(x0 + 1, cells[0].lastIndex)
            val y1 = minOf(y0 + 1, cells.lastIndex)
            val fx = gx - x0
            val fy = gy - y0
            fun blend(selector: (Cell) -> Float): Float {
                val a = selector(cells[y0][x0]) * (1f - fx) + selector(cells[y0][x1]) * fx
                val b = selector(cells[y1][x0]) * (1f - fx) + selector(cells[y1][x1]) * fx
                return a * (1f - fy) + b * fy
            }
            return BackgroundRgb(
                red = blend { it.red },
                green = blend { it.green },
                blue = blend { it.blue }
            )
        }
    }

    private fun buildBackgroundModel(bitmap: Bitmap, roi: android.graphics.Rect): BackgroundModel {
        val gridWidth = 24
        val gridHeight = 24
        val cells = Array(gridHeight) { Array(gridWidth) { Cell() } }
        val sampleStep = maxOf(1, minOf(bitmap.width, bitmap.height) / 900)
        val histogram = IntArray(256)
        var samples = 0L
        var y = roi.top
        while (y < roi.bottom) {
            var x = roi.left
            while (x < roi.right) {
                histogram[StarDetector.luminance(bitmap.getPixel(x, y))]++
                samples++
                x += sampleStep
            }
            y += sampleStep
        }
        val skyMedian = StarDetector.percentile(histogram, samples.coerceAtLeast(1L), 0.55)
        val brightLimit = (skyMedian + 70).coerceIn(40, 235)
        y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val lum = StarDetector.luminance(color)
                if (lum <= brightLimit || lum < 180) {
                    val gx = ((x.toFloat() / bitmap.width) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
                    val gy = ((y.toFloat() / bitmap.height) * gridHeight).toInt().coerceIn(0, gridHeight - 1)
                    val cell = cells[gy][gx]
                    cell.red += (color ushr 16 and 0xFF).toFloat()
                    cell.green += (color ushr 8 and 0xFF).toFloat()
                    cell.blue += (color and 0xFF).toFloat()
                    cell.count++
                }
                x += sampleStep
            }
            y += sampleStep
        }
        var fallbackRed = 0f
        var fallbackGreen = 0f
        var fallbackBlue = 0f
        var fallbackCount = 0
        for (row in cells) {
            for (cell in row) {
                if (cell.count > 0) {
                    fallbackRed += cell.red
                    fallbackGreen += cell.green
                    fallbackBlue += cell.blue
                    fallbackCount += cell.count
                }
            }
        }
        val usedFallback = fallbackCount < gridWidth * gridHeight / 2
        fallbackRed /= fallbackCount.coerceAtLeast(1)
        fallbackGreen /= fallbackCount.coerceAtLeast(1)
        fallbackBlue /= fallbackCount.coerceAtLeast(1)
        cells.forEach { row ->
            row.forEach { cell ->
                if (cell.count > 0) {
                    cell.red /= cell.count
                    cell.green /= cell.count
                    cell.blue /= cell.count
                } else {
                    cell.red = fallbackRed
                    cell.green = fallbackGreen
                    cell.blue = fallbackBlue
                }
            }
        }
        repeat(2) { smoothCells(cells) }
        return BackgroundModel(cells, usedFallback)
    }

    private fun smoothCells(cells: Array<Array<Cell>>) {
        val copy = Array(cells.size) { y ->
            Array(cells[0].size) { x ->
                cells[y][x].copy()
            }
        }
        for (y in cells.indices) {
            for (x in cells[y].indices) {
                var red = 0f
                var green = 0f
                var blue = 0f
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in cells.indices && nx in cells[y].indices) {
                            red += copy[ny][nx].red
                            green += copy[ny][nx].green
                            blue += copy[ny][nx].blue
                            count++
                        }
                    }
                }
                cells[y][x].red = red / count
                cells[y][x].green = green / count
                cells[y][x].blue = blue / count
            }
        }
    }

    private fun correctChannel(
        value: Int,
        background: Float,
        neutralOffset: Int,
        strength: Float
    ): Int {
        val corrected = value - (background - neutralOffset) * strength
        return corrected.roundToInt().coerceIn(0, 255)
    }

    private fun neutralizeUrbanTint(bitmap: Bitmap, roi: android.graphics.Rect) {
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var count = 0
        val step = maxOf(1, minOf(roi.width(), roi.height()) / 500)
        var y = roi.top
        while (y < roi.bottom) {
            var x = roi.left
            while (x < roi.right) {
                val color = bitmap.getPixel(x, y)
                val lum = StarDetector.luminance(color)
                if (lum in 16..190) {
                    redSum += color ushr 16 and 0xFF
                    greenSum += color ushr 8 and 0xFF
                    blueSum += color and 0xFF
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return
        val redAvg = redSum.toFloat() / count
        val greenAvg = greenSum.toFloat() / count
        val blueAvg = blueSum.toFloat() / count
        val neutral = (redAvg + greenAvg + blueAvg) / 3f
        val row = IntArray(bitmap.width)
        for (yy in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, yy, bitmap.width, 1)
            for (x in 0 until bitmap.width) {
                val color = row[x]
                val red = ((color ushr 16 and 0xFF) + (neutral - redAvg) * 0.22f)
                    .roundToInt().coerceIn(0, 255)
                val green = ((color ushr 8 and 0xFF) + (neutral - greenAvg) * 0.22f)
                    .roundToInt().coerceIn(0, 255)
                val blue = ((color and 0xFF) + (neutral - blueAvg) * 0.22f)
                    .roundToInt().coerceIn(0, 255)
                row[x] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, yy, bitmap.width, 1)
        }
    }
}
