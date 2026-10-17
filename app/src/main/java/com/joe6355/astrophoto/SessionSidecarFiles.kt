package com.joe6355.astrophoto

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Atomic app-private sidecars, with public legacy files read only as a fallback. */
internal class SessionSidecarFiles(
    private val root: File,
    private val extension: String,
    private val readLegacy: (String) -> String? = { null }
) {
    fun read(folder: String, readLegacyIfMissing: Boolean = true): String? = synchronized(lock) {
        val file = target(folder)
        if (file.isFile) file.readText(Charsets.UTF_8)
        else if (readLegacyIfMissing) readLegacy(folder) else null
    }

    fun folders(): List<String> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".$extension") }
            .map { it.name.removeSuffix(".$extension") }
    }

    fun update(folder: String, fallback: String = "", transform: (String) -> String): Unit = synchronized(lock) {
        write(folder, transform(read(folder).orEmpty().ifBlank { fallback }))
    }

    fun write(folder: String, content: String): Unit = synchronized(lock) {
        val destination = target(folder)
        require(root.isDirectory || root.mkdirs()) { "Не удалось сохранить данные сессии" }
        val temporary = File(root, "${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    /** Keep the old copy until the caller has successfully moved the session's public files. */
    fun prepareRename(oldFolder: String, newFolder: String, fallback: String? = null,
        transform: (String) -> String = { it }): Unit = synchronized(lock) {
        if (oldFolder != newFolder) {
            require(!target(newFolder).exists()) { "Данные для новой папки уже существуют" }
        }
        val content = read(oldFolder) ?: fallback ?: return@synchronized
        write(newFolder, transform(content))
    }

    fun delete(folder: String): Boolean = synchronized(lock) {
        val file = target(folder)
        !file.exists() || file.delete()
    }

    private fun target(folder: String): File {
        require(folder.isNotBlank() && folder != "." && folder != ".." &&
            folder.none { it == '/' || it == '\\' || it == '\u0000' }) { "Недопустимая папка сессии" }
        return File(root, "$folder.$extension").also {
            require(it.canonicalFile.parentFile == root.canonicalFile) { "Недопустимый путь сессии" }
        }
    }

    companion object {
        private val lock = Any()
    }
}
