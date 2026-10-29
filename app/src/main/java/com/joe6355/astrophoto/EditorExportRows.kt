package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.output.PngImageSource
import kotlin.math.pow
import kotlin.math.roundToInt

/** The same 8-bit editor transform, cached once per channel value. */
internal class EditorColorTransform(settings: ImageAdjustments) {
    private val saturation = settings.saturation.coerceIn(0f, 200f) / 100f
    private val channels = IntArray(256) { value ->
        val black = settings.blackPoint.coerceIn(0f, 100f)
        val white = settings.whitePoint.coerceIn(155f, 255f).coerceAtLeast(black + 1f)
        val gamma = settings.gamma.coerceIn(0.5f, 2.5f)
        val normalized = ((value - black) / (white - black)).coerceIn(0f, 1f)
        var corrected = normalized.toDouble().pow(1.0 / gamma).toFloat()
        corrected += settings.brightness.coerceIn(-100f, 100f) / 255f
        corrected = (corrected - 0.5f) * (1f + settings.contrast.coerceIn(-100f, 100f) / 100f) + 0.5f
        (corrected.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    fun apply(color: Int): Int {
        val red = channels[color ushr 16 and 255]
        val green = channels[color ushr 8 and 255]
        val blue = channels[color and 255]
        val gray = red * 0.299f + green * 0.587f + blue * 0.114f
        return 0xFF000000.toInt() or
            ((gray + (red - gray) * saturation).toInt().coerceIn(0, 255) shl 16) or
            ((gray + (green - gray) * saturation).toInt().coerceIn(0, 255) shl 8) or
            (gray + (blue - gray) * saturation).toInt().coerceIn(0, 255)
    }
}

internal fun editorCropPixels(width: Int, height: Int, crop: CropSettings): CropPixels {
    require(width > 0 && height > 0)
    val left = (width * crop.leftPercent.coerceIn(0f, 30f) / 100f).roundToInt()
    val right = (width * crop.rightPercent.coerceIn(0f, 30f) / 100f).roundToInt()
    val top = (height * crop.topPercent.coerceIn(0f, 30f) / 100f).roundToInt()
    val bottom = (height * crop.bottomPercent.coerceIn(0f, 30f) / 100f).roundToInt()
    val finalWidth = width - left - right
    val finalHeight = height - top - bottom
    require(finalWidth >= 200 && finalHeight >= 200) { "Обрезка даёт слишком маленькое изображение" }
    return CropPixels(left, right, top, bottom, finalWidth, finalHeight)
}

/** Single-pass full-resolution output. At most three source rows are retained. */
internal class EditorExportRows(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    val crop: CropPixels,
    adjustments: ImageAdjustments,
    private val readSourceRow: (Int, IntArray) -> Unit
) : PngImageSource {
    override val width = crop.finalWidth
    override val height = crop.finalHeight
    private val color = EditorColorTransform(adjustments)
    private val sharpen = adjustments.sharpness.coerceIn(0f, 100f) / 100f * 1.6f
    private val useSharpness = sharpen > 0f && sourceWidth > 1 && sourceHeight > 1
    private var previous = IntArray(if (useSharpness) sourceWidth else 0)
    private var current = IntArray(sourceWidth)
    private var next = IntArray(if (useSharpness) sourceWidth else 0)
    private var renderedRows = 0
    private val histogram = IntArray(64)
    private var brightnessSum = 0L
    private var shadows = 0L
    private var highlights = 0L
    private var clippedBlack = 0L
    private var clippedWhite = 0L

    init {
        require(crop.left >= 0 && crop.top >= 0 && width > 0 && height > 0)
        require(crop.left.toLong() + width <= sourceWidth && crop.top.toLong() + height <= sourceHeight)
    }

    override fun readArgbRow(y: Int, destination: IntArray) {
        require(y == renderedRows && y in 0 until height && destination.size >= width) {
            "Editor PNG rows must be read once in order"
        }
        val sourceY = crop.top + y
        if (!useSharpness) {
            loadAdjusted(sourceY, current)
        } else if (y == 0) {
            loadAdjusted((sourceY - 1).coerceAtLeast(0), previous)
            loadAdjusted(sourceY, current)
            loadAdjusted((sourceY + 1).coerceAtMost(sourceHeight - 1), next)
        } else {
            val reusable = previous
            previous = current
            current = next
            next = reusable
            loadAdjusted((sourceY + 1).coerceAtMost(sourceHeight - 1), next)
        }
        for (x in 0 until width) {
            val sourceX = crop.left + x
            val center = current[sourceX]
            val output = if (!useSharpness) center else {
                val left = current[(sourceX - 1).coerceAtLeast(0)]
                val right = current[(sourceX + 1).coerceAtMost(sourceWidth - 1)]
                val up = previous[sourceX]
                val down = next[sourceX]
                fun channel(shift: Int) = editorSharpenedChannel(center ushr shift and 255,
                    left ushr shift and 255, right ushr shift and 255,
                    up ushr shift and 255, down ushr shift and 255, sharpen)
                0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
            destination[x] = output
            val luminance = ((output ushr 16 and 255) * 77 +
                (output ushr 8 and 255) * 150 + (output and 255) * 29) ushr 8
            brightnessSum += luminance
            if (luminance < 10) shadows++
            if (luminance > 245) highlights++
            if (luminance <= 2) clippedBlack++
            if (luminance >= 253) clippedWhite++
            histogram[(luminance * 64 / 256).coerceIn(0, 63)]++
        }
        renderedRows++
    }

    fun metrics(): EditorImageMetrics {
        check(renderedRows == height) { "Editor metrics require a complete output" }
        val total = width.toLong() * height
        val maximum = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
        return EditorImageMetrics(brightnessSum.toFloat() / total,
            shadows * 100f / total, highlights * 100f / total,
            clippedBlack * 100f / total, clippedWhite * 100f / total,
            histogram.map { it.toFloat() / maximum })
    }

    private fun loadAdjusted(y: Int, destination: IntArray) {
        readSourceRow(y, destination)
        for (x in destination.indices) destination[x] = color.apply(destination[x])
    }
}
