package com.joe6355.astrophoto

import android.content.Context
import androidx.core.content.edit

internal enum class SeriesRecoveryStatus {
    RUNNING, COMPLETED, STOPPED, FAILED, INTERRUPTED
}

internal data class SeriesRecoveryRecord(
    val total: Int,
    val completed: Int,
    val status: SeriesRecoveryStatus,
    val sessionFolder: String,
    val savedFiles: Set<String> = emptySet(),
    val message: String? = null
)

internal fun advanceSeriesRecord(
    record: SeriesRecoveryRecord,
    fileName: String
): SeriesRecoveryRecord {
    if (record.status != SeriesRecoveryStatus.RUNNING) return record
    val files = record.savedFiles + fileName
    return record.copy(
        completed = files.size.coerceAtMost(record.total),
        savedFiles = files
    )
}

internal fun interruptRunningSeries(record: SeriesRecoveryRecord): SeriesRecoveryRecord =
    if (record.status == SeriesRecoveryStatus.RUNNING) {
        record.copy(
            status = SeriesRecoveryStatus.INTERRUPTED,
            message = "Серия прервана системой: сохранено ${record.completed} из ${record.total} кадров. " +
                "Сохранённые кадры доступны в сессии."
        )
    } else {
        record
    }

internal class SeriesCaptureRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun begin(request: SeriesCaptureRequest) {
        preferences.edit(commit = true) {
            clear()
            putString(KEY_STATUS, SeriesRecoveryStatus.RUNNING.name)
            putInt(KEY_TOTAL, request.frameCount)
            putInt(KEY_COMPLETED, 0)
            putString(KEY_SESSION, request.sessionFolder)
            putStringSet(KEY_FILES, emptySet())
        }
    }

    fun frameSaved(fileName: String) {
        val current = load() ?: return
        save(advanceSeriesRecord(current, fileName))
    }

    fun finish(status: SeriesRecoveryStatus, message: String) {
        val current = load() ?: return
        save(current.copy(status = status, message = message))
    }

    fun restoreInterruptedState(): SeriesCaptureState? {
        val current = load() ?: return null
        val restored = interruptRunningSeries(current)
        if (restored != current) save(restored)
        return if (restored.status == SeriesRecoveryStatus.INTERRUPTED) {
            SeriesCaptureState(
                current = restored.completed,
                total = restored.total,
                status = "",
                remainingMillis = 0L,
                running = false,
                message = restored.message
            )
        } else null
    }

    private fun load(): SeriesRecoveryRecord? {
        val status = preferences.getString(KEY_STATUS, null)
            ?.let { runCatching { SeriesRecoveryStatus.valueOf(it) }.getOrNull() }
            ?: return null
        val total = preferences.getInt(KEY_TOTAL, 0)
        if (total <= 0) return null
        return SeriesRecoveryRecord(
            total = total,
            completed = preferences.getInt(KEY_COMPLETED, 0).coerceIn(0, total),
            status = status,
            sessionFolder = preferences.getString(KEY_SESSION, "").orEmpty(),
            savedFiles = preferences.getStringSet(KEY_FILES, emptySet()).orEmpty().toSet(),
            message = preferences.getString(KEY_MESSAGE, null)
        )
    }

    private fun save(record: SeriesRecoveryRecord) {
        preferences.edit(commit = true) {
            putString(KEY_STATUS, record.status.name)
            putInt(KEY_TOTAL, record.total)
            putInt(KEY_COMPLETED, record.completed)
            putString(KEY_SESSION, record.sessionFolder)
            putStringSet(KEY_FILES, record.savedFiles.toSet())
            putString(KEY_MESSAGE, record.message)
        }
    }

    private companion object {
        const val PREFERENCES = "series_capture_recovery"
        const val KEY_STATUS = "status"
        const val KEY_TOTAL = "total"
        const val KEY_COMPLETED = "completed"
        const val KEY_SESSION = "session"
        const val KEY_FILES = "saved_files"
        const val KEY_MESSAGE = "message"
    }
}
