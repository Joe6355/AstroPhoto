package com.joe6355.astrophoto

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

            val infoStore = SessionInfoStore(context)
            infoStore.prepareRename(session.folderName, newFolderName, session.infoContent) {
                renamedMetadata(it, session.sessionName, safeName)
            }
            var publicMoved = false
            if (session.folderName != newFolderName) {
                try {
                    renamePublicFolder(session.folderName, newFolderName)
                    publicMoved = true
                    // One atomic directory rename; failure does not partially move RAW sidecars.
                    AstroRawSidecarStore(context).renameSession(session.folderName, newFolderName)
                } catch (error: Exception) {
                    val rolledBack = if (publicMoved) {
                        runCatching { renamePublicFolder(newFolderName, session.folderName) }.isSuccess
                    } else {
                        (error as? SessionMoveFailure)?.rollbackComplete != false
                    }
                    // Preserve both copies when media could not be moved back completely.
                    if (rolledBack) infoStore.delete(newFolderName)
                    throw IllegalStateException(if (rolledBack) {
                        "Не удалось переименовать сессию. Кадры остались в прежней папке."
                    } else {
                        "Переименование завершено не полностью. Часть кадров осталась в другой папке; сведения сохранены."
                    }, error)
                }
            }
            val metadataUpdated = session.folderName == newFolderName || infoStore.delete(session.folderName)
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
            val publicCounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteMediaStoreSession(session.folderName, onProgress)
            } else {
                deleteLegacySession(session.folderName, onProgress)
            }
            val privateCounts = AstroRawSidecarStore(context)
                .deleteSession(session.folderName)
            val counts = publicCounts.first + privateCounts.first to
                publicCounts.second + privateCounts.second
            val infoDeleted = if (counts.second == 0) SessionInfoStore(context).delete(session.folderName) else true
            val active = sessionStore.load()
            val activeCleared = active?.folderName == session.folderName
            if (activeCleared) sessionStore.clear()
            SessionDeleteResult(
                deletedFiles = counts.first,
                failedFiles = counts.second + if (infoDeleted) 0 else 1,
                activeSessionCleared = activeCleared
            )
        }
    }

    private fun renamePublicFolder(oldFolder: String, newFolder: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) renameMediaStoreFolder(oldFolder, newFolder)
        else renameLegacyFolder(oldFolder, newFolder)
    }

    private class SessionMoveFailure(val rollbackComplete: Boolean, cause: Exception) :
        IllegalStateException("Не удалось переместить файлы сессии", cause)

    private fun renameMediaStoreFolder(oldFolder: String, newFolder: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val oldBase = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$oldFolder/"
        val newBase = "${Environment.DIRECTORY_PICTURES}/AstroPhoto/$newFolder/"
        require(!mediaStoreFolderExists(collection, newBase)) {
            "Сессия с таким именем папки уже существует"
        }
        val entries = mutableListOf<Pair<android.net.Uri, String>>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$oldBase%"),
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            val mediaTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex).orEmpty()
                // SQL LIKE treats '_' in session names as a wildcard; never move a neighbouring session.
                if (path.startsWith(oldBase)) {
                    // Updating an image via Files/<id> makes Android reject the Pictures directory.
                    val itemCollection = when (cursor.getInt(mediaTypeIndex)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else -> collection
                    }
                    entries += ContentUris.withAppendedId(itemCollection, cursor.getLong(idIndex)) to path
                }
            }
        }

        val moved = mutableListOf<Pair<android.net.Uri, String>>()
        try {
            entries.forEach { (uri, oldPath) ->
                val newPath = newBase + oldPath.removePrefix(oldBase)
                val updated = resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.RELATIVE_PATH, newPath)
                    },
                    null,
                    null
                )
                if (updated != 1) error("Не удалось переместить один из файлов сессии")
                moved += uri to oldPath
            }
        } catch (error: Exception) {
            var rollbackComplete = true
            moved.asReversed().forEach { (uri, oldPath) ->
                val restored = runCatching {
                    resolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Files.FileColumns.RELATIVE_PATH, oldPath)
                        },
                        null,
                        null
                    ) == 1
                }.getOrDefault(false)
                if (!restored) rollbackComplete = false
            }
            throw SessionMoveFailure(rollbackComplete, error)
        }
    }

    private fun mediaStoreFolderExists(
        collection: android.net.Uri,
        basePath: String
    ): Boolean = context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH),
        "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
        arrayOf("$basePath%"),
        null
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(0).orEmpty().startsWith(basePath)) return@use true
        }
        false
    } == true

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
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.RELATIVE_PATH),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$basePath%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).orEmpty().startsWith(basePath)) ids += cursor.getLong(0)
            }
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

    private fun renamedMetadata(
        content: String,
        oldName: String,
        newName: String
    ): String {
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
        return replaceSessionName(content, newName) + block
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
