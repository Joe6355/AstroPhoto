package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import com.joe6355.astrophoto.processing.jpeg.v2.output.requireValidPngDimensions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

enum class JpegOrientation(val swapsDimensions: Boolean) {
    NORMAL(false),
    FLIP_HORIZONTAL(false),
    ROTATE_180(false),
    FLIP_VERTICAL(false),
    TRANSPOSE(true),
    ROTATE_90(true),
    TRANSVERSE(true),
    ROTATE_270(true)
}

fun orientedDimensions(width: Int, height: Int, orientation: JpegOrientation): Pair<Int, Int> {
    require(width > 0 && height > 0)
    return if (orientation.swapsDimensions) height to width else width to height
}

internal fun readOrientedJpegDimensions(openStream: () -> InputStream?): Pair<Int, Int>? {
    val orientation = readOrientation(openStream)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return orientedDimensions(options.outWidth, options.outHeight, orientation)
}

internal fun decodeOrientedJpeg(
    openStream: () -> InputStream?,
    sampleSize: Int = 1
): Bitmap? {
    val orientation = readOrientation(openStream)
    val decoded = openStream()?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } ?: return null
    if (orientation == JpegOrientation.NORMAL) return decoded
    val matrix = Matrix().apply {
        when (orientation) {
            JpegOrientation.NORMAL -> Unit
            JpegOrientation.FLIP_HORIZONTAL -> setScale(-1f, 1f)
            JpegOrientation.ROTATE_180 -> setRotate(180f)
            JpegOrientation.FLIP_VERTICAL -> setScale(1f, -1f)
            JpegOrientation.TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            JpegOrientation.ROTATE_90 -> setRotate(90f)
            JpegOrientation.TRANSVERSE -> {
                setRotate(270f)
                postScale(-1f, 1f)
            }
            JpegOrientation.ROTATE_270 -> setRotate(270f)
        }
    }
    return try {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } finally {
        decoded.recycle()
    }
}

private fun readOrientation(openStream: () -> InputStream?): JpegOrientation = runCatching {
    val value = openStream()?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    when (value) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> JpegOrientation.FLIP_HORIZONTAL
        ExifInterface.ORIENTATION_ROTATE_180 -> JpegOrientation.ROTATE_180
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> JpegOrientation.FLIP_VERTICAL
        ExifInterface.ORIENTATION_TRANSPOSE -> JpegOrientation.TRANSPOSE
        ExifInterface.ORIENTATION_ROTATE_90 -> JpegOrientation.ROTATE_90
        ExifInterface.ORIENTATION_TRANSVERSE -> JpegOrientation.TRANSVERSE
        ExifInterface.ORIENTATION_ROTATE_270 -> JpegOrientation.ROTATE_270
        else -> JpegOrientation.NORMAL
    }
}.getOrDefault(JpegOrientation.NORMAL)

/** Decodes bounded source regions, applying all eight EXIF orientations without a full Bitmap. */
@Suppress("DEPRECATION")
internal suspend fun cacheOrientedJpeg(
    openStream: () -> InputStream?,
    files: TemporaryPipelineFiles,
    label: String,
    budget: JpegMemoryBudget,
    onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
): FileBackedImage {
    val compressed = files.file("$label-source.jpg")
    try {
        openStream()?.use { source ->
            compressed.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    files.requireSpace(count.toLong())
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Не удалось открыть исходный JPEG")
        val orientation = readOrientation { compressed.inputStream() }
        val decoder = BitmapRegionDecoder.newInstance(compressed.absolutePath, false)
        try {
            val (width, height) = orientedDimensions(decoder.width, decoder.height, orientation)
            requireValidPngDimensions(width, height)
            files.requireSpace(width.toLong() * height * 4L)
            val tile = budget.chooseTile(decoder.width, decoder.height, 512, 512,
                argbBuffers = 3, floatBuffers = 0, residentBytes = width * 4L + 2L * 1024 * 1024)
            require(tile.accepted) { "Недостаточно безопасной памяти для декодирования JPEG" }
            FileBackedImageWriter(files.file("$label.argb"), width, height).use { writer ->
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                for (top in 0 until decoder.height step tile.tileHeight) {
                    for (left in 0 until decoder.width step tile.tileWidth) {
                        currentCoroutineContext().ensureActive()
                        val rawWidth = minOf(tile.tileWidth, decoder.width - left)
                        val rawHeight = minOf(tile.tileHeight, decoder.height - top)
                        val bitmap = decoder.decodeRegion(Rect(left, top, left + rawWidth, top + rawHeight), options)
                            ?: error("Не удалось декодировать участок JPEG")
                        try {
                            val pixels = IntArray(rawWidth * rawHeight)
                            bitmap.getPixels(pixels, 0, rawWidth, 0, 0, rawWidth, rawHeight)
                            val corners = listOf(left to top, left + rawWidth - 1 to top + rawHeight - 1)
                                .map { (x, y) -> orientedPixel(x, y, decoder.width, decoder.height, orientation) }
                            val destLeft = corners.minOf { it.first }
                            val destTop = corners.minOf { it.second }
                            val destWidth = if (orientation.swapsDimensions) rawHeight else rawWidth
                            val destHeight = if (orientation.swapsDimensions) rawWidth else rawHeight
                            val oriented = IntArray(pixels.size)
                            val origin = corners.first()
                            val nextX = orientedPixel(left + 1, top, decoder.width, decoder.height, orientation)
                            val nextY = orientedPixel(left, top + 1, decoder.width, decoder.height, orientation)
                            val originIndex = (origin.second - destTop) * destWidth + origin.first - destLeft
                            val stepX = (nextX.second - origin.second) * destWidth + nextX.first - origin.first
                            val stepY = (nextY.second - origin.second) * destWidth + nextY.first - origin.first
                            for (y in 0 until rawHeight) for (x in 0 until rawWidth) {
                                oriented[originIndex + y * stepY + x * stepX] = pixels[y * rawWidth + x]
                            }
                            writer.writeTile(destLeft, destTop, destWidth, destHeight, oriented)
                        } finally { bitmap.recycle() }
                    }
                    onProgress(minOf(top + tile.tileHeight, decoder.height), decoder.height)
                }
                return writer.finish()
            }
        } finally { decoder.recycle() }
    } finally { compressed.delete() }
}

internal fun orientedPixel(x: Int, y: Int, width: Int, height: Int, orientation: JpegOrientation): Pair<Int, Int> =
    when (orientation) {
        JpegOrientation.NORMAL -> x to y
        JpegOrientation.FLIP_HORIZONTAL -> width - 1 - x to y
        JpegOrientation.ROTATE_180 -> width - 1 - x to height - 1 - y
        JpegOrientation.FLIP_VERTICAL -> x to height - 1 - y
        JpegOrientation.TRANSPOSE -> y to x
        JpegOrientation.ROTATE_90 -> height - 1 - y to x
        JpegOrientation.TRANSVERSE -> height - 1 - y to width - 1 - x
        JpegOrientation.ROTATE_270 -> y to width - 1 - x
    }
