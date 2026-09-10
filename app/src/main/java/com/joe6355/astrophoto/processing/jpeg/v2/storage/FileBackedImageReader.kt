package com.joe6355.astrophoto.processing.jpeg.v2.storage

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.output.PngImageSource
import java.io.RandomAccessFile

class FileBackedImageReader(
    val image: FileBackedImage,
    cachedRows: Int = DEFAULT_CACHED_ROWS
) : LinearRgbPixelSource, PngImageSource {
    init {
        require(cachedRows >= 1)
        image.validate()
    }

    override val width: Int = image.width
    override val height: Int = image.height
    private val rowIndices = IntArray(cachedRows) { -1 }
    private val rows = Array(cachedRows) { LongArray(width) }
    private val encodedRow = ByteArray(image.rowStrideBytes)
    // Allocate row buffers before opening the file so a failed allocation cannot leak its handle.
    private val input = RandomAccessFile(image.file, "r")
    private var replacementIndex = 0
    private var closed = false

    override fun linearRgbAt(x: Int, y: Int): Long {
        require(x in 0 until width && y in 0 until height)
        val color = rows[rowSlot(y)][x]
        return if (image.pixelFormat == FileBackedPixelFormat.ARGB_8888) LinearRgb16.fromArgb(color.toInt()) else color
    }

    override fun argbAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return displayPixel(rows[rowSlot(y)][x])
    }

    private fun displayPixel(color: Long): Int = if (image.pixelFormat == FileBackedPixelFormat.ARGB_8888) color.toInt()
        else LinearRgb16.toArgb(color)

    override fun readArgbRow(y: Int, destination: IntArray) {
        require(y in 0 until height && destination.size >= width)
        val row = rows[rowSlot(y)]
        for (x in 0 until width) destination[x] = displayPixel(row[x])
    }

    fun readLinearRow(y: Int, destination: LongArray) {
        require(y in 0 until height && destination.size >= width)
        rows[rowSlot(y)].copyInto(destination, endIndex = width)
        if (image.pixelFormat == FileBackedPixelFormat.ARGB_8888) {
            for (x in 0 until width) destination[x] = LinearRgb16.fromArgb(destination[x].toInt())
        }
    }

    fun readTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, destination: IntArray) {
        validateTile(left, top, tileWidth, tileHeight, destination.size)
        for (row in 0 until tileHeight) {
            val source = rows[rowSlot(top + row)]
            for (x in 0 until tileWidth) destination[row * tileWidth + x] = displayPixel(source[left + x])
        }
    }

    fun readLinearTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, destination: LongArray) {
        validateTile(left, top, tileWidth, tileHeight, destination.size)
        for (row in 0 until tileHeight) {
            rows[rowSlot(top + row)].copyInto(destination, row * tileWidth, left, left + tileWidth)
        }
        if (image.pixelFormat == FileBackedPixelFormat.ARGB_8888) {
            for (index in 0 until tileWidth * tileHeight) destination[index] = LinearRgb16.fromArgb(destination[index].toInt())
        }
    }

    private fun validateTile(left: Int, top: Int, width: Int, height: Int, size: Int) {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(left + width <= this.width && top + height <= this.height)
        require(width.toLong() * height <= size)
    }

    override fun close() {
        if (!closed) {
            closed = true
            input.close()
        }
    }

    private fun rowSlot(y: Int): Int {
        check(!closed) { "File-backed image reader is closed" }
        val existing = rowIndices.indexOf(y)
        if (existing >= 0) return existing
        val slot = replacementIndex
        replacementIndex = (replacementIndex + 1) % rows.size
        input.seek(y.toLong() * image.rowStrideBytes)
        input.readFully(encodedRow)
        for (x in 0 until width) {
            val offset = x * image.pixelFormat.bytesPerPixel
            rows[slot][x] = if (image.pixelFormat == FileBackedPixelFormat.LINEAR_RGB_16) {
                var color = 0L
                for (byte in 0 until 6) color = (color shl 8) or (encodedRow[offset + byte].toLong() and 255L)
                color
            } else {
                val argb = ((encodedRow[offset].toInt() and 255) shl 24) or
                    ((encodedRow[offset + 1].toInt() and 255) shl 16) or
                    ((encodedRow[offset + 2].toInt() and 255) shl 8) or
                    (encodedRow[offset + 3].toInt() and 255)
                argb.toLong()
            }
        }
        rowIndices[slot] = y
        return slot
    }

    companion object { private const val DEFAULT_CACHED_ROWS = 4 }
}

class FileBackedFloatPlaneReader(
    val plane: FileBackedFloatPlane,
    cachedRows: Int = DEFAULT_CACHED_ROWS
) : AlphaPixelSource {
    init {
        require(cachedRows >= 1)
        plane.validate()
    }

    override val width: Int = plane.width
    override val height: Int = plane.height
    private val rowIndices = IntArray(cachedRows) { -1 }
    private val rows = Array(cachedRows) { FloatArray(width) }
    private val encodedRow = ByteArray(plane.rowStrideBytes)
    private val input = RandomAccessFile(plane.file, "r")
    private var replacementIndex = 0
    private var closed = false

    override fun alphaAt(x: Int, y: Int): Float {
        require(x in 0 until width && y in 0 until height)
        return rows[rowSlot(y)][x]
    }

    override fun readAlphaRow(y: Int, destination: FloatArray) {
        require(y in 0 until height && destination.size >= width)
        rows[rowSlot(y)].copyInto(destination, endIndex = width)
    }

    fun readTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, destination: FloatArray) {
        require(left >= 0 && top >= 0 && tileWidth > 0 && tileHeight > 0)
        require(left + tileWidth <= width && top + tileHeight <= height)
        require(tileWidth.toLong() * tileHeight <= destination.size)
        for (row in 0 until tileHeight) {
            rows[rowSlot(top + row)].copyInto(
                destination,
                destinationOffset = row * tileWidth,
                startIndex = left,
                endIndex = left + tileWidth
            )
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            input.close()
        }
    }

    private fun rowSlot(y: Int): Int {
        check(!closed) { "File-backed float reader is closed" }
        val existing = rowIndices.indexOf(y)
        if (existing >= 0) return existing
        val slot = replacementIndex
        replacementIndex = (replacementIndex + 1) % rows.size
        input.seek(y.toLong() * plane.rowStrideBytes)
        val encoded = encodedRow
        input.readFully(encoded)
        for (x in 0 until width) {
            val offset = x * Float.SIZE_BYTES
            val bits =
                ((encoded[offset].toInt() and 0xFF) shl 24) or
                ((encoded[offset + 1].toInt() and 0xFF) shl 16) or
                ((encoded[offset + 2].toInt() and 0xFF) shl 8) or
                (encoded[offset + 3].toInt() and 0xFF)
            rows[slot][x] = Float.fromBits(bits)
        }
        rowIndices[slot] = y
        return slot
    }

    companion object {
        private const val DEFAULT_CACHED_ROWS = 4
    }
}
