package com.joe6355.astrophoto

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

internal const val CAPTURE_STORAGE_FULL_MESSAGE =
    "Недостаточно свободного места для снимка. Освободите место и повторите съёмку."

internal fun writePendingCapturedJpeg(
    bytes: ByteArray,
    openOutput: () -> OutputStream?,
    publish: () -> Int,
    discard: () -> Unit
) = withCapturedJpegStorageErrors {
    withOwnedOutputCleanup(discard) {
        require(bytes.isNotEmpty()) { "Камера вернула пустой JPEG" }
        openOutput()?.use { it.write(bytes) } ?: error("Не удалось открыть файл для записи")
        check(publish() == 1) { "Галерея не подтвердила сохранение снимка" }
    }
}

internal fun writeLegacyCapturedJpeg(
    bytes: ByteArray,
    requested: File,
    openOutput: (File) -> OutputStream = { FileOutputStream(it) }
): File = withCapturedJpegStorageErrors {
    require(bytes.isNotEmpty()) { "Камера вернула пустой JPEG" }
    val parent = requested.absoluteFile.parentFile
        ?: error("Не указана папка сохранения снимка")
    val temporary = Files.createTempFile(parent.toPath(), ".astrophoto-jpeg-", ".pending").toFile()
    withOwnedOutputCleanup({ Files.deleteIfExists(temporary.toPath()) }) {
        openOutput(temporary).use { output ->
            output.write(bytes)
            output.flush()
            if (output is FileOutputStream) output.fd.sync()
        }
        check(temporary.length() == bytes.size.toLong()) { "JPEG записан не полностью" }
        moveCapturedJpegWithoutOverwrite(temporary, requested)
    }
}

private fun moveCapturedJpegWithoutOverwrite(temporary: File, requested: File): File {
    var suffix = 0
    while (true) {
        val target = if (suffix == 0) requested else {
            val extension = requested.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            File(requested.parentFile, "${requested.nameWithoutExtension}_$suffix$extension")
        }
        try {
            // No REPLACE_EXISTING: preserve a file that appeared after name selection.
            Files.move(temporary.toPath(), target.toPath())
            return target
        } catch (_: FileAlreadyExistsException) {
            suffix++
        }
    }
}

internal inline fun <T> withCapturedJpegStorageErrors(block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw storageWriteFailure(error, CAPTURE_STORAGE_FULL_MESSAGE)
}
