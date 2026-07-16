package com.example.astrophoto.processing.jpeg.v2.storage

import java.io.File

enum class FileBackedPixelFormat(val bytesPerPixel: Int) {
    ARGB_8888(Int.SIZE_BYTES)
}

data class FileBackedImage(
    val file: File,
    val width: Int,
    val height: Int,
    val pixelFormat: FileBackedPixelFormat = FileBackedPixelFormat.ARGB_8888,
    val rowStrideBytes: Int = width * pixelFormat.bytesPerPixel
) {
    init {
        require(width > 0 && height > 0)
        require(rowStrideBytes >= width * pixelFormat.bytesPerPixel)
    }

    val expectedBytes: Long get() = rowStrideBytes.toLong() * height

    fun validate(): FileBackedImage {
        require(file.isFile && file.length() == expectedBytes) {
            "Incomplete file-backed image ${file.name}"
        }
        return this
    }
}

data class FileBackedFloatPlane(
    val file: File,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int = width * Float.SIZE_BYTES
) {
    init {
        require(width > 0 && height > 0 && rowStrideBytes >= width * Float.SIZE_BYTES)
    }

    val expectedBytes: Long get() = rowStrideBytes.toLong() * height

    fun validate(): FileBackedFloatPlane {
        require(file.isFile && file.length() == expectedBytes) {
            "Incomplete file-backed float plane ${file.name}"
        }
        return this
    }
}

interface AlphaPixelSource : AutoCloseable {
    val width: Int
    val height: Int
    fun alphaAt(x: Int, y: Int): Float
    fun readAlphaRow(y: Int, destination: FloatArray) {
        require(y in 0 until height && destination.size >= width)
        for (x in 0 until width) destination[x] = alphaAt(x, y)
    }
    override fun close() = Unit
}
