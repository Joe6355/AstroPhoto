package com.example.astrophoto.processing.jpeg.v2.output

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.astrophoto.ProcessedMediaCollection
import com.example.astrophoto.SavedProcessedImage
import com.example.astrophoto.SessionSummary
import com.example.astrophoto.findUniqueProcessedResultName
import com.example.astrophoto.processedImageDestination
import com.example.astrophoto.processedImagesCollection
import com.example.astrophoto.retainedMediaStoreImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface PngImageSource {
    val width: Int
    val height: Int
    fun readArgbRow(y: Int, destination: IntArray)
}

class ArgbArrayPngSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray
) : PngImageSource {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }

    override fun readArgbRow(y: Int, destination: IntArray) {
        require(y in 0 until height && destination.size >= width)
        pixels.copyInto(destination, startIndex = y * width, endIndex = (y + 1) * width)
    }
}

object PngStreamEncoder {
    fun encode(
        source: PngImageSource,
        output: OutputStream,
        onRow: (Int) -> Unit = {}
    ) {
        require(source.width > 0 && source.height > 0)
        output.write(PNG_SIGNATURE)
        val header = ByteArrayOutputStream(13).also { buffer ->
            DataOutputStream(buffer).use { data ->
                data.writeInt(source.width)
                data.writeInt(source.height)
                data.writeByte(8)
                data.writeByte(6)
                data.writeByte(0)
                data.writeByte(0)
                data.writeByte(0)
            }
        }.toByteArray()
        writeChunk(output, "IHDR", header)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val argbRow = IntArray(source.width)
        val rawRow = ByteArray(1 + source.width * 4)
        val compressed = ByteArray(COMPRESSION_BUFFER_SIZE)
        try {
            for (y in 0 until source.height) {
                source.readArgbRow(y, argbRow)
                rawRow[0] = 0
                argbRow.indices.forEach { x ->
                    val color = argbRow[x]
                    val offset = 1 + x * 4
                    rawRow[offset] = (color ushr 16 and 0xFF).toByte()
                    rawRow[offset + 1] = (color ushr 8 and 0xFF).toByte()
                    rawRow[offset + 2] = (color and 0xFF).toByte()
                    rawRow[offset + 3] = (color ushr 24 and 0xFF).toByte()
                }
                deflater.setInput(rawRow)
                while (!deflater.needsInput()) {
                    val count = deflater.deflate(compressed)
                    if (count <= 0) break
                    writeChunk(output, "IDAT", compressed.copyOf(count))
                }
                onRow(y + 1)
            }
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(compressed)
                require(count > 0) { "PNG compression did not finish" }
                writeChunk(output, "IDAT", compressed.copyOf(count))
            }
        } finally {
            deflater.end()
        }
        writeChunk(output, "IEND", ByteArray(0))
        output.flush()
    }

    private fun writeChunk(output: OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4)
        val writer = DataOutputStream(output)
        writer.writeInt(data.size)
        writer.write(typeBytes)
        writer.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        writer.writeInt(crc.value.toInt())
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private const val COMPRESSION_BUFFER_SIZE = 32 * 1024
}

internal class LosslessProcessedImageWriter(private val context: Context) {
    suspend fun write(
        session: SessionSummary,
        bitmap: Bitmap,
        requestedFileName: String
    ): SavedProcessedImage {
        require(requestedFileName.endsWith(".png", ignoreCase = true))
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val source = BitmapPngSource(bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val destination = processedImageDestination(session.folderName, "image/png")
            val finalFileName = findUniqueProcessedResultName(requestedFileName) { candidate ->
                mediaStoreNameExists(destination.relativePath, candidate)
            }
            val uri = resolver.insert(
                processedImagesCollection(destination.collection),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, destination.mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, destination.relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            ) ?: error("Не удалось создать PNG результат")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    PngStreamEncoder.encode(source, output) { coroutineContext.ensureActive() }
                } ?: error("Не удалось записать PNG результат")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                val size = resolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
                } ?: 0L
                require(size > 0L) { "PNG результат пуст" }
                val saved = retainedMediaStoreImage(destination, finalFileName, uri.toString())
                Log.i(OUTPUT_TAG, "pngOutput=${saved.displayPath} pngFileSize=$size")
                return saved
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "AstroPhoto/${session.folderName}/Processed")
        require(directory.exists() || directory.mkdirs()) { "Не удалось создать папку Processed" }
        val finalFileName = findUniqueProcessedResultName(requestedFileName) { candidate ->
            File(directory, candidate).exists() || File(directory, "$candidate.tmp").exists()
        }
        val target = File(directory, finalFileName)
        val temporary = File(directory, "$finalFileName.tmp")
        var moved = false
        try {
            require(temporary.createNewFile()) { "Не удалось создать временный PNG" }
            FileOutputStream(temporary, false).use { output ->
                PngStreamEncoder.encode(source, output) { coroutineContext.ensureActive() }
            }
            require(temporary.length() > 0L) { "PNG результат пуст" }
            Files.move(temporary.toPath(), target.toPath())
            moved = true
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("image/png"),
                null
            )
            Log.i(OUTPUT_TAG, "pngOutput=${target.absolutePath} pngFileSize=${target.length()}")
            return SavedProcessedImage(
                fileName = finalFileName,
                displayPath = target.absolutePath,
                contentUri = null,
                filePath = target.absolutePath
            )
        } catch (error: Throwable) {
            temporary.delete()
            if (moved) target.delete()
            throw error
        }
    }

    private fun mediaStoreNameExists(relativePath: String, fileName: String): Boolean =
        context.contentResolver.query(
            processedImagesCollection(ProcessedMediaCollection.IMAGES),
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH}=? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME}=?",
            arrayOf(relativePath, fileName),
            null
        )?.use { it.moveToFirst() } == true

    private class BitmapPngSource(private val bitmap: Bitmap) : PngImageSource {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        override fun readArgbRow(y: Int, destination: IntArray) {
            bitmap.getPixels(destination, 0, width, 0, y, width, 1)
        }
    }

    companion object {
        private const val OUTPUT_TAG = "AstroPhotoJpegV2Output"
    }
}
