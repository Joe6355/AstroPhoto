package com.joe6355.astrophoto

internal const val ZIP_STORAGE_FULL_MESSAGE =
    "Недостаточно свободного места для ZIP. Освободите место и повторите экспорт."

/** Owns only the new destination, from writing through successful publication. */
internal inline fun <T> publishSessionZip(
    write: () -> T,
    publish: () -> Unit,
    discard: () -> Unit
): T = try {
    withOwnedOutputCleanup(discard) {
        val result = write()
        publish()
        result
    }
} catch (error: Throwable) {
    throw zipPublicationFailure(error)
}

internal fun zipPublicationFailure(error: Throwable): Throwable =
    storageWriteFailure(error, ZIP_STORAGE_FULL_MESSAGE)
