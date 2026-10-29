package com.joe6355.astrophoto

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal const val ZIP_STORAGE_FULL_MESSAGE =
    "Недостаточно свободного места для ZIP. Освободите место и повторите экспорт."

/** Owns only the new destination, from writing through successful publication. */
internal inline fun <T> publishSessionZip(
    write: () -> T,
    publish: () -> Unit,
    discard: () -> Unit
): T = try {
    val result = write()
    publish()
    result
} catch (error: Throwable) {
    try {
        discard()
    } catch (cleanupError: Throwable) {
        if (cleanupError !== error) error.addSuppressed(cleanupError)
    }
    throw zipPublicationFailure(error)
}

internal fun zipPublicationFailure(error: Throwable): Throwable {
    if (error is CancellationException) return error
    if (error is IOException && error.message == ZIP_STORAGE_FULL_MESSAGE) return error
    val full = generateSequence(error) { it.cause }.take(16).any { cause ->
        val message = cause.message.orEmpty()
        Regex("\\bENOSPC\\b", RegexOption.IGNORE_CASE).containsMatchIn(message) ||
            message.contains("No space left on device", ignoreCase = true)
    }
    return if (full) IOException(ZIP_STORAGE_FULL_MESSAGE, error) else error
}
