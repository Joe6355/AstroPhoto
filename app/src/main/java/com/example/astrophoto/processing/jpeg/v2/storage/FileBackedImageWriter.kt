package com.example.astrophoto.processing.jpeg.v2.storage

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

    fun writeRow(y: Int, pixels: IntArray, sourceOffset: Int = 0) {
        check(!closed)
        require(y in 0 until image.height && sourceOffset >= 0 && sourceOffset + image.width <= pixels.size)
        for (x in 0 until image.width) encode(pixels[sourceOffset + x], x * Int.SIZE_BYTES)
        output.seek(y.toLong() * image.rowStrideBytes)
        output.write(encodedRow)
    }

    fun writeTile(left: Int, top: Int, tileWidth: Int, tileHeight: Int, pixels: IntArray) {
        check(!closed)
        require(left >= 0 && top >= 0 && tileWidth > 0 && tileHeight > 0)
        require(left + tileWidth <= image.width && top + tileHeight <= image.height)
        require(pixels.size >= tileWidth * tileHeight)
        val encodedTileRow = ByteArray(tileWidth * Int.SIZE_BYTES)
        for (row in 0 until tileHeight) {
            for (x in 0 until tileWidth) {
                val color = pixels[row * tileWidth + x]
                val offset = x * Int.SIZE_BYTES
                encodedTileRow[offset] = (color ushr 24).toByte()
                encodedTileRow[offset + 1] = (color ushr 16).toByte()
                encodedTileRow[offset + 2] = (color ushr 8).toByte()
                encodedTileRow[offset + 3] = color.toByte()
            }
            output.seek((top + row).toLong() * image.rowStrideBytes + left.toLong() * Int.SIZE_BYTES)
            output.write(encodedTileRow)
        }
    }

    fun finish(): FileBackedImage {
        close()
        return image.validate()
    }

    override fun close() {
        if (!closed) {
            closed = true
            output.fd.sync()
            output.close()
        }
    }

    private fun encode(color: Int, offset: Int) {
        encodedRow[offset] = (color ushr 24).toByte()
        encodedRow[offset + 1] = (color ushr 16).toByte()
        encodedRow[offset + 2] = (color ushr 8).toByte()
        encodedRow[offset + 3] = color.toByte()
    }
}

class FileBackedFloatPlaneWriter(
    file: File,
    width: Int,
    height: Int
) : AutoCloseable {
    val plane = FileBackedFloatPlane(file, width, height)
    private val output: RandomAccessFile
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
            output.fd.sync()
            output.close()
        }
    }

    private fun writeSpan(x: Int, y: Int, count: Int, values: FloatArray, sourceOffset: Int) {
        val encoded = ByteArray(count * Float.SIZE_BYTES)
        for (index in 0 until count) {
            val bits = values[sourceOffset + index].coerceIn(0f, 1f).toBits()
            val offset = index * Float.SIZE_BYTES
            encoded[offset] = (bits ushr 24).toByte()
            encoded[offset + 1] = (bits ushr 16).toByte()
            encoded[offset + 2] = (bits ushr 8).toByte()
            encoded[offset + 3] = bits.toByte()
        }
        output.seek(y.toLong() * plane.rowStrideBytes + x.toLong() * Float.SIZE_BYTES)
        output.write(encoded)
    }
}
