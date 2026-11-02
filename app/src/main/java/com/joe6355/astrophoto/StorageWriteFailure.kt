package com.joe6355.astrophoto

import java.io.IOException
import kotlinx.coroutines.CancellationException
import com.joe6355.astrophoto.processing.jpeg.v2.memory.InsufficientJpegMemoryException
import kotlin.math.ceil

internal const val PROCESSING_STORAGE_FULL_MESSAGE =
    "Недостаточно места в хранилище телефона для обработки. Освободите место и повторите обработку."

internal const val PROCESSING_MEMORY_MESSAGE = "Не хватает оперативной памяти для обработки."

internal fun processingResourceFailure(error: Throwable): Throwable {
    if (error is CancellationException) return error
    if (error.message.orEmpty().contains(PROCESSING_STORAGE_FULL_MESSAGE)) return error
    val memory = generateSequence(error) { it.cause }.take(16).firstOrNull {
        it is OutOfMemoryError || it is InsufficientJpegMemoryException
    }
    if (memory != null) {
        if (error.message.orEmpty().contains(PROCESSING_MEMORY_MESSAGE)) return error
        val details = if (memory is InsufficientJpegMemoryException) {
            val requiredMib = ceil(memory.requiredBytes.coerceAtLeast(0) / 1_048_576.0).toLong()
            val availableMib = memory.availableBytes.coerceAtLeast(0) / 1_048_576L
            "\nДля этого шага нужно не менее $requiredMib МБ; доступно обработке до $availableMib МБ."
        } else ""
        return IllegalStateException(
            PROCESSING_MEMORY_MESSAGE + details +
                "\nЗакройте другие приложения и повторите. При повторном отказе выберите меньше кадров.",
            error
        )
    }
    return storageWriteFailure(error, PROCESSING_STORAGE_FULL_MESSAGE)
}

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
