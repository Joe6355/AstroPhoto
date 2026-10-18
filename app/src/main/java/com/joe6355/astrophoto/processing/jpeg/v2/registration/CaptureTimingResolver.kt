package com.joe6355.astrophoto.processing.jpeg.v2.registration

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class CaptureTimingSample(
    val frameId: String,
    val captureIndex: Int,
    val capturedAtMillis: Long?
)

data class CaptureTimingAxis(
    val normalizedTimeByFrameId: Map<String, Float>,
    val nominalIntervalMillis: Double?
) {
    val source: String get() = if (normalizedTimeByFrameId.isEmpty()) "CAPTURE_INDEX" else "EXIF_ORIGINAL"
}

/** Keep ordinal frame identities; use elapsed capture time only for motion prediction. */
object CaptureTimingResolver {
    fun resolve(samples: List<CaptureTimingSample>): CaptureTimingAxis {
        val fallback = CaptureTimingAxis(emptyMap(), null)
        if (samples.size < 2 || samples.any { it.capturedAtMillis == null } ||
            samples.map { it.frameId }.distinct().size != samples.size ||
            samples.map { it.captureIndex }.distinct().size != samples.size
        ) return fallback
        val ordered = samples.sortedBy { it.captureIndex }
        val intervals = ordered.zipWithNext { first, second ->
            val millis = second.capturedAtMillis!! - first.capturedAtMillis!!
            millis.toDouble() / (second.captureIndex.toLong() - first.captureIndex.toLong())
        }.sorted()
        if (intervals.any { !it.isFinite() || it <= 0.0 }) return fallback
        val middle = intervals.size / 2
        val nominal = if (intervals.size % 2 == 1) intervals[middle]
        else (intervals[middle - 1] + intervals[middle]) * 0.5
        if (!nominal.isFinite() || nominal <= 0.0) return fallback
        val first = ordered.first()
        val coordinates = ordered.associate { sample ->
            sample.frameId to (first.captureIndex.toDouble() +
                (sample.capturedAtMillis!! - first.capturedAtMillis!!).toDouble() / nominal).toFloat()
        }
        if (coordinates.values.any { !it.isFinite() } ||
            ordered.zipWithNext().any { (first, second) ->
                coordinates.getValue(second.frameId) <= coordinates.getValue(first.frameId)
            }
        ) return fallback
        return CaptureTimingAxis(coordinates, nominal)
    }
}

/** EXIF local wall time is sufficient for elapsed times when no UTC offset was recorded. */
object ExifCaptureTimeParser {
    private val dateTime = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(original: String?, subsecond: String?, offset: String? = null): Long? = try {
        val value = original?.trim()?.takeIf { it.isNotEmpty() }
        if (value == null) null else {
            val fraction = subsecond?.trim().orEmpty()
            if (fraction.any { it !in '0'..'9' }) null else {
                val millis = fraction.take(3).padEnd(3, '0').toInt()
                val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let(ZoneOffset::of) ?: ZoneOffset.UTC
                LocalDateTime.parse(value, dateTime).toInstant(zone).toEpochMilli() + millis
            }
        }
    } catch (_: Exception) {
        null
    }
}
