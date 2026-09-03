package com.joe6355.astrophoto

internal class ProcessingStalledException : RuntimeException()

/** Fails only when a stage stops reporting progress for the configured interval. */
internal class ProgressWatchdog(
    timeoutMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val timeoutNanos = timeoutMillis.coerceAtLeast(1L) * 1_000_000L
    private var lastProgressNanos = nowNanos()

    fun reportProgress() {
        lastProgressNanos = nowNanos()
    }

    fun check() {
        if (nowNanos() - lastProgressNanos >= timeoutNanos) {
            throw ProcessingStalledException()
        }
    }
}
