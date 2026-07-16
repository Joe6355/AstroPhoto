package com.example.astrophoto.processing.jpeg.v2.diagnostics

data class JournalCompletionFailure(
    val attempts: Int,
    val throwable: Throwable
)

internal fun completeJournalWithSingleRetry(
    markCompleted: () -> Unit,
    onFailure: (attempt: Int, throwable: Throwable) -> Unit = { _, _ -> }
): JournalCompletionFailure? {
    var lastFailure: Throwable? = null
    repeat(MAX_ATTEMPTS) { index ->
        try {
            markCompleted()
            return null
        } catch (error: Throwable) {
            lastFailure = error
            onFailure(index + 1, error)
        }
    }
    return JournalCompletionFailure(MAX_ATTEMPTS, checkNotNull(lastFailure))
}

private const val MAX_ATTEMPTS = 2
