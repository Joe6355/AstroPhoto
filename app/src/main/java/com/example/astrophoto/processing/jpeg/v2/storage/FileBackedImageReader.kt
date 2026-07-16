package com.example.astrophoto.processing.jpeg.v2.storage

import com.example.astrophoto.processing.jpeg.v2.output.PngImageSource
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import java.io.RandomAccessFile

class FileBackedImageReader(
    val image: FileBackedImage,
    cachedRows: Int = DEFAULT_CACHED_ROWS
) : ArgbPixelSource, PngImageSource {
    override val width: Int = image.width
    override val height: Int = image.height
    private val input = RandomAccessFile(image.validate().file, "r")
    private val rowIndices = IntArray(cachedRows) { -1 }
    private val rows = Array(cachedRows) { IntArray(width) }
    private val encodedRows = Array(cachedRows) { ByteArray(image.rowStrideBytes) }
    private var replacementIndex = 0
    private var closed = false

    init {
        require(cachedRows >= 1)
    }

    override fun argbAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return rows[rowSlot(y)][x]
    }

    override fun readArgbRow(y: Int, destination: IntArray) {
        require(y in 0 until height && destination.size >= width)
        rows[rowSlot(y)].copyInto(destination, endIndex = width)
    }

    fun readTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, destination: IntArray) {
        require(left >= 0 && top >= 0 && tileWidth > 0 && tileHeight > 0)
        require(left + tileWidth <= width && top + tileHeight <= height)
        require(destination.size >= tileWidth * tileHeight)
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
        check(!closed) { "File-backed image reader is closed" }
        val existing = rowIndices.indexOf(y)
        if (existing >= 0) return existing
        val slot = replacementIndex
        replacementIndex = (replacementIndex + 1) % rows.size
        input.seek(y.toLong() * image.rowStrideBytes)
        val encoded = encodedRows[slot]
        input.readFully(encoded)
        for (x in 0 until width) {
            val offset = x * Int.SIZE_BYTES
            rows[slot][x] =
                ((encoded[offset].toInt() and 0xFF) shl 24) or
                ((encoded[offset + 1].toInt() and 0xFF) shl 16) or
                ((encoded[offset + 2].toInt() and 0xFF) shl 8) or
                (encoded[offset + 3].toInt() and 0xFF)
        }
        rowIndices[slot] = y
        return slot
    }

    companion object {
        private const val DEFAULT_CACHED_ROWS = 4
    }
}

class FileBackedFloatPlaneReader(
    val plane: FileBackedFloatPlane,
    cachedRows: Int = DEFAULT_CACHED_ROWS
) : AlphaPixelSource {
    override val width: Int = plane.width
    override val height: Int = plane.height
    private val input = RandomAccessFile(plane.validate().file, "r")
    private val rowIndices = IntArray(cachedRows) { -1 }
    private val rows = Array(cachedRows) { FloatArray(width) }
    private val encodedRows = Array(cachedRows) { ByteArray(plane.rowStrideBytes) }
    private var replacementIndex = 0
    private var closed = false

    init {
        require(cachedRows >= 1)
    }

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
        require(destination.size >= tileWidth * tileHeight)
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
        val encoded = encodedRows[slot]
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
