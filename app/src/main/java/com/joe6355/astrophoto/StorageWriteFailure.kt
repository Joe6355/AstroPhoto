package com.joe6355.astrophoto

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal const val PROCESSING_STORAGE_FULL_MESSAGE =
    "Недостаточно свободного места для обработки. Освободите место и повторите обработку."

private val NO_SPACE_CODE = Regex("\\bENOSPC\\b", RegexOption.IGNORE_CASE)

internal fun storageWriteFailure(error: Throwable, message: String): Throwable {
    if (error is CancellationException) return error
    if (error is IOException && error.message == message) return error
    val full = generateSequence(error) { it.cause }.take(16).any { cause ->
        NO_SPACE_CODE.containsMatchIn(cause.message.orEmpty()) ||
            cause.message.orEmpty().contains("No space left on device", ignoreCase = true)
    }
    return if (full) IOException(message, error) else error
}

/** The caller must own the new output; existing inputs must never be discarded here. */
internal inline fun <T> withOwnedOutputCleanup(discard: () -> Unit, block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    try {
        discard()
    } catch (cleanupError: Throwable) {
        if (cleanupError !== error) error.addSuppressed(cleanupError)
    }
    throw error
}
