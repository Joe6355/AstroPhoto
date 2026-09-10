package com.joe6355.astrophoto.processing.jpeg.v2.storage

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import java.io.File
import java.io.RandomAccessFile

class FileBackedImageWriter(
    file: File,
    width: Int,
    height: Int,
    pixelFormat: FileBackedPixelFormat = FileBackedPixelFormat.ARGB_8888
) : AutoCloseable {
    val image = FileBackedImage(file, width, height, pixelFormat)
    private val output: RandomAccessFile
    private val encodedRow = ByteArray(image.rowStrideBytes)
    private var closed = false

    init {
        require(!file.exists()) { "Temporary candidate already exists: ${file.name}" }
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(image.expectedBytes)
        } catch (error: Throwable) {
            output.close()
            file.delete()
            throw error
        }
    }

    fun writeRow(y: Int, pixels: IntArray, sourceOffset: Int = 0) =
        writeSpan(0, y, image.width, pixels.size, sourceOffset) { index ->
            encodeArgb(pixels[index], (index - sourceOffset) * image.pixelFormat.bytesPerPixel)
        }

    fun writeLinearRow(y: Int, pixels: LongArray, sourceOffset: Int = 0) =
        writeSpan(0, y, image.width, pixels.size, sourceOffset) { index ->
            encodeLinear(pixels[index], (index - sourceOffset) * image.pixelFormat.bytesPerPixel)
        }

    fun writeTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, pixels: IntArray) {
        validateTile(left, top, tileWidth, tileHeight, pixels.size)
        for (row in 0 until tileHeight) {
            val start = row * tileWidth
            writeSpan(left, top + row, tileWidth, pixels.size, start) { index ->
                encodeArgb(pixels[index], (index - start) * image.pixelFormat.bytesPerPixel)
            }
        }
    }

    fun writeLinearTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, pixels: LongArray) {
        validateTile(left, top, tileWidth, tileHeight, pixels.size)
        for (row in 0 until tileHeight) {
            val start = row * tileWidth
            writeSpan(left, top + row, tileWidth, pixels.size, start) { index ->
                encodeLinear(pixels[index], (index - start) * image.pixelFormat.bytesPerPixel)
            }
        }
    }

    private fun validateTile(left: Int, top: Int, width: Int, height: Int, size: Int) {
        check(!closed)
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(left + width <= image.width && top + height <= image.height)
        require(width.toLong() * height <= size)
    }

    private inline fun writeSpan(x: Int, y: Int, count: Int, size: Int, start: Int, encode: (Int) -> Unit) {
        check(!closed)
        require(y in 0 until image.height && x >= 0 && x + count <= image.width)
        require(start >= 0 && start.toLong() + count <= size)
        for (index in start until start + count) encode(index)
        output.seek(y.toLong() * image.rowStrideBytes + x.toLong() * image.pixelFormat.bytesPerPixel)
        output.write(encodedRow, 0, count * image.pixelFormat.bytesPerPixel)
    }

    fun finish(): FileBackedImage {
        close()
        return image.validate()
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { output.fd.sync() } finally { output.close() }
        }
    }

    private fun encodeArgb(color: Int, offset: Int) {
        if (image.pixelFormat == FileBackedPixelFormat.LINEAR_RGB_16) {
            encodeLinear(LinearRgb16.fromArgb(color), offset)
        } else {
            encodedRow[offset] = (color ushr 24).toByte()
            encodedRow[offset + 1] = (color ushr 16).toByte()
            encodedRow[offset + 2] = (color ushr 8).toByte()
            encodedRow[offset + 3] = color.toByte()
        }
    }

    private fun encodeLinear(color: Long, offset: Int) {
        require(color ushr 48 == 0L) { "Linear RGB16 pixel exceeds 48 bits" }
        if (image.pixelFormat == FileBackedPixelFormat.ARGB_8888) {
            encodeArgb(LinearRgb16.toArgb(color), offset)
        } else {
            for (byte in 0 until 6) encodedRow[offset + byte] = (color ushr ((5 - byte) * 8)).toByte()
        }
    }
}

class FileBackedFloatPlaneWriter(
    file: File,
    width: Int,
    height: Int
) : AutoCloseable {
    val plane = FileBackedFloatPlane(file, width, height)
    private val output: RandomAccessFile
    private val encoded = ByteArray(plane.rowStrideBytes)
    private var closed = false

    init {
        require(!file.exists()) { "Temporary float plane already exists: ${file.name}" }
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(plane.expectedBytes)
        } catch (error: Throwable) {
            output.close()
            file.delete()
            throw error
        }
    }

    fun writeRow(y: Int, values: FloatArray, sourceOffset: Int = 0) {
        check(!closed)
        require(y in 0 until plane.height && sourceOffset >= 0 && sourceOffset + plane.width <= values.size)
        writeSpan(0, y, plane.width, values, sourceOffset)
    }

    fun writeTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, values: FloatArray) {
        check(!closed)
        require(left >= 0 && top >= 0 && tileWidth > 0 && tileHeight > 0)
        require(left + tileWidth <= plane.width && top + tileHeight <= plane.height)
        require(values.size >= tileWidth * tileHeight)
        for (row in 0 until tileHeight) writeSpan(left, top + row, tileWidth, values, row * tileWidth)
    }

    fun finish(): FileBackedFloatPlane {
        close()
        return plane.validate()
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { output.fd.sync() } finally { output.close() }
        }
    }

    private fun writeSpan(x: Int, y: Int, count: Int, values: FloatArray, sourceOffset: Int) {
        for (index in 0 until count) {
            require(values[sourceOffset + index].isFinite()) { "Non-finite float plane value" }
            val bits = values[sourceOffset + index].coerceIn(0f, 1f).toBits()
            val offset = index * Float.SIZE_BYTES
            encoded[offset] = (bits ushr 24).toByte()
            encoded[offset + 1] = (bits ushr 16).toByte()
            encoded[offset + 2] = (bits ushr 8).toByte()
            encoded[offset + 3] = bits.toByte()
        }
        output.seek(y.toLong() * plane.rowStrideBytes + x.toLong() * Float.SIZE_BYTES)
        output.write(encoded, 0, count * Float.SIZE_BYTES)
    }
}
