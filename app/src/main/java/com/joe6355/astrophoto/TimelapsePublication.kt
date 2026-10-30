package com.joe6355.astrophoto

import java.io.File

internal const val TIMELAPSE_STORAGE_FULL_MESSAGE =
    "Недостаточно свободного места для MP4. Освободите место и повторите экспорт."

internal inline fun completeTimelapseEncoding(
    encode: () -> Unit,
    stopMuxer: () -> Unit,
    releaseResources: () -> Unit
) {
    withTimelapseCleanup(releaseResources) {
        encode()
        // stop() writes the MP4 container trailer. Its failure is an export failure.
        stopMuxer()
    }
}

internal fun releaseTimelapseResources(vararg release: () -> Unit) {
    var failure: Throwable? = null
    release.forEach { action ->
        try {
            action()
        } catch (error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure?.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

/** Close resources before commit; keep the primary error if cleanup also fails. */
internal inline fun <T> withTimelapseCleanup(cleanup: () -> Unit, block: () -> T): T {
    var failure: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            cleanup()
        } catch (error: Throwable) {
            if (failure == null) throw error
            if (failure !== error) failure.addSuppressed(error)
        }
    }
}

/** The caller owns only the newly allocated output, never the source JPEGs. */
internal inline fun publishSessionTimelapse(
    write: () -> Long,
    closeOutput: () -> Unit,
    publish: () -> Int,
    discard: () -> Unit
): Long = try {
    withOwnedOutputCleanup(discard) {
        val bytes = withTimelapseCleanup(closeOutput, write)
        check(bytes > 0L) { "MP4 пуст после записи" }
        check(publish() == 1) { "Не удалось опубликовать MP4" }
        bytes
    }
} catch (error: Throwable) {
    throw timelapsePublicationFailure(error)
}

internal fun timelapsePublicationFailure(error: Throwable): Throwable =
    storageWriteFailure(error, TIMELAPSE_STORAGE_FULL_MESSAGE)

/** Atomically reserve our own file so constructor failure cannot delete an older video. */
internal fun reserveTimelapseFile(requested: File): File {
    var target = requested
    var suffix = 2
    while (!target.createNewFile()) {
        target = File(requested.parentFile, "${requested.nameWithoutExtension}_${suffix}.mp4")
        suffix++
    }
    return target
}
