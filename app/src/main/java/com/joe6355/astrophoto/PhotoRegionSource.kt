package com.joe6355.astrophoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.FileNotFoundException

internal data class PhotoTile(val bitmap: Bitmap, val region: Rect, val sample: Int)

/** The decoder and its encoded source belong to one viewer and are closed with that viewer. */
internal class PhotoRegionSource private constructor(
    private val decoder: BitmapRegionDecoder,
    val orientation: Int
) : Closeable {
    val rawSize = IntSize(decoder.width, decoder.height)
    val orientationMatrix = photoOrientationMatrix(rawSize.width, rawSize.height, orientation)
    val orientedSize: IntSize = if (orientation in 5..8) IntSize(rawSize.height, rawSize.width) else rawSize

    fun request(viewport: PhotoViewport): PhotoRegionRequest {
        val visible = viewport.visibleSource()
        val raw = RectF(visible.left, visible.top, visible.right, visible.bottom)
        val inverse = Matrix()
        check(orientationMatrix.invert(inverse))
        inverse.mapRect(raw)
        return photoRegionRequest(ComposeRect(raw.left, raw.top, raw.right, raw.bottom),
            rawSize.width, rawSize.height, viewport.scale)
    }

    @Synchronized
    fun decode(request: PhotoRegionRequest): PhotoTile {
        val rect = Rect(request.left, request.top, request.right, request.bottom)
        var sample = request.sample
        repeat(4) { attempt ->
            try {
                val bitmap = decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }) ?: error("Не удалось прочитать изображение")
                return PhotoTile(bitmap, rect, sample)
            } catch (error: OutOfMemoryError) {
                if (attempt == 3) throw error
                sample *= 2
            }
        }
        error("Unreachable")
    }

    fun overview(): PhotoTile {
        var sample = 1
        while (maxOf(rawSize.width, rawSize.height) / sample > 1024) sample *= 2
        return decode(PhotoRegionRequest(0, 0, rawSize.width, rawSize.height, sample))
    }

    @Synchronized
    override fun close() { if (!decoder.isRecycled) decoder.recycle() }

    companion object {
        @Suppress("DEPRECATION") // InputStream overload supports minSdk 26.
        fun open(context: Context, reference: ImageSourceReference): PhotoRegionSource {
            val resolver = ProcessedImageSourceResolver(context)
            val orientation = resolver.openInputStream(reference)?.use { stream ->
                runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: throw FileNotFoundException(reference.displayName)
            val decoder = resolver.openInputStream(reference)?.use {
                BitmapRegionDecoder.newInstance(it, false)
            } ?: throw FileNotFoundException(reference.displayName)
            return try { PhotoRegionSource(decoder, orientation.coerceIn(1, 8)) }
            catch (error: Throwable) { decoder.recycle(); throw error }
        }
    }
}

/** All eight EXIF orientations, including mirrored images; output starts at (0, 0). */
internal fun photoOrientationMatrix(width: Int, height: Int, orientation: Int): Matrix {
    val w = width.toFloat(); val h = height.toFloat()
    val corners = when (orientation) {
        2 -> floatArrayOf(w, 0f, 0f, 0f, w, h)
        3 -> floatArrayOf(w, h, 0f, h, w, 0f)
        4 -> floatArrayOf(0f, h, w, h, 0f, 0f)
        5 -> floatArrayOf(0f, 0f, 0f, w, h, 0f)
        6 -> floatArrayOf(h, 0f, h, w, 0f, 0f)
        7 -> floatArrayOf(h, w, h, 0f, 0f, w)
        8 -> floatArrayOf(0f, w, 0f, 0f, h, w)
        else -> floatArrayOf(0f, 0f, w, 0f, 0f, h)
    }
    return Matrix().apply {
        setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, corners, 0, 3)
    }
}
