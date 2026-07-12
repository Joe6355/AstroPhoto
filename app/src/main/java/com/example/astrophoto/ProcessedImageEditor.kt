package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

data class ImageAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val blackPoint: Float = 0f,
    val whitePoint: Float = 255f,
    val saturation: Float = 100f,
    val sharpness: Float = 0f
)

data class CropSettings(
    val leftPercent: Float = 0f,
    val rightPercent: Float = 0f,
    val topPercent: Float = 0f,
    val bottomPercent: Float = 0f
)

private data class CropPixels(
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int,
    val finalWidth: Int,
    val finalHeight: Int
)

data class EditorSourcePreview(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int
)

data class AutoCropResult(
    val settings: CropSettings,
    val found: Boolean,
    val aggressive: Boolean
)

data class EditorImageMetrics(
    val averageBrightness: Float,
    val shadowPercent: Float,
    val highlightPercent: Float,
    val clippedBlackPercent: Float,
    val clippedWhitePercent: Float,
    val histogram: List<Float>
) {
    val hasClipping: Boolean
        get() = clippedBlackPercent > CLIPPING_WARNING_PERCENT ||
            clippedWhitePercent > CLIPPING_WARNING_PERCENT

    fun warnings(): List<String> = buildList {
        if (clippedBlackPercent > CLIPPING_WARNING_PERCENT) {
            add("Тени зажаты: часть деталей потеряна в чёрном.")
        }
        if (clippedWhitePercent > CLIPPING_WARNING_PERCENT) {
            add("Есть пересвет: часть деталей потеряна в белом.")
        }
        if (averageBrightness < DARK_BRIGHTNESS) {
            add("Изображение слишком тёмное.")
        } else if (averageBrightness > BRIGHT_BRIGHTNESS) {
            add("Изображение слишком яркое.")
        }
        if (isEmpty()) {
            add("Тональный диапазон выглядит нормально.")
        }
    }

    companion object {
        const val CLIPPING_WARNING_PERCENT = 2f
        private const val DARK_BRIGHTNESS = 30f
        private const val BRIGHT_BRIGHTNESS = 220f
    }
}

private data class EditorPreset(
    val name: String,
    val description: String,
    val adjustments: ImageAdjustments
)

data class EditedImageSaveResult(
    val fileName: String,
    val displayPath: String,
    val usedPreviewFallback: Boolean,
    val sessionInfoUpdated: Boolean
)

class ProcessedImageEditor(private val context: Context) {
    private val sourceResolver = ProcessedImageSourceResolver(context)

    suspend fun loadPreview(
        source: ProcessedResult,
        maxDimension: Int = 1600
    ): EditorSourcePreview? = withContext(Dispatchers.IO) {
        if (!source.isReadable) return@withContext null
        runCatching {
            val dimensions = readDimensions(source) ?: return@runCatching null
            val bitmap = decodeSampled(source, maxDimension)
                ?: return@runCatching null
            EditorSourcePreview(
                bitmap = bitmap,
                sourceWidth = dimensions.first,
                sourceHeight = dimensions.second
            )
        }.onFailure { error ->
            Log.e(
                "AstroPhotoImageLoad",
                "Failed to open editor source ${source.fileName}: ${error.message}",
                error
            )
        }.getOrNull()
    }

    suspend fun renderPreview(
        original: Bitmap,
        adjustments: ImageAdjustments,
        crop: CropSettings
    ): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val adjusted = original.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@runCatching null
            try {
                applyInPlace(adjusted, adjustments)
                applyCrop(adjusted, crop).also {
                    if (it !== adjusted) adjusted.recycle()
                }
            } catch (error: Throwable) {
                adjusted.takeUnless(Bitmap::isRecycled)?.recycle()
                throw error
            }
        }.getOrNull()
    }

    suspend fun autoAdjustments(original: Bitmap): ImageAdjustments? =
        withContext(Dispatchers.Default) {
            runCatching {
                val histogram = IntArray(256)
                val width = original.width
                val height = original.height
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    original.getPixels(pixels, 0, width, 0, y, width, 1)
                    pixels.forEach { color ->
                        val red = color ushr 16 and 0xFF
                        val green = color ushr 8 and 0xFF
                        val blue = color and 0xFF
                        val luminance =
                            (red * 77 + green * 150 + blue * 29) ushr 8
                        histogram[luminance]++
                    }
                }
                val total = (width.toLong() * height).coerceAtLeast(1L)
                val black = percentile(histogram, total, 0.01)
                    .coerceIn(0, 100)
                val white = percentile(histogram, total, 0.99)
                    .coerceIn(155, 255)
                ImageAdjustments(
                    contrast = 15f,
                    gamma = 1.15f,
                    blackPoint = black.toFloat(),
                    whitePoint = maxOf(white, black + 20).coerceAtMost(255).toFloat(),
                    saturation = 115f
                )
            }.getOrNull()
        }

    suspend fun astroAutoAdjustments(original: Bitmap): ImageAdjustments? =
        withContext(Dispatchers.Default) {
            runCatching {
                val histogram = IntArray(256)
                val width = original.width
                val height = original.height
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    original.getPixels(pixels, 0, width, 0, y, width, 1)
                    pixels.forEach { color ->
                        val red = color ushr 16 and 0xFF
                        val green = color ushr 8 and 0xFF
                        val blue = color and 0xFF
                        val luminance =
                            (red * 77 + green * 150 + blue * 29) ushr 8
                        histogram[luminance]++
                    }
                }
                val total = (width.toLong() * height).coerceAtLeast(1L)
                val black = percentile(histogram, total, 0.002)
                    .coerceIn(0, 28)
                val white = percentile(histogram, total, 0.998)
                    .coerceIn(black + 36, 255)
                ImageAdjustments(
                    brightness = 8f,
                    contrast = 8f,
                    gamma = 1.35f,
                    blackPoint = black.toFloat(),
                    whitePoint = white.toFloat(),
                    saturation = 112f
                )
            }.getOrNull()
        }

    suspend fun urbanSkyAdjustments(original: Bitmap): ImageAdjustments? =
        withContext(Dispatchers.Default) {
            runCatching {
                val histogram = IntArray(256)
                val width = original.width
                val height = original.height
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    original.getPixels(pixels, 0, width, 0, y, width, 1)
                    pixels.forEach { color ->
                        histogram[StarDetector.luminance(color)]++
                    }
                }
                val total = (width.toLong() * height).coerceAtLeast(1L)
                val black = percentile(histogram, total, 0.003).coerceIn(0, 24)
                val white = percentile(histogram, total, 0.997)
                    .coerceIn(black + 42, 255)
                ImageAdjustments(
                    brightness = -6f,
                    contrast = 18f,
                    gamma = 1.28f,
                    blackPoint = black.toFloat(),
                    whitePoint = white.toFloat(),
                    saturation = 92f
                )
            }.getOrNull()
        }

    suspend fun maxStarsAdjustments(original: Bitmap): ImageAdjustments? =
        withContext(Dispatchers.Default) {
            runCatching {
                val histogram = IntArray(256)
                val width = original.width
                val height = original.height
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    original.getPixels(pixels, 0, width, 0, y, width, 1)
                    pixels.forEach { color ->
                        histogram[StarDetector.luminance(color)]++
                    }
                }
                val total = (width.toLong() * height).coerceAtLeast(1L)
                val black = percentile(histogram, total, 0.0015).coerceIn(0, 18)
                val white = percentile(histogram, total, 0.999)
                    .coerceIn(black + 36, 255)
                ImageAdjustments(
                    brightness = 10f,
                    contrast = 22f,
                    gamma = 1.55f,
                    blackPoint = black.toFloat(),
                    whitePoint = white.toFloat(),
                    saturation = 118f
                )
            }.getOrNull()
        }

    suspend fun analyzePreview(bitmap: Bitmap): EditorImageMetrics =
        withContext(Dispatchers.Default) {
            calculateMetrics(bitmap)
        }

    suspend fun detectBlackEdges(bitmap: Bitmap): AutoCropResult =
        withContext(Dispatchers.Default) {
            detectAutoCrop(bitmap)
        }

    suspend fun saveEditedCopy(
        session: SessionSummary,
        source: ProcessedResult,
        adjustments: ImageAdjustments,
        crop: CropSettings,
        preview: Bitmap,
        resultMetrics: EditorImageMetrics?
    ): Result<EditedImageSaveResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(source.fileName.endsWith(".jpg", ignoreCase = true) ||
                source.fileName.endsWith(".jpeg", ignoreCase = true)
            ) {
                "Редактор поддерживает только JPEG"
            }

            var usedPreviewFallback = false
            var fullBitmap: Bitmap? = null
            val editedAndCrop = try {
                val full = decodeFullMutable(source)
                    ?: error("Не удалось прочитать JPEG")
                fullBitmap = full
                applyInPlace(full, adjustments)
                val cropPixels = cropPixels(full.width, full.height, crop)
                val cropped = applyCrop(full, crop)
                if (cropped !== full) {
                    full.recycle()
                    fullBitmap = null
                }
                cropped to cropPixels
            } catch (_: OutOfMemoryError) {
                fullBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                fullBitmap = null
                usedPreviewFallback = true
                val fallback = preview.copy(Bitmap.Config.ARGB_8888, true)
                    ?: error("Недостаточно памяти для обработки изображения")
                applyInPlace(fallback, adjustments)
                val cropPixels = cropPixels(
                    fallback.width,
                    fallback.height,
                    crop
                )
                val cropped = applyCrop(fallback, crop)
                if (cropped !== fallback) fallback.recycle()
                cropped to cropPixels
            } catch (error: Throwable) {
                fullBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                fullBitmap = null
                throw error
            }
            val edited = editedAndCrop.first
            val appliedCrop = editedAndCrop.second

            try {
                val now = System.currentTimeMillis()
                val fileName = "Edited_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
                }.jpg"
                val path = saveBitmap(session, edited, fileName)
                val infoUpdated = runCatching {
                    appendSessionInfo(
                        session = session,
                        sourceFile = source.fileName,
                        editedFile = fileName,
                        adjustments = adjustments,
                        crop = appliedCrop,
                        resultMetrics = resultMetrics ?: calculateMetrics(edited),
                        editedAtMillis = now
                    )
                }.isSuccess
                EditedImageSaveResult(
                    fileName = fileName,
                    displayPath = path,
                    usedPreviewFallback = usedPreviewFallback,
                    sessionInfoUpdated = infoUpdated
                )
            } finally {
                edited.recycle()
            }
        }
    }

    private fun decodeSampled(
        source: ProcessedResult,
        maxDimension: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }
        return openSource(source)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }

    private fun readDimensions(source: ProcessedResult): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    private fun decodeFullMutable(source: ProcessedResult): Bitmap? =
        openSource(source)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
            )
        }

    private fun openSource(source: ProcessedResult): InputStream? =
        sourceResolver.openInputStream(
            imageSourceReference(
                displayName = source.fileName,
                displayPath = source.displayPath,
                relativePath = source.relativePath,
                providerUri = source.contentUri,
                legacyFilePath = source.filePath
            )
        )

    private fun applyInPlace(bitmap: Bitmap, settings: ImageAdjustments) {
        require(bitmap.isMutable) { "Bitmap недоступен для редактирования" }
        val width = bitmap.width
        val rowCount = 32
        val pixels = IntArray(width * rowCount)
        val black = settings.blackPoint.coerceIn(0f, 100f)
        val white = settings.whitePoint.coerceIn(155f, 255f)
            .coerceAtLeast(black + 1f)
        val gamma = settings.gamma.coerceIn(0.5f, 2.5f)
        val contrastFactor = 1f + settings.contrast.coerceIn(-100f, 100f) / 100f
        val saturationFactor = settings.saturation.coerceIn(0f, 200f) / 100f
        var top = 0

        while (top < bitmap.height) {
            val rows = minOf(rowCount, bitmap.height - top)
            val count = width * rows
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
            for (index in 0 until count) {
                val color = pixels[index]
                var red = adjustChannel(
                    color ushr 16 and 0xFF,
                    black,
                    white,
                    gamma,
                    settings.brightness,
                    contrastFactor
                )
                var green = adjustChannel(
                    color ushr 8 and 0xFF,
                    black,
                    white,
                    gamma,
                    settings.brightness,
                    contrastFactor
                )
                var blue = adjustChannel(
                    color and 0xFF,
                    black,
                    white,
                    gamma,
                    settings.brightness,
                    contrastFactor
                )
                val gray = red * 0.299f + green * 0.587f + blue * 0.114f
                red = (gray + (red - gray) * saturationFactor)
                    .toInt().coerceIn(0, 255)
                green = (gray + (green - gray) * saturationFactor)
                    .toInt().coerceIn(0, 255)
                blue = (gray + (blue - gray) * saturationFactor)
                    .toInt().coerceIn(0, 255)
                pixels[index] =
                    0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
            bitmap.setPixels(pixels, 0, width, 0, top, width, rows)
            top += rows
        }
    }

    private fun applyCrop(bitmap: Bitmap, crop: CropSettings): Bitmap {
        val pixels = cropPixels(bitmap.width, bitmap.height, crop)
        if (
            pixels.left == 0 &&
            pixels.right == 0 &&
            pixels.top == 0 &&
            pixels.bottom == 0
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(
            bitmap,
            pixels.left,
            pixels.top,
            pixels.finalWidth,
            pixels.finalHeight
        )
    }

    private fun cropPixels(
        width: Int,
        height: Int,
        crop: CropSettings
    ): CropPixels {
        val left = (width * crop.leftPercent.coerceIn(0f, 30f) / 100f)
            .roundToInt()
        val right = (width * crop.rightPercent.coerceIn(0f, 30f) / 100f)
            .roundToInt()
        val top = (height * crop.topPercent.coerceIn(0f, 30f) / 100f)
            .roundToInt()
        val bottom = (height * crop.bottomPercent.coerceIn(0f, 30f) / 100f)
            .roundToInt()
        val finalWidth = width - left - right
        val finalHeight = height - top - bottom
        require(finalWidth >= MIN_CROP_SIZE && finalHeight >= MIN_CROP_SIZE) {
            "Обрезка даёт слишком маленькое изображение"
        }
        return CropPixels(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            finalWidth = finalWidth,
            finalHeight = finalHeight
        )
    }

    private fun detectAutoCrop(bitmap: Bitmap): AutoCropResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width < MIN_CROP_SIZE || height < MIN_CROP_SIZE) {
            return AutoCropResult(CropSettings(), found = false, aggressive = false)
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        fun isBlack(color: Int): Boolean {
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            return ((red * 77 + green * 150 + blue * 29) ushr 8) < 5
        }

        fun blackRow(y: Int): Boolean {
            var black = 0
            var samples = 0
            var x = 0
            while (x < width) {
                if (isBlack(pixels[y * width + x])) black++
                samples++
                x += 2
            }
            return black >= samples * 0.98f
        }

        fun blackColumn(x: Int): Boolean {
            var black = 0
            var samples = 0
            var y = 0
            while (y < height) {
                if (isBlack(pixels[y * width + x])) black++
                samples++
                y += 2
            }
            return black >= samples * 0.98f
        }

        var top = 0
        while (top < height / 2 && blackRow(top)) top++
        var bottom = 0
        while (bottom < height / 2 && blackRow(height - 1 - bottom)) bottom++
        var left = 0
        while (left < width / 2 && blackColumn(left)) left++
        var right = 0
        while (right < width / 2 && blackColumn(width - 1 - right)) right++

        if (top == 0 && bottom == 0 && left == 0 && right == 0) {
            return AutoCropResult(CropSettings(), found = false, aggressive = false)
        }
        val safetyPadding = 3
        if (left > 0) left += safetyPadding
        if (right > 0) right += safetyPadding
        if (top > 0) top += safetyPadding
        if (bottom > 0) bottom += safetyPadding

        val rawLeft = left * 100f / width
        val rawRight = right * 100f / width
        val rawTop = top * 100f / height
        val rawBottom = bottom * 100f / height
        val aggressive = maxOf(rawLeft, rawRight, rawTop, rawBottom) > 25f
        val settings = CropSettings(
            leftPercent = rawLeft.coerceAtMost(30f),
            rightPercent = rawRight.coerceAtMost(30f),
            topPercent = rawTop.coerceAtMost(30f),
            bottomPercent = rawBottom.coerceAtMost(30f)
        )
        return if (isSafeCrop(settings, width, height)) {
            AutoCropResult(settings, found = true, aggressive = aggressive)
        } else {
            AutoCropResult(
                settings = CropSettings(),
                found = false,
                aggressive = true
            )
        }
    }

    private fun adjustChannel(
        value: Int,
        black: Float,
        white: Float,
        gamma: Float,
        brightness: Float,
        contrastFactor: Float
    ): Int {
        val normalized = ((value - black) / (white - black)).coerceIn(0f, 1f)
        var corrected = normalized.toDouble().pow(1.0 / gamma).toFloat()
        corrected += brightness.coerceIn(-100f, 100f) / 255f
        corrected = (corrected - 0.5f) * contrastFactor + 0.5f
        return (corrected.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    private fun percentile(
        histogram: IntArray,
        totalPixels: Long,
        percentile: Double
    ): Int {
        val target = (totalPixels * percentile).toLong()
        var accumulated = 0L
        histogram.forEachIndexed { index, count ->
            accumulated += count
            if (accumulated >= target) return index
        }
        return histogram.lastIndex
    }

    private fun calculateMetrics(bitmap: Bitmap): EditorImageMetrics {
        val histogramCounts = IntArray(64)
        val width = bitmap.width
        val row = IntArray(width)
        var brightnessSum = 0L
        var shadowCount = 0L
        var highlightCount = 0L
        var clippedBlackCount = 0L
        var clippedWhiteCount = 0L

        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            row.forEach { color ->
                val red = color ushr 16 and 0xFF
                val green = color ushr 8 and 0xFF
                val blue = color and 0xFF
                val luminance = (red * 77 + green * 150 + blue * 29) ushr 8
                brightnessSum += luminance
                if (luminance < 10) shadowCount++
                if (luminance > 245) highlightCount++
                if (luminance <= 2) clippedBlackCount++
                if (luminance >= 253) clippedWhiteCount++
                val bin = (luminance * 64 / 256).coerceIn(0, 63)
                histogramCounts[bin]++
            }
        }
        val total = (width.toLong() * bitmap.height).coerceAtLeast(1L)
        val maximum = histogramCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        return EditorImageMetrics(
            averageBrightness = brightnessSum.toFloat() / total,
            shadowPercent = shadowCount * 100f / total,
            highlightPercent = highlightCount * 100f / total,
            clippedBlackPercent = clippedBlackCount * 100f / total,
            clippedWhitePercent = clippedWhiteCount * 100f / total,
            histogram = histogramCounts.map { it.toFloat() / maximum }
        )
    }

    private fun saveBitmap(
        session: SessionSummary,
        bitmap: Bitmap,
        fileName: String
    ): String {
        val jpegQuality = CameraSettingsStore(context).load().jpegQuality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath =
                "${Environment.DIRECTORY_PICTURES}/AstroPhoto/" +
                    "${session.folderName}/Processed/"
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            ) ?: error("Не удалось сохранить результат")
            try {
                resolver.openOutputStream(uri, "w")?.use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)) {
                        error("Не удалось сохранить результат")
                    }
                } ?: error("Не удалось сохранить результат")
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                return "Pictures/AstroPhoto/${session.folderName}/Processed/$fileName"
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(
            pictures,
            "AstroPhoto/${session.folderName}/Processed"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Не удалось создать папку Processed")
        }
        val file = File(directory, fileName)
        FileOutputStream(file).use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)) {
                error("Не удалось сохранить результат")
            }
        }
        return file.absolutePath
    }

    private fun appendSessionInfo(
        session: SessionSummary,
        sourceFile: String,
        editedFile: String,
        adjustments: ImageAdjustments,
        crop: CropPixels,
        resultMetrics: EditorImageMetrics,
        editedAtMillis: Long
    ) {
        val block = buildString {
            appendLine()
            appendLine("editedSourceFile: Processed/$sourceFile")
            appendLine("editedFile: Processed/$editedFile")
            appendLine(
                "editSettings: brightness=${adjustments.brightness.toInt()}, " +
                    "contrast=${adjustments.contrast.toInt()}, " +
                    "gamma=${String.format(Locale.US, "%.2f", adjustments.gamma)}, " +
                    "black=${adjustments.blackPoint.toInt()}, " +
                    "white=${adjustments.whitePoint.toInt()}, " +
                    "saturation=${adjustments.saturation.toInt()}"
            )
            appendLine(
                "editedMetrics: averageBrightness=${
                    String.format(Locale.US, "%.2f", resultMetrics.averageBrightness)
                }, shadowPercent=${
                    String.format(Locale.US, "%.2f", resultMetrics.shadowPercent)
                }, highlightPercent=${
                    String.format(Locale.US, "%.2f", resultMetrics.highlightPercent)
                }, clippedBlackPercent=${
                    String.format(Locale.US, "%.2f", resultMetrics.clippedBlackPercent)
                }, clippedWhitePercent=${
                    String.format(Locale.US, "%.2f", resultMetrics.clippedWhitePercent)
                }"
            )
            appendLine("cropLeft: ${crop.left}")
            appendLine("cropRight: ${crop.right}")
            appendLine("cropTop: ${crop.top}")
            appendLine("cropBottom: ${crop.bottom}")
            appendLine("finalWidth: ${crop.finalWidth}")
            appendLine("finalHeight: ${crop.finalHeight}")
            appendLine(
                "editedAt: ${
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date(editedAtMillis))
                }"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreInfo(session, block)
        } else {
            appendLegacyInfo(session, block)
        }
    }

    private fun appendMediaStoreInfo(session: SessionSummary, block: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val uri = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf("session_info.txt", relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        } ?: error("session_info.txt не найден")
        val current = resolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
            it.write(current.trimEnd())
            it.write(block)
        } ?: error("Не удалось обновить session_info.txt")
    }

    @Suppress("DEPRECATION")
    private fun appendLegacyInfo(session: SessionSummary, block: String) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val file = File(
            pictures,
            "AstroPhoto/${session.folderName}/session_info.txt"
        )
        file.parentFile?.mkdirs()
        file.appendText(block)
    }

    companion object {
        private const val MIN_CROP_SIZE = 200
    }
}

private fun isSafeCrop(
    crop: CropSettings,
    width: Int,
    height: Int
): Boolean {
    val finalWidth = width -
        (width * crop.leftPercent / 100f).roundToInt() -
        (width * crop.rightPercent / 100f).roundToInt()
    val finalHeight = height -
        (height * crop.topPercent / 100f).roundToInt() -
        (height * crop.bottomPercent / 100f).roundToInt()
    return finalWidth >= 200 && finalHeight >= 200
}

private fun cropFinalDimensions(
    crop: CropSettings,
    width: Int,
    height: Int
): Pair<Int, Int> {
    val finalWidth = width -
        (width * crop.leftPercent / 100f).roundToInt() -
        (width * crop.rightPercent / 100f).roundToInt()
    val finalHeight = height -
        (height * crop.topPercent / 100f).roundToInt() -
        (height * crop.bottomPercent / 100f).roundToInt()
    return finalWidth.coerceAtLeast(0) to finalHeight.coerceAtLeast(0)
}

@Composable
fun ProcessedImageEditorScreen(
    session: SessionSummary,
    source: ProcessedResult,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val editor = remember { ProcessedImageEditor(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var original by remember(source.key) { mutableStateOf<Bitmap?>(null) }
    var sourceWidth by remember(source.key) { mutableStateOf(0) }
    var sourceHeight by remember(source.key) { mutableStateOf(0) }
    var processed by remember(source.key) { mutableStateOf<Bitmap?>(null) }
    var originalMetrics by remember(source.key) {
        mutableStateOf<EditorImageMetrics?>(null)
    }
    var processedMetrics by remember(source.key) {
        mutableStateOf<EditorImageMetrics?>(null)
    }
    var adjustments by remember(source.key) { mutableStateOf(ImageAdjustments()) }
    var crop by remember(source.key) { mutableStateOf(CropSettings()) }
    var autoAdjustmentSnapshot by remember(source.key) {
        mutableStateOf<ImageAdjustments?>(null)
    }
    var loading by remember(source.key) { mutableStateOf(true) }
    var rendering by remember(source.key) { mutableStateOf(false) }
    var saving by remember(source.key) { mutableStateOf(false) }
    var showOriginal by remember(source.key) { mutableStateOf(false) }
    var status by remember(source.key) { mutableStateOf<String?>(null) }

    LaunchedEffect(source.key) {
        if (!source.isReadable) {
            status = source.errorMessage
                ?: "Не удалось открыть изображение. Файл повреждён или был удалён."
            loading = false
            return@LaunchedEffect
        }
        if (!source.fileName.endsWith(".jpg", true) &&
            !source.fileName.endsWith(".jpeg", true)
        ) {
            status = "Редактор поддерживает только JPEG"
            loading = false
            return@LaunchedEffect
        }
        val loaded = editor.loadPreview(source)
        original = loaded?.bitmap
        sourceWidth = loaded?.sourceWidth ?: 0
        sourceHeight = loaded?.sourceHeight ?: 0
        if (loaded == null) {
            status = "Не удалось прочитать JPEG"
        } else {
            originalMetrics = editor.analyzePreview(loaded.bitmap)
        }
        loading = false
    }

    LaunchedEffect(original, adjustments, crop) {
        val bitmap = original ?: return@LaunchedEffect
        delay(200L)
        rendering = true
        val updated = editor.renderPreview(bitmap, adjustments, crop)
        val updatedMetrics = updated?.let { editor.analyzePreview(it) }
        processed?.takeUnless(Bitmap::isRecycled)?.recycle()
        processed = updated
        processedMetrics = updatedMetrics
        rendering = false
        if (updated == null) status = "Не удалось обработать preview"
    }

    val visibleMetrics = if (showOriginal) originalMetrics else processedMetrics
    val autoStillActive = autoAdjustmentSnapshot == adjustments
    val finalDimensions = cropFinalDimensions(
        crop = crop,
        width = sourceWidth,
        height = sourceHeight
    )

    fun updateCrop(updated: CropSettings) {
        if (
            sourceWidth > 0 &&
            sourceHeight > 0 &&
            isSafeCrop(updated, sourceWidth, sourceHeight)
        ) {
            crop = updated
            showOriginal = false
        } else {
            status = "Обрезка даёт слишком маленькое изображение"
        }
    }

    DisposableEffect(source.key) {
        onDispose {
            original?.takeUnless(Bitmap::isRecycled)?.recycle()
            processed?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 36.dp)
    ) {
        TextButton(onClick = onBack, enabled = !saving) {
            Text("← Назад")
        }
        Text(
            text = "Редактор",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = source.fileName,
            color = Color(0xFFB8BECC),
            style = MaterialTheme.typography.bodySmall
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(top = 10.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator()
                showOriginal && original != null -> Image(
                    bitmap = checkNotNull(original).asImageBitmap(),
                    contentDescription = "До обработки",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                processed != null -> Image(
                    bitmap = checkNotNull(processed).asImageBitmap(),
                    contentDescription = "После обработки",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                else -> Text(
                    text = status ?: "Не удалось прочитать JPEG",
                    color = Color(0xFFFFAB91)
                )
            }
            if (rendering) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = showOriginal,
                onClick = { showOriginal = true },
                label = { Text("До") }
            )
            FilterChip(
                selected = !showOriginal,
                onClick = { showOriginal = false },
                label = { Text("После") }
            )
        }
        EditorHistogramBlock(
            metrics = visibleMetrics,
            showingOriginal = showOriginal,
            autoDataAlreadyLost = autoStillActive &&
                processedMetrics?.hasClipping == true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        EditorCropBlock(
            crop = crop,
            finalWidth = finalDimensions.first,
            finalHeight = finalDimensions.second,
            enabled = original != null && !saving,
            onCropChanged = ::updateCrop,
            onAutoCrop = {
                val bitmap = original ?: return@EditorCropBlock
                coroutineScope.launch {
                    status = "Поиск чёрных краёв..."
                    val uncropped = editor.renderPreview(
                        bitmap,
                        adjustments,
                        CropSettings()
                    )
                    if (uncropped == null) {
                        status = "Не удалось выполнить автообрезку"
                        return@launch
                    }
                    try {
                        val detected = editor.detectBlackEdges(uncropped)
                        if (!detected.found) {
                            status = if (detected.aggressive) {
                                "Автообрезка слишком агрессивная и не применена."
                            } else {
                                "Чёрные края не обнаружены."
                            }
                        } else {
                            updateCrop(detected.settings)
                            status = if (detected.aggressive) {
                                "Автообрезка сильная, проверьте результат."
                            } else {
                                "Автообрезка применена"
                            }
                        }
                    } finally {
                        uncropped.recycle()
                    }
                }
            },
            onResetCrop = {
                crop = CropSettings()
                status = "Обрезка сброшена"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Text(
            text = "Пресеты",
            modifier = Modifier.padding(top = 10.dp),
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EDITOR_PRESETS.forEach { preset ->
                FilterChip(
                    selected = adjustments == preset.adjustments,
                    onClick = {
                        adjustments = preset.adjustments
                        autoAdjustmentSnapshot = null
                        showOriginal = false
                        status = preset.description
                    },
                    label = { Text(preset.name) }
                )
            }
        }

        EditorSlider(
            title = "Яркость",
            value = adjustments.brightness,
            valueRange = -100f..100f,
            valueLabel = adjustments.brightness.toInt().toString(),
            onValueChange = { adjustments = adjustments.copy(brightness = it) }
        )
        EditorSlider(
            title = "Контраст",
            value = adjustments.contrast,
            valueRange = -100f..100f,
            valueLabel = adjustments.contrast.toInt().toString(),
            onValueChange = { adjustments = adjustments.copy(contrast = it) }
        )
        EditorSlider(
            title = "Гамма",
            value = adjustments.gamma,
            valueRange = 0.5f..2.5f,
            valueLabel = String.format(Locale.getDefault(), "%.2f", adjustments.gamma),
            onValueChange = { adjustments = adjustments.copy(gamma = it) }
        )
        EditorSlider(
            title = "Чёрная точка",
            value = adjustments.blackPoint,
            valueRange = 0f..100f,
            valueLabel = adjustments.blackPoint.toInt().toString(),
            onValueChange = { adjustments = adjustments.copy(blackPoint = it) }
        )
        EditorSlider(
            title = "Белая точка",
            value = adjustments.whitePoint,
            valueRange = 155f..255f,
            valueLabel = adjustments.whitePoint.toInt().toString(),
            onValueChange = { adjustments = adjustments.copy(whitePoint = it) }
        )
        EditorSlider(
            title = "Насыщенность",
            value = adjustments.saturation,
            valueRange = 0f..200f,
            valueLabel = "${adjustments.saturation.toInt()}%",
            onValueChange = { adjustments = adjustments.copy(saturation = it) }
        )
        EditorSlider(
            title = "Резкость",
            value = adjustments.sharpness,
            valueRange = 0f..100f,
            valueLabel = "Будет позже",
            onValueChange = {},
            enabled = false
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    adjustments = ImageAdjustments()
                    crop = CropSettings()
                    autoAdjustmentSnapshot = null
                    status = "Настройки сброшены"
                },
                enabled = !saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            ) {
                Text("Сброс")
            }
            Button(
                onClick = {
                    val bitmap = original ?: return@Button
                    coroutineScope.launch {
                        val automatic = editor.autoAdjustments(bitmap)
                        if (automatic != null) {
                            adjustments = automatic
                            autoAdjustmentSnapshot = automatic
                            status = "Авторастяжение применено"
                        } else {
                            status = "Не удалось применить автонастройку"
                        }
                    }
                },
                enabled = original != null && !saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            ) {
                Text("Авто")
            }
        }
        Button(
            onClick = {
                val bitmap = original ?: return@Button
                coroutineScope.launch {
                    val automatic = editor.astroAutoAdjustments(bitmap)
                    if (automatic != null) {
                        adjustments = automatic
                        autoAdjustmentSnapshot = automatic
                        showOriginal = false
                        status = "Astro Auto применён: мягко вытягиваем слабые звёзды без жёсткой чёрной точки"
                    } else {
                        status = "Не удалось применить Astro Auto"
                    }
                }
            },
            enabled = original != null && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(top = 8.dp)
        ) {
            Text("Astro Auto")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val bitmap = original ?: return@Button
                    coroutineScope.launch {
                        val urban = editor.urbanSkyAdjustments(bitmap)
                        if (urban != null) {
                            adjustments = urban
                            autoAdjustmentSnapshot = urban
                            showOriginal = false
                            status = "Городское небо: мягко уменьшаем засветку и сохраняем слабые точки"
                        } else {
                            status = "Не удалось применить профиль городского неба"
                        }
                    }
                },
                enabled = original != null && !saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            ) {
                Text("Город")
            }
            Button(
                onClick = {
                    val bitmap = original ?: return@Button
                    coroutineScope.launch {
                        val maxStars = editor.maxStarsAdjustments(bitmap)
                        if (maxStars != null) {
                            adjustments = maxStars
                            autoAdjustmentSnapshot = maxStars
                            showOriginal = false
                            status = "Максимум звёзд применён. Может усилить шум."
                        } else {
                            status = "Не удалось применить максимум звёзд"
                        }
                    }
                },
                enabled = original != null && !saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            ) {
                Text("Макс. звёзд")
            }
        }
        TextButton(
            onClick = {
                adjustments = ImageAdjustments()
                autoAdjustmentSnapshot = null
                showOriginal = false
                status = "Астро-настройки сброшены, обрезка сохранена."
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сбросить астро")
        }
        if (adjustments.blackPoint > 24f) {
            Text(
                text = "Риск потери слабых деталей: чёрная точка слишком высокая.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80)
            )
        }
        Button(
            onClick = {
                val fallback = original ?: return@Button
                saving = true
                status = "Сохранение..."
                coroutineScope.launch {
                    val result = editor.saveEditedCopy(
                        session = session,
                        source = source,
                        adjustments = adjustments,
                        crop = crop,
                        preview = fallback,
                        resultMetrics = processedMetrics
                    )
                    saving = false
                    status = result.fold(
                        onSuccess = {
                            onSaved()
                            buildString {
                                append("Сохранено: ${it.fileName}")
                                if (it.usedPreviewFallback) {
                                    append(
                                        "\nНе хватило памяти для оригинала; " +
                                            "сохранена preview-версия."
                                    )
                                }
                                if (!it.sessionInfoUpdated) {
                                    append("\nsession_info.txt обновить не удалось.")
                                }
                            }
                        },
                        onFailure = {
                            it.message ?: "Не удалось сохранить результат"
                        }
                    )
                }
            },
            enabled = original != null && processed != null && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(top = 8.dp)
        ) {
            Text(if (saving) "Сохранение..." else "Сохранить копию")
        }
        if (saving) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
        status?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                color = if (
                    it.startsWith("Не удалось") ||
                    it.startsWith("Редактор поддерживает")
                ) {
                    Color(0xFFFFAB91)
                } else {
                    Color(0xFFA5D6A7)
                }
            )
        }
    }
}

@Composable
private fun EditorCropBlock(
    crop: CropSettings,
    finalWidth: Int,
    finalHeight: Int,
    enabled: Boolean,
    onCropChanged: (CropSettings) -> Unit,
    onAutoCrop: () -> Unit,
    onResetCrop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF121722), MaterialTheme.shapes.medium)
            .padding(10.dp)
    ) {
        Text(
            text = "Обрезка",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Итоговый размер: ${finalWidth}×${finalHeight} px",
            modifier = Modifier.padding(top = 3.dp),
            color = Color(0xFFD5DBE8)
        )
        EditorSlider(
            title = "Слева",
            value = crop.leftPercent,
            valueRange = 0f..30f,
            valueLabel = String.format(Locale.getDefault(), "%.1f%%", crop.leftPercent),
            onValueChange = { onCropChanged(crop.copy(leftPercent = it)) },
            enabled = enabled
        )
        EditorSlider(
            title = "Справа",
            value = crop.rightPercent,
            valueRange = 0f..30f,
            valueLabel = String.format(Locale.getDefault(), "%.1f%%", crop.rightPercent),
            onValueChange = { onCropChanged(crop.copy(rightPercent = it)) },
            enabled = enabled
        )
        EditorSlider(
            title = "Сверху",
            value = crop.topPercent,
            valueRange = 0f..30f,
            valueLabel = String.format(Locale.getDefault(), "%.1f%%", crop.topPercent),
            onValueChange = { onCropChanged(crop.copy(topPercent = it)) },
            enabled = enabled
        )
        EditorSlider(
            title = "Снизу",
            value = crop.bottomPercent,
            valueRange = 0f..30f,
            valueLabel = String.format(Locale.getDefault(), "%.1f%%", crop.bottomPercent),
            onValueChange = { onCropChanged(crop.copy(bottomPercent = it)) },
            enabled = enabled
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAutoCrop,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text("Автообрезка")
            }
            TextButton(
                onClick = onResetCrop,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text("Сбросить обрезку")
            }
        }
        Text(
            text = "Максимум 30% на сторону, минимум результата 200×200 px.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB8BECC)
        )
    }
}

@Composable
private fun EditorHistogramBlock(
    metrics: EditorImageMetrics?,
    showingOriginal: Boolean,
    autoDataAlreadyLost: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0D1119), MaterialTheme.shapes.medium)
            .padding(10.dp)
    ) {
        Text(
            text = "Гистограмма • ${if (showingOriginal) "До" else "После"}",
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .padding(top = 5.dp)
                .background(Color.Black)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val data = metrics ?: return@Canvas
                val barWidth = size.width / data.histogram.size
                data.histogram.forEachIndexed { index, value ->
                    val barHeight = size.height * value.coerceIn(0f, 1f)
                    drawLine(
                        color = Color(0xFFB7C9FF),
                        start = Offset(
                            index * barWidth + barWidth / 2f,
                            size.height
                        ),
                        end = Offset(
                            index * barWidth + barWidth / 2f,
                            size.height - barHeight
                        ),
                        strokeWidth = (barWidth * 0.7f).coerceAtLeast(1f)
                    )
                }
                if (
                    data.clippedBlackPercent >
                    EditorImageMetrics.CLIPPING_WARNING_PERCENT
                ) {
                    drawRect(
                        color = Color(0xFFFF8A80),
                        size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height)
                    )
                }
                if (
                    data.clippedWhitePercent >
                    EditorImageMetrics.CLIPPING_WARNING_PERCENT
                ) {
                    drawRect(
                        color = Color(0xFFFFCC80),
                        topLeft = Offset(size.width - 5.dp.toPx(), 0f),
                        size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height)
                    )
                }
            }
        }
        metrics?.let { data ->
            Text(
                text = String.format(
                    Locale.getDefault(),
                    "Средняя: %.1f • Тени: %.1f%% • Света: %.1f%%\n" +
                        "Чёрный клиппинг: %.2f%% • Белый: %.2f%%",
                    data.averageBrightness,
                    data.shadowPercent,
                    data.highlightPercent,
                    data.clippedBlackPercent,
                    data.clippedWhitePercent
                ),
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD5DBE8)
            )
            data.warnings().forEach { warning ->
                Text(
                    text = warning,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (
                        warning == "Тональный диапазон выглядит нормально."
                    ) {
                        Color(0xFFA5D6A7)
                    } else {
                        Color(0xFFFFCC80)
                    }
                )
            }
            if (autoDataAlreadyLost) {
                Text(
                    text = "Авто улучшило изображение, но часть данных уже потеряна.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFCC80)
                )
            }
        } ?: Text(
            text = "Расчёт гистограммы…",
            modifier = Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB8BECC)
        )
    }
}

@Composable
private fun EditorSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    Text(
        text = "$title: $valueLabel",
        modifier = Modifier.padding(top = 10.dp),
        fontWeight = FontWeight.SemiBold
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

private val EDITOR_PRESETS = listOf(
    EditorPreset(
        name = "Мягко",
        description = "Мягко: немного контраста и цвета.",
        adjustments = ImageAdjustments(
            contrast = 10f,
            gamma = 1.1f,
            saturation = 110f
        )
    ),
    EditorPreset(
        name = "Больше звёзд",
        description = "Больше звёзд: усилены чёрная точка и контраст.",
        adjustments = ImageAdjustments(
            contrast = 28f,
            gamma = 1.2f,
            blackPoint = 18f,
            saturation = 110f
        )
    ),
    EditorPreset(
        name = "Городское небо",
        description = "Городское небо: меньше яркости и городской засветки.",
        adjustments = ImageAdjustments(
            brightness = -12f,
            contrast = 25f,
            blackPoint = 10f,
            saturation = 90f
        )
    ),
    EditorPreset(
        name = "Луна",
        description = "Луна: повышен контраст без сильной гаммы.",
        adjustments = ImageAdjustments(
            contrast = 30f,
            gamma = 1.0f,
            saturation = 80f
        )
    ),
    EditorPreset(
        name = "Слабый объект",
        description = "Слабый объект: подняты гамма и яркость.",
        adjustments = ImageAdjustments(
            brightness = 12f,
            contrast = 15f,
            gamma = 1.4f,
            saturation = 110f
        )
    )
)
