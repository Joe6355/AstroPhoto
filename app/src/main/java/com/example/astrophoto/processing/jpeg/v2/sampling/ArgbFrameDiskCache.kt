package com.example.astrophoto.processing.jpeg.v2.sampling

import android.graphics.Bitmap
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class CachedArgbFrame(
    val file: File,
    val width: Int,
    val height: Int,
    val referenceToSourceTransform: ReferenceToSourceTransform? = null
)

/**
 * Stores one oriented decode at a time as lossless ARGB rows. Integration can then reopen small
 * row windows without retaining all accepted full-resolution bitmaps or decoding JPEG per tile.
 */
object ArgbFrameDiskCache {
    fun write(
        bitmap: Bitmap,
        file: File,
        referenceToSourceTransform: ReferenceToSourceTransform? = null,
        onRowWritten: (Int) -> Unit = {}
    ): CachedArgbFrame {
        require(!file.exists())
        val row = IntArray(bitmap.width)
        val encodedRow = ByteArray(bitmap.width * Int.SIZE_BYTES)
        try {
            BufferedOutputStream(FileOutputStream(file)).use { output ->
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in row.indices) {
                        val color = row[x]
                        val offset = x * Int.SIZE_BYTES
                        encodedRow[offset] = (color ushr 24).toByte()
                        encodedRow[offset + 1] = (color ushr 16).toByte()
                        encodedRow[offset + 2] = (color ushr 8).toByte()
                        encodedRow[offset + 3] = color.toByte()
                    }
                    output.write(encodedRow)
                    onRowWritten(y + 1)
                }
            }
            val expectedBytes = bitmap.width.toLong() * bitmap.height * Int.SIZE_BYTES
            require(file.length() == expectedBytes) { "Incomplete ARGB integration cache" }
            return CachedArgbFrame(file, bitmap.width, bitmap.height, referenceToSourceTransform)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}

class FileBackedArgbPixelSource(
    cached: CachedArgbFrame,
    cachedRowCount: Int = DEFAULT_CACHED_ROWS
) : ArgbPixelSource {
    override val width: Int = cached.width
    override val height: Int = cached.height
    private val input = RandomAccessFile(cached.file, "r")
    private val rowIndices = IntArray(cachedRowCount) { -1 }
    private val rows = Array(cachedRowCount) { IntArray(width) }
    private val encodedRow = ByteArray(width * Int.SIZE_BYTES)
    private var replacementIndex = 0

    init {
        require(width > 0 && height > 0 && cachedRowCount >= 2)
    }

    override fun argbAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        val existing = rowIndices.indexOf(y)
        val slot = if (existing >= 0) existing else loadRow(y)
        return rows[slot][x]
    }

    override fun close() {
        input.close()
    }

    private fun loadRow(y: Int): Int {
        val slot = replacementIndex
        replacementIndex = (replacementIndex + 1) % rows.size
        input.seek(y.toLong() * width * Int.SIZE_BYTES)
        input.readFully(encodedRow)
        for (x in 0 until width) {
            val offset = x * Int.SIZE_BYTES
            rows[slot][x] =
                ((encodedRow[offset].toInt() and 0xFF) shl 24) or
                ((encodedRow[offset + 1].toInt() and 0xFF) shl 16) or
                ((encodedRow[offset + 2].toInt() and 0xFF) shl 8) or
                (encodedRow[offset + 3].toInt() and 0xFF)
        }
        rowIndices[slot] = y
        return slot
    }

    companion object {
        private const val DEFAULT_CACHED_ROWS = 6
    }
}
