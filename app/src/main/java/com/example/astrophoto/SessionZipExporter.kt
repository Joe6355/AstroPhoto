package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionZipExportResult(
    val fileName: String,
    val location: String,
    val contentUri: String?,
    val filePath: String?,
    val sizeBytes: Long,
    val addedFiles: Int,
    val skippedFiles: Int,
    val sessionInfoUpdated: Boolean = false
)

data class SessionZipProgress(
    val message: String,
    val current: Int,
    val total: Int
)

class SessionZipExporter(private val context: Context) {
    suspend fun export(
        session: SessionSummary,
        onProgress: suspend (SessionZipProgress) -> Unit = {}
    ): Result<SessionZipExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeSessionName = sanitizeName(session.sessionName)
                .ifBlank { sanitizeName(session.folderName) }
                .ifBlank { "Session" }
            val timestamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date())
            val requestedName = "AstroPhoto_${safeSessionName}_$timestamp.zip"
            val sources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collectMediaStoreSources(session)
            } else {
                collectFileSources(session)
            }
            val readme = buildReadme(session, sources)
            reportProgress(
                onProgress,
                "Подготовка README",
                0,
                sources.size + 1
            )

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportWithMediaStore(
                    session = session,
                    rootName = safeSessionName,
                    requestedFileName = requestedName,
                    sources = sources,
                    readme = readme,
                    onProgress = onProgress
                )
            } else {
                exportWithFiles(
                    session = session,
                    rootName = safeSessionName,
                    requestedFileName = requestedName,
                    sources = sources,
                    readme = readme,
                    onProgress = onProgress
                )
            }

            reportProgress(
                onProgress,
                "Экспорт завершён",
                sources.size + 1,
                sources.size + 1
            )
            val infoUpdated = runCatching {
                appendExportSessionInfo(session, result)
            }.isSuccess
            result.copy(sessionInfoUpdated = infoUpdated)
        }
    }

    private suspend fun exportWithMediaStore(
        session: SessionSummary,
        rootName: String,
        requestedFileName: String,
        sources: List<ZipSource>,
        readme: String,
        onProgress: suspend (SessionZipProgress) -> Unit
    ): SessionZipExportResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val exportPath = "${Environment.DIRECTORY_DOCUMENTS}/AstroPhoto/Exports/"
        val fileName = uniqueMediaStoreName(collection, exportPath, requestedFileName)
        val zipUri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/zip")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, exportPath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        ) ?: error("Не удалось создать ZIP в MediaStore")

        val writeResult = try {
            resolver.openOutputStream(zipUri, "w")?.buffered(BUFFER_SIZE)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    writeEntries(zip, rootName, sources, readme, onProgress)
                }
            } ?: error("Не удалось открыть ZIP для записи")
        } catch (exception: Exception) {
            resolver.delete(zipUri, null, null)
            throw exception
        }

        resolver.update(
            zipUri,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            },
            null,
            null
        )
        val size = resolver.query(
            zipUri,
            arrayOf(MediaStore.Files.FileColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
        } ?: 0L

        return SessionZipExportResult(
            fileName = fileName,
            location = "${exportPath.trimEnd('/')}/$fileName",
            contentUri = zipUri.toString(),
            filePath = null,
            sizeBytes = size,
            addedFiles = writeResult.added,
            skippedFiles = writeResult.skipped
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun exportWithFiles(
        session: SessionSummary,
        rootName: String,
        requestedFileName: String,
        sources: List<ZipSource>,
        readme: String,
        onProgress: suspend (SessionZipProgress) -> Unit
    ): SessionZipExportResult {
        val documents = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        val exportDirectory = File(documents, "AstroPhoto/Exports")
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            error("Не удалось создать папку экспорта")
        }
        val zipFile = uniqueFile(exportDirectory, requestedFileName)
        val writeResult = try {
            ZipOutputStream(
                FileOutputStream(zipFile).buffered(BUFFER_SIZE)
            ).use { zip ->
                writeEntries(zip, rootName, sources, readme, onProgress)
            }
        } catch (exception: Exception) {
            zipFile.delete()
            throw exception
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(zipFile.absolutePath),
            arrayOf("application/zip"),
            null
        )
        return SessionZipExportResult(
            fileName = zipFile.name,
            location = zipFile.absolutePath,
            contentUri = null,
            filePath = zipFile.absolutePath,
            sizeBytes = zipFile.length(),
            addedFiles = writeResult.added,
            skippedFiles = writeResult.skipped
        )
    }

    private suspend fun writeEntries(
        zip: ZipOutputStream,
        rootName: String,
        sources: List<ZipSource>,
        readme: String,
        onProgress: suspend (SessionZipProgress) -> Unit
    ): WriteResult {
        val writtenEntries = mutableSetOf<String>()
        var added = 0
        var skipped = 0
        val total = sources.size + 1

        zip.putNextEntry(ZipEntry("$rootName/README.txt"))
        zip.write(readme.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        added++
        ZIP_DIRECTORIES.forEach { directory ->
            zip.putNextEntry(ZipEntry("$rootName/$directory"))
            zip.closeEntry()
        }

        sources.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            reportProgress(
                onProgress,
                "Добавление файла ${index + 1} из ${sources.size}",
                index + 1,
                total
            )
            val entryName = "$rootName/${sanitizeEntryPath(source.relativePath)}"
            if (!writtenEntries.add(entryName)) {
                skipped++
                return@forEachIndexed
            }
            val input = try {
                source.open()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (input == null) {
                skipped++
                return@forEachIndexed
            }

            var entryOpened = false
            try {
                input.buffered(BUFFER_SIZE).use {
                    zip.putNextEntry(ZipEntry(entryName))
                    entryOpened = true
                    it.copyTo(zip, BUFFER_SIZE)
                }
                zip.closeEntry()
                entryOpened = false
                added++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                skipped++
                if (entryOpened) {
                    runCatching { zip.closeEntry() }
                        .getOrElse { throw it }
                }
            }
        }
        return WriteResult(added = added, skipped = skipped)
    }

    private fun collectMediaStoreSources(session: SessionSummary): List<ZipSource> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val basePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val sources = mutableListOf<ZipSource>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.SIZE
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$basePath%"),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} ASC, " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val pathIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            val sizeIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.SIZE
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                if (name.isBlank()) continue
                val relativeDirectory = cursor.getString(pathIndex).orEmpty()
                    .removePrefix(basePath)
                val relativePath = sanitizeEntryPath("$relativeDirectory$name")
                if (relativePath.equals("README.txt", ignoreCase = true)) continue
                val uri = ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(idIndex)
                )
                sources += ZipSource(
                    relativePath = relativePath,
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    open = { resolver.openInputStream(uri) }
                )
            }
        }
        if (sources.none {
                it.relativePath.equals("session_info.txt", ignoreCase = true)
            }
        ) {
            val bytes = fallbackSessionInfo(session).toByteArray(Charsets.UTF_8)
            sources += ZipSource(
                relativePath = "session_info.txt",
                sizeBytes = bytes.size.toLong(),
                open = { bytes.inputStream() }
            )
        }
        return sources
    }

    @Suppress("DEPRECATION")
    private fun collectFileSources(session: SessionSummary): List<ZipSource> {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(pictures, "AstroPhoto/${session.folderName}")
        val sources = if (directory.exists()) {
            directory.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    ZipSource(
                        relativePath = sanitizeEntryPath(
                            file.relativeTo(directory).path
                        ),
                        sizeBytes = file.length(),
                        open = { file.inputStream() }
                    )
                }
                .toMutableList()
        } else {
            mutableListOf()
        }
        if (sources.none {
                it.relativePath.equals("session_info.txt", ignoreCase = true)
            }
        ) {
            val bytes = fallbackSessionInfo(session).toByteArray(Charsets.UTF_8)
            sources += ZipSource(
                relativePath = "session_info.txt",
                sizeBytes = bytes.size.toLong(),
                open = { bytes.inputStream() }
            )
        }
        return sources
    }

    private fun buildReadme(
        session: SessionSummary,
        sources: List<ZipSource>
    ): String {
        fun count(prefix: String, extensions: Set<String>? = null): Int =
            sources.count { source ->
                val normalized = source.relativePath.replace('\\', '/')
                normalized.startsWith(prefix, ignoreCase = true) &&
                    (
                        extensions == null ||
                            extensions.any {
                                normalized.endsWith(it, ignoreCase = true)
                            }
                        )
            }

        val info = session.infoContent
        val exportDate = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
        return buildString {
            appendLine("AstroPhoto")
            appendLine("==========")
            appendLine("Сессия: ${session.sessionName.ifBlank { "неизвестно" }}")
            appendLine("Дата экспорта: $exportDate")
            appendLine(
                "Модель устройства: ${
                    "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                        .ifBlank { "неизвестно" }
                }"
            )
            appendLine("cameraId: ${infoValue(info, "cameraId")}")
            appendLine()
            appendLine("Активные настройки")
            appendLine("ISO: ${infoValue(info, "ISO")}")
            appendLine("Выдержка: ${formatExposureInfo(infoValue(info, "exposureTimeNs"))}")
            appendLine("Фокус: ${infoValue(info, "focus")}")
            appendLine("Формат: ${infoValue(info, "selectedFormat")}")
            appendLine()
            appendLine("Количество файлов")
            appendLine("Lights JPEG: ${count("Lights/JPEG/", setOf(".jpg", ".jpeg"))}")
            appendLine("Lights RAW/DNG: ${count("Lights/RAW/", setOf(".dng"))}")
            appendLine("Darks JPEG: ${count("Darks/JPEG/", setOf(".jpg", ".jpeg"))}")
            appendLine("Darks RAW/DNG: ${count("Darks/RAW/", setOf(".dng"))}")
            appendLine("Tests: ${count("Tests/")}")
            appendLine("Processed: ${count("Processed/")}")
            appendLine()
            appendLine("Структура")
            appendLine("Lights — основные кадры.")
            appendLine("Darks — тёмные кадры для вычитания шума.")
            appendLine("Processed — результаты stacking и редактирования.")
            appendLine("Tests — пробные кадры.")
            appendLine()
            appendLine(
                "RAW/DNG можно обрабатывать на ПК в Siril, DeepSkyStacker, " +
                    "RawTherapee, darktable или другом ПО."
            )
        }
    }

    private fun appendExportSessionInfo(
        session: SessionSummary,
        result: SessionZipExportResult
    ) {
        val exportedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
        val block = buildString {
            appendLine()
            appendLine("exportedAt: $exportedAt")
            appendLine("exportZip: ${result.fileName}")
            appendLine("exportZipSizeBytes: ${result.sizeBytes}")
            appendLine("exportAddedFiles: ${result.addedFiles}")
            appendLine("exportSkippedFiles: ${result.skippedFiles}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreInfo(session, block)
        } else {
            appendFileInfo(session, block)
        }
    }

    private fun appendMediaStoreInfo(session: SessionSummary, block: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val path =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val uri = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf("session_info.txt", path),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
        val existing = uri?.let {
            resolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                reader.readText()
            }
        }.orEmpty()
        var inserted = false
        val target = uri ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "session_info.txt")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, path)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        )?.also { inserted = true } ?: error("Не удалось обновить session_info.txt")
        try {
            resolver.openOutputStream(target, "wt")?.bufferedWriter()?.use { writer ->
                val base = existing.ifBlank {
                    "sessionName: ${session.sessionName}\n"
                }
                writer.write(base.trimEnd())
                writer.write(block)
            } ?: error("Не удалось обновить session_info.txt")
            if (inserted) {
                resolver.update(
                    target,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
        } catch (error: Exception) {
            if (inserted) resolver.delete(target, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun appendFileInfo(session: SessionSummary, block: String) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val file = File(
            pictures,
            "AstroPhoto/${session.folderName}/session_info.txt"
        )
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText("sessionName: ${session.sessionName}\n")
        }
        file.appendText(block)
    }

    private fun uniqueMediaStoreName(
        collection: Uri,
        path: String,
        requested: String
    ): String {
        val base = requested.substringBeforeLast('.')
        val extension = requested.substringAfterLast('.', "zip")
        var candidate = requested
        var suffix = 1
        while (mediaStoreNameExists(collection, path, candidate)) {
            candidate = "${base}_${suffix++}.$extension"
        }
        return candidate
    }

    private fun mediaStoreNameExists(
        collection: Uri,
        path: String,
        name: String
    ): Boolean = context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Files.FileColumns._ID),
        "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
        arrayOf(name, path),
        null
    )?.use { it.moveToFirst() } == true

    private fun uniqueFile(directory: File, requested: String): File {
        var file = File(directory, requested)
        if (!file.exists()) return file
        val base = requested.substringBeforeLast('.')
        val extension = requested.substringAfterLast('.', "zip")
        var suffix = 1
        while (file.exists()) {
            file = File(directory, "${base}_${suffix++}.$extension")
        }
        return file
    }

    private suspend fun reportProgress(
        callback: suspend (SessionZipProgress) -> Unit,
        message: String,
        current: Int,
        total: Int
    ) {
        withContext(Dispatchers.Main.immediate) {
            callback(SessionZipProgress(message, current, total.coerceAtLeast(1)))
        }
    }

    private fun infoValue(info: String, key: String): String =
        info.lineSequence()
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "неизвестно"

    private fun fallbackSessionInfo(session: SessionSummary): String =
        session.infoContent.takeIf {
            it.isNotBlank() && it != "Нет информации"
        } ?: buildString {
            appendLine("sessionName: ${session.sessionName.ifBlank { "неизвестно" }}")
            appendLine("createdAt: неизвестно")
            appendLine("cameraId: неизвестно")
            appendLine("ISO: неизвестно")
            appendLine("exposureTimeNs: неизвестно")
            appendLine("focus: неизвестно")
            appendLine("selectedFormat: неизвестно")
        }

    private fun formatExposureInfo(value: String): String {
        val nanoseconds = value.toLongOrNull() ?: return "неизвестно"
        return if (nanoseconds >= 1_000_000_000L) {
            String.format(
                Locale.getDefault(),
                "%.3f сек",
                nanoseconds / 1_000_000_000.0
            ).trimEnd('0').trimEnd('.')
        } else {
            "$nanoseconds нс"
        }
    }

    private fun sanitizeName(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '-')
            .take(48)

    private fun sanitizeEntryPath(value: String): String =
        value.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { segment ->
                segment.replace(Regex("[\\u0000-\\u001F]"), "_")
            }

    private data class ZipSource(
        val relativePath: String,
        val sizeBytes: Long,
        val open: () -> InputStream?
    )

    private data class WriteResult(
        val added: Int,
        val skipped: Int
    )

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private val ZIP_DIRECTORIES = listOf(
            "Lights/",
            "Lights/JPEG/",
            "Lights/RAW/",
            "Darks/",
            "Darks/JPEG/",
            "Darks/RAW/",
            "Tests/",
            "Tests/JPEG/",
            "Processed/"
        )
    }
}
