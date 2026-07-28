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
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        requireValidPngDimensions(width, height)
        require(pixels.size.toLong() == width.toLong() * height) {
            "ARGB source size does not match its dimensions"
        }
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
        requireValidPngDimensions(source.width, source.height)
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

internal data class ValidatedPng(
    val width: Int,
    val height: Int,
    val chunkCount: Int
)

internal data class MediaStorePngPublicationResult(
    val encodedBytes: Long,
    val publishedSizeBytes: Long?
)

internal object MediaStorePngPublicationCoordinator {
    fun publish(
        encodePending: () -> Long,
        validatePending: () -> Unit,
        publishPending: () -> Unit,
        queryPublishedSize: () -> Long?
    ): MediaStorePngPublicationResult {
        val encodedBytes = encodePending()
        require(encodedBytes > 0L) { "PNG output is empty" }
        validatePending()
        publishPending()
        val publishedSize = runCatching(queryPublishedSize).getOrNull()
        return MediaStorePngPublicationResult(encodedBytes, publishedSize)
    }
}

internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(value: Int) {
        delegate.write(value)
        bytesWritten++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        delegate.write(buffer, offset, length)
        bytesWritten += length
    }

    override fun flush() = delegate.flush()
}

internal fun requireValidPngDimensions(width: Int, height: Int) {
    require(width in 1..MAX_SAFE_PNG_DIMENSION) { "PNG width is outside the safe range" }
    require(height in 1..MAX_SAFE_PNG_DIMENSION) { "PNG height is outside the safe range" }
    require(width.toLong() * height <= MAX_SAFE_PNG_PIXELS) {
        "PNG pixel count is outside the safe range"
    }
}

internal object PngStructureValidator {
    fun validate(
        file: File,
        expectedWidth: Int,
        expectedHeight: Int
    ): ValidatedPng = FileInputStream(file).use { input ->
        validate(input, expectedWidth, expectedHeight)
    }

    fun validate(
        source: InputStream,
        expectedWidth: Int,
        expectedHeight: Int
    ): ValidatedPng {
        requireValidPngDimensions(expectedWidth, expectedHeight)
        val input = DataInputStream(BufferedInputStream(source))
        val signature = ByteArray(PNG_SIGNATURE_BYTES.size)
        input.readFully(signature)
        require(signature.contentEquals(PNG_SIGNATURE_BYTES)) { "Invalid PNG signature" }
        val transfer = ByteArray(PNG_VALIDATION_BUFFER_SIZE)
        val inflated = ByteArray(PNG_VALIDATION_BUFFER_SIZE)
        val inflater = Inflater()
        var width = -1
        var height = -1
        var sawHeader = false
        var sawImageData = false
        var imageDataEnded = false
        var sawEnd = false
        var chunkCount = 0
        var inflatedByteCount = 0L
        try {
            while (!sawEnd) {
                require(chunkCount < MAX_PNG_CHUNKS) { "PNG has too many chunks" }
                val length = input.readInt().toLong() and 0xFFFF_FFFFL
                require(length <= MAX_PNG_CHUNK_BYTES) { "PNG chunk is too large" }
                val typeBytes = ByteArray(4)
                input.readFully(typeBytes)
                val type = typeBytes.toString(Charsets.US_ASCII)
                val crc = CRC32().apply { update(typeBytes) }
                if (type == "IHDR") {
                    require(!sawHeader && chunkCount == 0 && length == 13L) {
                        "Invalid PNG header chunk"
                    }
                    val header = ByteArray(13)
                    input.readFully(header)
                    crc.update(header)
                    DataInputStream(ByteArrayInputStream(header)).use { data ->
                        width = data.readInt()
                        height = data.readInt()
                        require(data.readUnsignedByte() == 8) { "Unsupported PNG bit depth" }
                        require(data.readUnsignedByte() == 6) { "Unsupported PNG colour type" }
                        require(data.readUnsignedByte() == 0) { "Unsupported PNG compression" }
                        require(data.readUnsignedByte() == 0) { "Unsupported PNG filter method" }
                        require(data.readUnsignedByte() == 0) { "Unsupported PNG interlace mode" }
                    }
                    requireValidPngDimensions(width, height)
                    require(width == expectedWidth && height == expectedHeight) {
                        "PNG dimensions do not match the encoded source"
                    }
                    sawHeader = true
                } else {
                    require(sawHeader) { "PNG data appeared before its header" }
                    if (sawImageData && type != "IDAT") imageDataEnded = true
                    require(type != "IDAT" || !imageDataEnded) {
                        "PNG image-data chunks are not consecutive"
                    }
                    var remaining = length
                    while (remaining > 0L) {
                        val count = minOf(remaining, transfer.size.toLong()).toInt()
                        input.readFully(transfer, 0, count)
                        crc.update(transfer, 0, count)
                        if (type == "IDAT") {
                            inflater.setInput(transfer, 0, count)
                            while (!inflater.needsInput()) {
                                val inflatedCount = inflater.inflate(inflated)
                                if (inflatedCount <= 0) {
                                    require(!inflater.needsDictionary()) {
                                        "PNG compressed image data requires a dictionary"
                                    }
                                    require(inflater.finished() || inflater.needsInput()) {
                                        "PNG compressed image data made no progress"
                                    }
                                    break
                                }
                                validateInflatedBytes(
                                    bytes = inflated,
                                    count = inflatedCount,
                                    previousByteCount = inflatedByteCount,
                                    rowBytes = width.toLong() * 4L + 1L,
                                    maximumBytes = (width.toLong() * 4L + 1L) * height
                                )
                                inflatedByteCount += inflatedCount
                            }
                            require(!inflater.finished() || inflater.remaining == 0) {
                                "PNG has trailing compressed image data"
                            }
                        }
                        remaining -= count
                    }
                    when (type) {
                        "IDAT" -> sawImageData = true
                        "IEND" -> {
                            val expectedInflatedBytes =
                                (width.toLong() * 4L + 1L) * height
                            require(
                                length == 0L &&
                                    sawImageData &&
                                    inflater.finished() &&
                                    inflatedByteCount == expectedInflatedBytes
                            ) { "Invalid or incomplete PNG image data" }
                            sawEnd = true
                        }
                    }
                }
                val expectedCrc = input.readInt().toLong() and 0xFFFF_FFFFL
                require(crc.value == expectedCrc) { "PNG chunk CRC mismatch" }
                chunkCount++
            }
            require(input.read() == -1) { "PNG contains trailing data" }
            return ValidatedPng(width, height, chunkCount)
        } finally {
            inflater.end()
        }
    }

    private fun validateInflatedBytes(
        bytes: ByteArray,
        count: Int,
        previousByteCount: Long,
        rowBytes: Long,
        maximumBytes: Long
    ) {
        require(previousByteCount + count <= maximumBytes) {
            "PNG expands beyond its declared dimensions"
        }
        for (index in 0 until count) {
            val absoluteIndex = previousByteCount + index
            if (absoluteIndex % rowBytes == 0L) {
                require((bytes[index].toInt() and 0xFF) in 0..4) {
                    "PNG contains an invalid row filter"
                }
            }
        }
    }
}

internal object CrashSafePngFilePublisher {
    fun publish(
        source: PngImageSource,
        target: File,
        temporary: File,
        onRow: (Int) -> Unit = {}
    ): Long {
        requireValidPngDimensions(source.width, source.height)
        val parent = target.parentFile?.canonicalFile
            ?: error("PNG destination has no parent directory")
        require(parent == temporary.parentFile?.canonicalFile) {
            "PNG temporary and final files must share a destination directory"
        }
        require(!target.exists()) { "PNG destination already exists" }
        require(!temporary.exists()) { "PNG temporary file already exists" }
        var temporaryCreated = false
        try {
            require(temporary.createNewFile()) { "Unable to create temporary PNG" }
            temporaryCreated = true
            FileOutputStream(temporary, false).use { output ->
                PngStreamEncoder.encode(source, output, onRow)
                output.fd.sync()
            }
            require(temporary.length() > 0L) { "PNG output is empty" }
            PngStructureValidator.validate(
                temporary,
                expectedWidth = source.width,
                expectedHeight = source.height
            )
            val encodedBytes = temporary.length()
            moveWithoutOverwrite(temporary, target)
            return encodedBytes
        } catch (error: Throwable) {
            if (temporaryCreated) {
                try {
                    Files.deleteIfExists(temporary.toPath())
                } catch (cleanupError: IOException) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }

    private fun moveWithoutOverwrite(temporary: File, target: File) {
        // Both paths are in the same directory. Omitting REPLACE_EXISTING is
        // intentional: publication must fail rather than overwrite a result
        // that appeared after filename selection.
        Files.move(temporary.toPath(), target.toPath())
    }
}

internal class LosslessProcessedImageWriter(private val context: Context) {
    suspend fun write(
        session: SessionSummary,
        bitmap: Bitmap,
        requestedFileName: String
    ): SavedProcessedImage = write(session, BitmapPngSource(bitmap), requestedFileName)

    suspend fun write(
        session: SessionSummary,
        source: PngImageSource,
        requestedFileName: String
    ): SavedProcessedImage = PNG_PUBLICATION_MUTEX.withLock {
        writeLocked(session, source, requestedFileName)
    }

    private suspend fun writeLocked(
        session: SessionSummary,
        source: PngImageSource,
        requestedFileName: String
    ): SavedProcessedImage {
        require(requestedFileName.endsWith(".png", ignoreCase = true))
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val destination = processedImageDestination(session.folderName, "image/png")
            val finalFileName = findUniqueProcessedResultName(requestedFileName) { candidate ->
                mediaStoreNameExists(destination.relativePath, candidate)
            }
            val pendingFileName = buildPendingPngName(finalFileName)
            val uri = resolver.insert(
                processedImagesCollection(destination.collection),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, pendingFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, destination.mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, destination.relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            ) ?: error("Не удалось создать PNG результат")
            var published = false
            try {
                val publication = MediaStorePngPublicationCoordinator.publish(
                    encodePending = {
                        resolver.openOutputStream(uri, "w")?.use { output ->
                            val counting = CountingOutputStream(output)
                            PngStreamEncoder.encode(source, counting) {
                                coroutineContext.ensureActive()
                            }
                            counting.bytesWritten
                        } ?: error("Не удалось записать PNG результат")
                    },
                    validatePending = {
                        resolver.openInputStream(uri)?.use { input ->
                            PngStructureValidator.validate(
                                input,
                                expectedWidth = source.width,
                                expectedHeight = source.height
                            )
                        } ?: error("Unable to validate encoded PNG")
                    },
                    publishPending = {
                        val updated = resolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            },
                            null,
                            null
                        )
                        require(updated == 1) {
                            "PNG MediaStore publication did not update exactly one row"
                        }
                        published = true
                    },
                    queryPublishedSize = {
                        resolver.query(
                            uri,
                            arrayOf(MediaStore.Images.Media.SIZE),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else null
                        }
                    }
                )
                val saved = retainedMediaStoreImage(destination, finalFileName, uri.toString())
                if (publication.publishedSizeBytes == null || publication.publishedSizeBytes <= 0L) {
                    Log.w(
                        OUTPUT_TAG,
                        "Published MediaStore SIZE is unavailable; " +
                            "using validated encodedBytes=${publication.encodedBytes}"
                    )
                }
                Log.i(
                    OUTPUT_TAG,
                    "pngOutput=${saved.displayPath} pngFileSize=${publication.encodedBytes} " +
                        "publishedMediaStoreSize=${publication.publishedSizeBytes ?: -1L}"
                )
                return saved
            } catch (error: Throwable) {
                if (!published) {
                    runCatching { resolver.delete(uri, null, null) }
                        .onFailure { cleanup ->
                            Log.w(
                                OUTPUT_TAG,
                                "Unable to remove pending PNG row after failure",
                                cleanup
                            )
                        }
                }
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
        val encodedBytes = CrashSafePngFilePublisher.publish(
            source = source,
            target = target,
            temporary = temporary
        ) {
            coroutineContext.ensureActive()
        }
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("image/png"),
                null
            )
        }
        Log.i(OUTPUT_TAG, "pngOutput=${target.absolutePath} pngFileSize=$encodedBytes")
        return SavedProcessedImage(
            fileName = finalFileName,
            displayPath = target.absolutePath,
            contentUri = null,
            filePath = target.absolutePath
        )
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

    private fun buildPendingPngName(finalFileName: String): String =
        ".${finalFileName.substringBeforeLast('.')}.pending.png"

    private class BitmapPngSource(private val bitmap: Bitmap) : PngImageSource {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        override fun readArgbRow(y: Int, destination: IntArray) {
            bitmap.getPixels(destination, 0, width, 0, y, width, 1)
        }
    }

    companion object {
        private const val OUTPUT_TAG = "AstroPhotoJpegV2Output"
        private val PNG_PUBLICATION_MUTEX = Mutex()
    }
}

private val PNG_SIGNATURE_BYTES = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
)
private const val MAX_SAFE_PNG_DIMENSION = 32_768
private const val MAX_SAFE_PNG_PIXELS = 300_000_000L
private const val MAX_PNG_CHUNK_BYTES = 128L * 1024L * 1024L
private const val MAX_PNG_CHUNKS = 1_000_000
private const val PNG_VALIDATION_BUFFER_SIZE = 32 * 1024
