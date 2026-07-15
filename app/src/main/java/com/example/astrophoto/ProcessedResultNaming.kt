package com.example.astrophoto

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ProcessedOutputType(val prefix: String) {
    AVERAGE("Stacked"),
    AVERAGE_ALIGNED("StackedAligned"),
    AVERAGE_DARK("StackedDark"),
    AVERAGE_DARK_ALIGNED("StackedDarkAligned"),
    MASTER_DARK("MasterDark"),
    MEDIAN("Median"),
    MEDIAN_ALIGNED("MedianAligned"),
    SIGMA("Sigma"),
    SIGMA_ALIGNED("SigmaAligned"),
    RAW_LINEAR("RawLinear")
}

const val DEFAULT_PROCESSED_NAME_ATTEMPTS = 100

private val processedTimestampFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

fun buildProcessedResultBaseName(
    type: ProcessedOutputType,
    timestampMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val timestamp = Instant.ofEpochMilli(timestampMillis)
        .atZone(zoneId)
        .format(processedTimestampFormatter)
    return "${type.prefix}_$timestamp.jpg"
}

fun findUniqueProcessedResultName(
    baseName: String,
    maxAttempts: Int = DEFAULT_PROCESSED_NAME_ATTEMPTS,
    exists: (String) -> Boolean
): String {
    val extension = baseName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.US)
    require(extension in setOf("jpg", "jpeg", "png")) {
        "Processed result name must use a supported image extension"
    }
    require(maxAttempts > 0) { "Collision attempt count must be positive" }

    val base = baseName.substringBeforeLast('.')
    repeat(maxAttempts) { attempt ->
        val candidate = if (attempt == 0) {
            baseName
        } else {
            "${base}_${attempt.toString().padStart(2, '0')}.$extension"
        }
        if (!exists(candidate)) return candidate
    }
    throw IllegalStateException(
        "Не удалось создать уникальное имя результата за $maxAttempts попыток"
    )
}
