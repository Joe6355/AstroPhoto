package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionRenameResult(
    val newFolderName: String,
    val newSessionName: String,
    val metadataUpdated: Boolean
)

data class SessionDeleteResult(
    val deletedFiles: Int,
    val failedFiles: Int,
    val activeSessionCleared: Boolean
)

class SessionFileManager(private val context: Context) {
    private val sessionStore = ShootingSessionStore(context)

    suspend fun renameSession(
        session: SessionSummary,
        requestedName: String
    ): Result<SessionRenameResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = sanitizeManagedName(requestedName)
            require(safeName.isNotBlank()) { "Имя сессии не может быть пустым" }
            val duplicate = SessionBrowserRepository(context)
                .loadSessions()
                .any {
                    it.folderName != session.folderName &&
                        it.sessionName.equals(safeName, ignoreCase = true)
                }
            require(!duplicate) { "Сессия с таким именем уже существует" }
            val newFolderName = buildRenamedFolderName(session.folderName, safeName)
            require(newFolderName != session.folderName || safeName != session.sessionName) {
                "Новое имя совпадает с текущим"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                renameMediaStoreFolder(session.folderName, newFolderName)
            } else {
                renameLegacyFolder(session.folderName, newFolderName)
            }

            val metadataUpdated = runCatching {
                updateRenameMetadata(
                    folderName = newFolderName,
                    oldName = session.sessionName,
                    newName = safeName
                )
            }.isSuccess
            val active = sessionStore.load()
            if (active?.folderName == session.folderName) {
                sessionStore.save(
                    active.copy(
                        sessionName = safeName,
                        folderName = newFolderName
                    )
                )
            }
            SessionRenameResult(
                newFolderName = newFolderName,
                newSessionName = safeName,
                metadataUpdated = metadataUpdated
            )
        }
    }

    suspend fun deleteSession(
        session: SessionSummary,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<SessionDeleteResult> = withContext(Dispatchers.IO) {
        runCatching {
            val counts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteMediaStoreSession(session.folderName, onProgress)
            } else {
                deleteLegacySession(session.folderName, onProgress)
            }
            val active = sessionStore.load()
            val activeCleared = active?.folderName == session.folderName
            if (activeCleared) sessionStore.clear()
            SessionDeleteResult(
                deletedFiles = counts.first,
                failedFiles = counts.second,
                activeSessionCleared = activeCleared
            )
        }
    }

    private fun renameMediaStoreFolder(oldFolder: String, newFolder: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val oldBase = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$oldFolder/"
        val newBase = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$newFolder/"
        require(!mediaStoreFolderExists(collection, newBase)) {
            "Сессия с таким именем папки уже существует"
        }
        val entries = mutableListOf<Pair<Long, String>>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$oldBase%"),
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            while (cursor.moveToNext()) {
                entries += cursor.getLong(idIndex) to cursor.getString(pathIndex).orEmpty()
            }
        }

        val moved = mutableListOf<Pair<Long, String>>()
        try {
            entries.forEach { (id, oldPath) ->
                val newPath = newBase + oldPath.removePrefix(oldBase)
                val uri = ContentUris.withAppendedId(collection, id)
                val updated = resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.RELATIVE_PATH, newPath)
                    },
                    null,
                    null
                )
                if (updated != 1) error("Не удалось переместить один из файлов сессии")
                moved += id to oldPath
            }
        } catch (error: Exception) {
            moved.asReversed().forEach { (id, oldPath) ->
                runCatching {
                    resolver.update(
                        ContentUris.withAppendedId(collection, id),
                        ContentValues().apply {
                            put(MediaStore.Files.FileColumns.RELATIVE_PATH, oldPath)
                        },
                        null,
                        null
                    )
                }
            }
            throw IllegalStateException(
                "Не удалось переименовать папку сессии. Изменения отменены.",
                error
            )
        }
    }

    private fun mediaStoreFolderExists(
        collection: android.net.Uri,
        basePath: String
    ): Boolean = context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Files.FileColumns._ID),
        "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
        arrayOf("$basePath%"),
        null
    )?.use { it.moveToFirst() } == true

    @Suppress("DEPRECATION")
    private fun renameLegacyFolder(oldFolder: String, newFolder: String) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val root = File(pictures, "AstroPhoto").canonicalFile
        val oldDirectory = File(root, oldFolder).canonicalFile
        val newDirectory = File(root, newFolder).canonicalFile
        require(
            oldDirectory.path.startsWith(root.path + File.separator) &&
                newDirectory.path.startsWith(root.path + File.separator)
        ) {
            "Недопустимый путь сессии"
        }
        require(!newDirectory.exists()) { "Сессия с таким именем уже существует" }
        if (oldDirectory.exists() && !oldDirectory.renameTo(newDirectory)) {
            error("Не удалось переименовать папку сессии")
        }
    }

    private suspend fun deleteMediaStoreSession(
        folderName: String,
        onProgress: suspend (Int, Int) -> Unit
    ): Pair<Int, Int> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val basePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$folderName/"
        val ids = mutableListOf<Long>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$basePath%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        var deleted = 0
        var failed = 0
        ids.forEachIndexed { index, id ->
            val removed = runCatching {
                resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
            }.getOrDefault(0)
            if (removed > 0) deleted++ else failed++
            withContext(Dispatchers.Main.immediate) {
                onProgress(index + 1, ids.size.coerceAtLeast(1))
            }
        }
        return deleted to failed
    }

    @Suppress("DEPRECATION")
    private suspend fun deleteLegacySession(
        folderName: String,
        onProgress: suspend (Int, Int) -> Unit
    ): Pair<Int, Int> {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val astroPhotoRoot = File(pictures, "AstroPhoto").canonicalFile
        val directory = File(astroPhotoRoot, folderName).canonicalFile
        require(directory.path.startsWith(astroPhotoRoot.path + File.separator)) {
            "Недопустимый путь сессии"
        }
        if (!directory.exists()) return 0 to 0
        var deleted = 0
        var failed = 0
        val entries = directory.walkBottomUp().toList()
        var processedFiles = 0
        val totalFiles = entries.count { it.isFile }.coerceAtLeast(1)
        entries.forEach { file ->
            if (file.isFile) {
                if (file.delete()) deleted++ else failed++
                processedFiles++
                withContext(Dispatchers.Main.immediate) {
                    onProgress(processedFiles, totalFiles)
                }
            } else {
                file.delete()
            }
        }
        return deleted to failed
    }

    private fun updateRenameMetadata(
        folderName: String,
        oldName: String,
        newName: String
    ) {
        val renamedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
        val block = buildString {
            appendLine()
            appendLine("oldName: $oldName")
            appendLine("newName: $newName")
            appendLine("renamedAt: $renamedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            updateMediaStoreInfo(folderName, newName, block)
        } else {
            updateLegacyInfo(folderName, newName, block)
        }
    }

    private fun updateMediaStoreInfo(
        folderName: String,
        sessionName: String,
        block: String
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val path =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$folderName/"
        val existingUri = resolver.query(
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
        val existing = existingUri?.let {
            resolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                reader.readText()
            }
        }.orEmpty()
        val updatedContent = replaceSessionName(existing, sessionName) + block
        var inserted = false
        val uri = existingUri ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "session_info.txt")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, path)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        )?.also { inserted = true } ?: error("Не удалось обновить session_info.txt")
        try {
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(updatedContent)
            } ?: error("Не удалось обновить session_info.txt")
            if (inserted) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
        } catch (error: Exception) {
            if (inserted) resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun updateLegacyInfo(
        folderName: String,
        sessionName: String,
        block: String
    ) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val file = File(pictures, "AstroPhoto/$folderName/session_info.txt")
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) file.readText() else ""
        file.writeText(replaceSessionName(existing, sessionName) + block)
    }

    private fun replaceSessionName(content: String, sessionName: String): String {
        val lines = content.lineSequence().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("sessionName:") }
        if (index >= 0) {
            lines[index] = "sessionName: $sessionName"
        } else {
            lines.add(0, "sessionName: $sessionName")
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun buildRenamedFolderName(oldFolder: String, safeName: String): String {
        val prefix = Regex("^Session_\\d{8}_\\d{6}")
            .find(oldFolder)
            ?.value
            ?: "Session_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            }"
        return "${prefix}_$safeName".take(96)
    }
}

fun sanitizeManagedName(value: String): String =
    value.trim()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_', '-')
        .take(48)
