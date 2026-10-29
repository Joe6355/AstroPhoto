package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.joe6355.astrophoto.processing.jpeg.v2.output.PngImageSource
import java.io.Closeable
import java.io.InputStream

internal interface EditorExportSource : PngImageSource, Closeable {
    val crop: CropPixels
    fun metrics(): EditorImageMetrics
}

internal fun interface EditorExportSourceFactory {
    fun open(source: ProcessedResult, adjustments: ImageAdjustments, crop: CropSettings): EditorExportSource
}

/** Native-scale stripes; the decoder never retries with a smaller image. */
internal class RegionEditorExportSource private constructor(
    private val decoder: BitmapRegionDecoder,
    override val crop: CropPixels,
    adjustments: ImageAdjustments
) : EditorExportSource {
    private var stripe: Bitmap? = null
    private var stripeTop = -1
    private val rows = EditorExportRows(decoder.width, decoder.height, crop, adjustments, ::readSourceRow)
    override val width: Int get() = rows.width
    override val height: Int get() = rows.height
    var maximumDecodedRows: Int = 0
        private set

    override fun readArgbRow(y: Int, destination: IntArray) = rows.readArgbRow(y, destination)
    override fun metrics(): EditorImageMetrics = rows.metrics()

    private fun readSourceRow(y: Int, destination: IntArray) {
        val top = y / STRIPE_ROWS * STRIPE_ROWS
        if (stripe == null || stripeTop != top) {
            stripe?.recycle()
            stripe = null
            val bottom = minOf(decoder.height, top + STRIPE_ROWS)
            val decoded = decoder.decodeRegion(Rect(0, top, decoder.width, bottom),
                BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }) ?: error("Не удалось прочитать оригинал в полном размере")
            try {
                check(decoded.width == decoder.width && decoded.height == bottom - top) {
                    "Не удалось прочитать фрагмент в полном размере"
                }
                stripe = decoded
                stripeTop = top
                maximumDecodedRows = maxOf(maximumDecodedRows, decoded.height)
            } catch (error: Throwable) {
                decoded.recycle()
                throw error
            }
        }
        requireNotNull(stripe).getPixels(destination, 0, decoder.width, 0, y - stripeTop, decoder.width, 1)
    }

    override fun close() {
        stripe?.recycle()
        stripe = null
        if (!decoder.isRecycled) decoder.recycle()
    }

    companion object {
        private const val STRIPE_ROWS = 32

        @Suppress("DEPRECATION") // InputStream overload supports minSdk 26.
        fun open(openInput: () -> InputStream?, adjustments: ImageAdjustments, crop: CropSettings): RegionEditorExportSource {
            val decoder = openInput()?.use { BitmapRegionDecoder.newInstance(it, false) }
                ?: error("Не удалось прочитать оригинал в полном размере")
            return try {
                RegionEditorExportSource(decoder, editorCropPixels(decoder.width, decoder.height, crop), adjustments)
            } catch (error: Throwable) {
                decoder.recycle()
                throw error
            }
        }
    }
}
