package com.joe6355.astrophoto

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Authoritative session metadata. Public legacy sidecars remain readable; ZIP includes this copy. */
internal class SessionInfoStore(
    private val filesRoot: File,
    private val readLegacy: (String) -> String? = { null }
) {
    constructor(context: Context) : this(context.filesDir, { folder -> readLegacyInfo(context, folder) })

    private val root get() = File(filesRoot, "session-info")

    fun read(folder: String, readLegacyIfMissing: Boolean = true): String? = synchronized(lock) {
        val file = target(folder)
        if (file.isFile) file.readText(Charsets.UTF_8)
        else if (readLegacyIfMissing) readLegacy(folder) else null
    }

    fun folders(): List<String> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".txt") }
            .map { it.name.removeSuffix(".txt") }
    }

    fun update(folder: String, fallback: String = "", transform: (String) -> String): Unit = synchronized(lock) {
        val content = transform(read(folder).orEmpty().ifBlank { fallback })
        val target = target(folder)
        require(root.isDirectory || root.mkdirs()) { "Не удалось сохранить сведения о сессии" }
        val temporary = File(root, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun append(session: SessionSummary, block: String) {
        update(session.folderName, session.infoContent.takeUnless { it == "Нет информации" }
            .orEmpty().ifBlank { "sessionName: ${session.sessionName}\n" }) {
            it.trimEnd() + "\n" + block.trimStart()
        }
    }

    /** Prepare metadata before moving images; keep the old copy until the caller commits the move. */
    fun prepareRename(oldFolder: String, newFolder: String, fallback: String,
        transform: (String) -> String = { it }) = synchronized(lock) {
        if (oldFolder == newFolder) {
            update(oldFolder, fallback, transform)
        } else {
            require(!target(newFolder).exists()) { "Сведения для новой папки уже существуют" }
            val content = read(oldFolder) ?: fallback
            update(newFolder) { transform(content) }
        }
    }

    fun delete(folder: String): Boolean = synchronized(lock) {
        val file = target(folder)
        !file.exists() || file.delete()
    }

    private fun target(folder: String): File {
        require(folder.isNotBlank() && folder != "." && folder != ".." &&
            folder.none { it == '/' || it == '\\' || it == '\u0000' }) { "Недопустимая папка сессии" }
        return File(root, "$folder.txt").also {
            require(it.canonicalFile.parentFile == root.canonicalFile) { "Недопустимый путь сессии" }
        }
    }

    companion object {
        private val lock = Any()

        @Suppress("DEPRECATION")
        private fun readLegacyInfo(context: Context, folder: String): String? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                resolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID),
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
                    arrayOf("session_info.txt", "Pictures/AstroPhoto/$folder/"), null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "AstroPhoto/$folder/session_info.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
