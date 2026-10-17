package com.joe6355.astrophoto

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Authoritative session metadata. Public legacy sidecars remain readable; ZIP includes this copy. */
internal class SessionInfoStore(
    filesRoot: File,
    readLegacy: (String) -> String? = { null }
) {
    constructor(context: Context) : this(context.filesDir, { folder -> readLegacyInfo(context, folder) })

    private val files = SessionSidecarFiles(File(filesRoot, "session-info"), "txt", readLegacy)

    fun read(folder: String, readLegacyIfMissing: Boolean = true): String? =
        files.read(folder, readLegacyIfMissing)

    fun folders(): List<String> = files.folders()

    fun update(folder: String, fallback: String = "", transform: (String) -> String) =
        files.update(folder, fallback, transform)

    fun append(session: SessionSummary, block: String) {
        update(session.folderName, session.infoContent.takeUnless { it == "Нет информации" }
            .orEmpty().ifBlank { "sessionName: ${session.sessionName}\n" }) {
            it.trimEnd() + "\n" + block.trimStart()
        }
    }

    /** Prepare metadata before moving images; keep the old copy until the caller commits the move. */
    fun prepareRename(oldFolder: String, newFolder: String, fallback: String,
        transform: (String) -> String = { it }) {
        if (oldFolder == newFolder) {
            update(oldFolder, fallback, transform)
        } else {
            files.prepareRename(oldFolder, newFolder, fallback, transform)
        }
    }

    fun delete(folder: String): Boolean = files.delete(folder)

    companion object {
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
