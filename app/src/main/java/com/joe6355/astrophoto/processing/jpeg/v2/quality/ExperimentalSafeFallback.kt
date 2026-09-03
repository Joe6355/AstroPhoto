package com.joe6355.astrophoto.processing.jpeg.v2.quality

import kotlinx.coroutines.CancellationException

internal suspend fun <T> withExperimentalSafeFallback(
    enabled: Boolean,
    primary: suspend () -> T,
    safeFallback: suspend (Exception) -> T
): T = try {
    primary()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    if (!enabled) throw error
    try {
        safeFallback(error)
    } catch (fallbackError: Exception) {
        fallbackError.addSuppressed(error)
        throw fallbackError
    }
}
