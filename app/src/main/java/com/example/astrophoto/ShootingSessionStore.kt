package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShootingSession(
    val sessionName: String,
    val folderName: String,
    val createdAtMillis: Long,
    val note: String,
    val lightFrames: Int = 0,
    val darkFrames: Int = 0,
    val testShots: Int = 0,
    val lastTestShotStatus: String = "",
    val lastTestShotAtMillis: Long = 0L
)

data class SessionCaptureMetadata(
    val cameraId: String,
    val iso: Int,
    val exposureTimeNs: Long,
    val focus: String,
    val selectedFormat: String
)

class ShootingSessionStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(
        "astrophoto_shooting_session",
        Context.MODE_PRIVATE
    )

    fun load(): ShootingSession? {
        val folderName = preferences.getString("folder_name", null) ?: return null
        return ShootingSession(
            sessionName = preferences.getString("session_name", folderName) ?: folderName,
            folderName = folderName,
            createdAtMillis = preferences.getLong("created_at", System.currentTimeMillis()),
            note = preferences.getString("note", "") ?: "",
            lightFrames = preferences.getInt("light_frames", 0),
            darkFrames = preferences.getInt("dark_frames", 0),
            testShots = preferences.getInt("test_shots", 0),
            lastTestShotStatus =
                preferences.getString("last_test_shot_status", "") ?: "",
            lastTestShotAtMillis =
                preferences.getLong("last_test_shot_at", 0L)
        )
    }

    fun create(name: String, note: String): ShootingSession {
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val safeName = sanitizeName(name)
        val displayName = safeName.ifBlank { "Session_$timestamp" }
        val folderName = if (safeName.isBlank()) {
            displayName
        } else {
            "Session_${timestamp}_$safeName"
        }
        return ShootingSession(
            sessionName = displayName,
            folderName = folderName,
            createdAtMillis = now,
            note = note.trim().take(300)
        ).also(::save)
    }

    fun save(session: ShootingSession) {
        preferences.edit()
            .putString("session_name", session.sessionName)
            .putString("folder_name", session.folderName)
            .putLong("created_at", session.createdAtMillis)
            .putString("note", session.note)
            .putInt("light_frames", session.lightFrames)
            .putInt("dark_frames", session.darkFrames)
            .putInt("test_shots", session.testShots)
            .putString("last_test_shot_status", session.lastTestShotStatus)
            .putLong("last_test_shot_at", session.lastTestShotAtMillis)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun relativeDirectory(
        session: ShootingSession,
        dark: Boolean,
        raw: Boolean
    ): String {
        val frameType = if (dark) "Darks" else "Lights"
        val format = if (raw) "RAW" else "JPEG"
        return "AstroPhoto/${session.folderName}/$frameType/$format"
    }

    fun writeSessionInfo(
        session: ShootingSession,
        metadata: SessionCaptureMetadata
    ): Result<Unit> = runCatching {
        val content = buildString {
            appendLine("sessionName: ${session.sessionName}")
            appendLine(
                "createdAt: ${
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date(session.createdAtMillis))
                }"
            )
            appendLine("deviceModel: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("cameraId: ${metadata.cameraId}")
            appendLine("ISO: ${metadata.iso}")
            appendLine("exposureTimeNs: ${metadata.exposureTimeNs}")
            appendLine("focus: ${metadata.focus}")
            appendLine("selectedFormat: ${metadata.selectedFormat}")
            appendLine("lightFrames: ${session.lightFrames}")
            appendLine("darkFrames: ${session.darkFrames}")
            appendLine("testShots: ${session.testShots}")
            appendLine("lastTestShotStatus: ${session.lastTestShotStatus}")
            appendLine(
                "lastTestShotAt: ${
                    if (session.lastTestShotAtMillis > 0L) {
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date(session.lastTestShotAtMillis))
                    } else {
                        ""
                    }
                }"
            )
            appendLine("note: ${session.note.replace("\n", " ")}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeInfoWithMediaStore(session, content)
        } else {
            writeLegacyInfo(session, content)
        }
    }

    private fun writeInfoWithMediaStore(session: ShootingSession, content: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val existingUri = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf("session_info.txt", relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }

        var inserted = false
        val fileUri = existingUri ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "session_info.txt")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        )?.also { inserted = true } ?: error("Не удалось создать session_info.txt")

        try {
            resolver.openOutputStream(fileUri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(content)
            } ?: error("Не удалось открыть session_info.txt")
            if (inserted) {
                resolver.update(
                    fileUri,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
        } catch (exception: Exception) {
            if (inserted) resolver.delete(fileUri, null, null)
            throw exception
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyInfo(session: ShootingSession, content: String) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val sessionDirectory = File(
            pictures,
            "AstroPhoto/${session.folderName}"
        )
        if (!sessionDirectory.exists() && !sessionDirectory.mkdirs()) {
            error("Не удалось создать папку сессии")
        }
        FileOutputStream(File(sessionDirectory, "session_info.txt")).bufferedWriter().use {
            it.write(content)
        }
    }

    private fun sanitizeName(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '-')
            .take(40)
}
