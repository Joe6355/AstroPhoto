package com.example.astrophoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
