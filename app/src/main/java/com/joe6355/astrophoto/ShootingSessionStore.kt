package com.joe6355.astrophoto

import android.content.Context
import androidx.core.content.edit
import android.os.Build
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
        preferences.edit {
                putString("session_name", session.sessionName)
                .putString("folder_name", session.folderName)
                .putLong("created_at", session.createdAtMillis)
                .putString("note", session.note)
                .putInt("light_frames", session.lightFrames)
                .putInt("dark_frames", session.darkFrames)
                .putInt("test_shots", session.testShots)
                .putString("last_test_shot_status", session.lastTestShotStatus)
                .putLong("last_test_shot_at", session.lastTestShotAtMillis)
            }
    }

    fun clear() {
        preferences.edit {clear()}
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
            appendLine("createdAtMillis: ${session.createdAtMillis}")
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

        SessionInfoStore(context).update(session.folderName) { existing ->
            // Capture counters refresh, while processing/export history is retained.
            val keys = content.lineSequence().map { it.substringBefore(':') }.toSet()
            content + existing.lineSequence()
                .filter { it.isNotBlank() && it.substringBefore(':') !in keys }
                .joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" }
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
