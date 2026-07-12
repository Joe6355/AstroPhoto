package com.example.astrophoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

data class ImageBounds(
    val width: Int,
    val height: Int
)

sealed interface ImageLoadResult {
    data class Success(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sampleSize: Int,
        val warningTitle: String? = null,
        val warningMessage: String? = null
    ) : ImageLoadResult

    data class Error(
        val userTitle: String,
        val userMessage: String,
        val technicalMessage: String,
        val throwable: Throwable? = null
    ) : ImageLoadResult
}

class SafeImageLoader(private val context: Context) {
    private val sourceResolver = ProcessedImageSourceResolver(context)

    suspend fun loadImageBounds(pathOrUri: String): Result<ImageBounds> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = decodeBounds(pathOrUri)
                ImageBounds(bounds.outWidth, bounds.outHeight)
            }
        }

    suspend fun loadBitmapForViewer(
        pathOrUri: String,
        maxSizePx: Int = 3072
    ): ImageLoadResult = withContext(Dispatchers.IO) {
        loadBitmap(pathOrUri, maxSizePx, allowEmergencyDownsample = true)
    }

    suspend fun loadBitmapForViewer(
        source: ImageSourceReference,
        maxSizePx: Int = 3072
    ): ImageLoadResult = withContext(Dispatchers.IO) {
        loadBitmap(source, maxSizePx, allowEmergencyDownsample = true)
    }

    suspend fun loadBitmapThumbnail(
        pathOrUri: String,
        maxSizePx: Int = 256
    ): ImageLoadResult = withContext(Dispatchers.IO) {
        loadBitmap(pathOrUri, maxSizePx, allowEmergencyDownsample = false)
    }

    suspend fun loadBitmapThumbnail(
        source: ImageSourceReference,
        maxSizePx: Int = 256
    ): ImageLoadResult = withContext(Dispatchers.IO) {
        loadBitmap(source, maxSizePx, allowEmergencyDownsample = false)
    }

    fun isReadableJpeg(pathOrUri: String): Boolean {
        val lower = pathOrUri.lowercase()
        val looksLikeJpeg = lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.contains(".jpg?") ||
            lower.contains(".jpeg?")
        if (!looksLikeJpeg && !lower.startsWith("content://")) return false
        return try {
            openSource(pathOrUri)?.use { true } == true
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    fun isReadableJpeg(source: ImageSourceReference): Boolean {
        val resolved = sourceResolver.resolve(source) ?: return false
        return isReadableJpeg(resolved.pathOrUri)
    }

    private fun loadBitmap(
        source: ImageSourceReference,
        maxSizePx: Int,
        allowEmergencyDownsample: Boolean
    ): ImageLoadResult {
        val resolved = sourceResolver.resolve(source)
            ?: return imageError(
                userTitle = "Файл не найден",
                userMessage = "Возможно, он был удалён.",
                technicalMessage = "Image source could not be resolved: ${source.displayName}"
            )
        return loadBitmap(resolved.pathOrUri, maxSizePx, allowEmergencyDownsample)
    }

    private fun loadBitmap(
        pathOrUri: String,
        maxSizePx: Int,
        allowEmergencyDownsample: Boolean
    ): ImageLoadResult {
        val bounds = try {
            decodeBounds(pathOrUri)
        } catch (error: FileNotFoundException) {
            return imageError(
                userTitle = "Файл не найден",
                userMessage = "Возможно, он был удалён.",
                technicalMessage = "Image source not found: $pathOrUri",
                throwable = error
            )
        } catch (error: Exception) {
            return imageError(
                userTitle = "Не удалось открыть изображение",
                userMessage = "Файл повреждён или не поддерживается.",
                technicalMessage = "Failed to read image bounds: $pathOrUri",
                throwable = error
            )
        } catch (error: OutOfMemoryError) {
            return imageError(
                userTitle = "Изображение слишком большое",
                userMessage = "Не удалось открыть уменьшенную копию.",
                technicalMessage = "Out of memory while reading bounds: $pathOrUri",
                throwable = error
            )
        }

        val sampleSize = calculateSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxSizePx = maxSizePx
        )
        return try {
            val bitmap = decodeBitmap(pathOrUri, sampleSize, Bitmap.Config.ARGB_8888)
                ?: return imageError(
                    userTitle = "Не удалось открыть изображение",
                    userMessage = "Файл повреждён или не поддерживается.",
                    technicalMessage = "BitmapFactory returned null: $pathOrUri"
                )
            ImageLoadResult.Success(
                bitmap = bitmap,
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                sampleSize = sampleSize
            )
        } catch (error: OutOfMemoryError) {
            if (!allowEmergencyDownsample) {
                return imageError(
                    userTitle = "Изображение слишком большое",
                    userMessage = "Не удалось открыть уменьшенную копию.",
                    technicalMessage = "Out of memory while decoding thumbnail: $pathOrUri",
                    throwable = error
                )
            }
            decodeEmergencyCopy(pathOrUri, bounds, sampleSize, error)
        } catch (error: FileNotFoundException) {
            imageError(
                userTitle = "Файл не найден",
                userMessage = "Возможно, он был удалён.",
                technicalMessage = "Image source disappeared while decoding: $pathOrUri",
                throwable = error
            )
        } catch (error: Exception) {
            imageError(
                userTitle = "Не удалось открыть изображение",
                userMessage = "Файл повреждён или не поддерживается.",
                technicalMessage = "Failed to decode image: $pathOrUri",
                throwable = error
            )
        }
    }

    private fun decodeEmergencyCopy(
        pathOrUri: String,
        bounds: BitmapFactory.Options,
        originalSampleSize: Int,
        originalError: OutOfMemoryError
    ): ImageLoadResult {
        var sampleSize = (originalSampleSize * 2).coerceAtLeast(2)
        repeat(4) {
            try {
                val bitmap = decodeBitmap(pathOrUri, sampleSize, Bitmap.Config.RGB_565)
                if (bitmap != null) {
                    return ImageLoadResult.Success(
                        bitmap = bitmap,
                        sourceWidth = bounds.outWidth,
                        sourceHeight = bounds.outHeight,
                        sampleSize = sampleSize,
                        warningTitle = "Изображение слишком большое",
                        warningMessage = "Открыта уменьшенная копия."
                    )
                }
            } catch (_: OutOfMemoryError) {
                sampleSize *= 2
            } catch (error: FileNotFoundException) {
                return imageError(
                    userTitle = "Файл не найден",
                    userMessage = "Возможно, он был удалён.",
                    technicalMessage = "Image source disappeared during emergency decode: $pathOrUri",
                    throwable = error
                )
            } catch (error: Exception) {
                return imageError(
                    userTitle = "Не удалось открыть изображение",
                    userMessage = "Файл повреждён или не поддерживается.",
                    technicalMessage = "Emergency decode failed: $pathOrUri",
                    throwable = error
                )
            }
        }
        return imageError(
            userTitle = "Изображение слишком большое",
            userMessage = "Не удалось открыть уменьшенную копию.",
            technicalMessage = "Out of memory while decoding image: $pathOrUri",
            throwable = originalError
        )
    }

    private fun decodeBounds(pathOrUri: String): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource(pathOrUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw FileNotFoundException(pathOrUri)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Unsupported or corrupted image")
        }
        return bounds
    }

    private fun decodeBitmap(
        pathOrUri: String,
        sampleSize: Int,
        config: Bitmap.Config
    ): Bitmap? =
        openSource(pathOrUri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize.coerceAtLeast(1)
                    inPreferredConfig = config
                }
            )
        }

    private fun openSource(pathOrUri: String): InputStream? {
        if (pathOrUri.isBlank()) throw FileNotFoundException("Empty image path")
        val uri = Uri.parse(pathOrUri)
        return when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { File(it) }?.openExistingInputStream()
            else -> File(pathOrUri).openExistingInputStream()
        }
    }

    private fun File.openExistingInputStream(): InputStream {
        if (!exists() || !isFile || !canRead()) {
            throw FileNotFoundException(absolutePath)
        }
        require(length() > 0L) { "Empty image file" }
        return inputStream()
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxSizePx: Int
    ): Int {
        val safeMax = maxSizePx.coerceAtLeast(256)
        var sampleSize = 1
        while (width / sampleSize > safeMax || height / sampleSize > safeMax) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun imageError(
        userTitle: String,
        userMessage: String,
        technicalMessage: String,
        throwable: Throwable? = null
    ): ImageLoadResult.Error {
        if (throwable != null) {
            Log.e("AstroPhotoImageOpen", technicalMessage, throwable)
        } else {
            Log.e("AstroPhotoImageOpen", technicalMessage)
        }
        return ImageLoadResult.Error(
            userTitle = userTitle,
            userMessage = userMessage,
            technicalMessage = technicalMessage,
            throwable = throwable
        )
    }
}
