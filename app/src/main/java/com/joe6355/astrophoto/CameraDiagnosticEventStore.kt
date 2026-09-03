package com.joe6355.astrophoto

import android.content.Context
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

internal data class CameraDiagnosticEvent(
    val timestampMillis: Long,
    val source: String,
    val message: String
)

internal fun boundedCameraDiagnosticEvents(
    events: List<CameraDiagnosticEvent>,
    limit: Int = 12
): List<CameraDiagnosticEvent> = events
    .filter { it.message.isNotBlank() }
    .sortedByDescending { it.timestampMillis }
    .distinctBy { it.source to it.message }
    .take(limit.coerceAtLeast(0))

internal fun formatCameraDiagnosticEvents(events: List<CameraDiagnosticEvent>): String {
    if (events.isEmpty()) return "Нет зарегистрированных ошибок"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return events.joinToString("\n") {
        "${formatter.format(Date(it.timestampMillis))} [${it.source}] ${it.message}"
    }
}

internal object CameraDiagnosticEventStore {
    private const val PREFERENCES = "camera_diagnostic_events"
    private const val KEY_NEXT = "next"
    private const val EVENT_PREFIX = "event_"
    private const val MAX_EVENTS = 12

    @Synchronized
    fun record(context: Context, source: String, message: String) {
        if (message.isBlank()) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val next = preferences.getLong(KEY_NEXT, 0L)
        val slot = (next % MAX_EVENTS).toInt()
        val encoded = listOf(
            System.currentTimeMillis().toString(),
            encode(source.trim().take(40)),
            encode(message.trim().replace(Regex("\\s+"), " ").take(500))
        ).joinToString("|")
        preferences.edit {
            putString("$EVENT_PREFIX$slot", encoded)
            putLong(KEY_NEXT, next + 1L)
        }
    }

    fun recent(context: Context): List<CameraDiagnosticEvent> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val events = (0 until MAX_EVENTS).mapNotNull { slot ->
            preferences.getString("$EVENT_PREFIX$slot", null)?.let(::decodeEvent)
        }
        return boundedCameraDiagnosticEvents(events, MAX_EVENTS)
    }

    private fun decodeEvent(value: String): CameraDiagnosticEvent? {
        val parts = value.split('|', limit = 3)
        if (parts.size != 3) return null
        return runCatching {
            CameraDiagnosticEvent(parts[0].toLong(), decode(parts[1]), decode(parts[2]))
        }.getOrNull()
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
    )
}
