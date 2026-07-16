package com.example.astrophoto.processing.jpeg.v2.completion

import com.example.astrophoto.JpegStackResult
import kotlinx.coroutines.CancellationException

data class CompletionStepFailure(
    val operation: String,
    val throwable: Throwable
)

data class PostCompletionEvent(
    val operation: String,
    val phase: String,
    val runIdPrefix: String,
    val outputFileName: String,
    val throwable: Throwable? = null
)

internal inline fun runCompletionStep(
    operation: String,
    block: () -> Unit
): CompletionStepFailure? = try {
    block()
    null
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    CompletionStepFailure(operation, error)
}

internal class AutomaticProfileCompletionCoordinator(
    private val logger: (PostCompletionEvent) -> Unit
) {
    fun complete(
        existingResults: List<JpegStackResult>,
        created: JpegStackResult,
        totalInputFrames: Int,
        selectedResultTitle: String,
        updateResults: (List<JpegStackResult>) -> Unit,
        updateStatus: (String) -> Unit,
        onStackCompleted: () -> Unit
    ): List<CompletionStepFailure> {
        val failures = mutableListOf<CompletionStepFailure>()
        val runPrefix = created.processingRunId?.take(8).orEmpty()
        fun event(operation: String, phase: String, throwable: Throwable? = null) {
            logger(PostCompletionEvent(operation, phase, runPrefix, created.fileName, throwable))
        }
        fun step(operation: String, block: () -> Unit) {
            val failure = runCompletionStep(operation, block)
            if (failure == null) {
                event(operation, "done")
            } else {
                failures += failure
                event(operation, "failed", failure.throwable)
            }
        }

        event("ui_state", "start")
        val updatedResults = appendUniqueResult(existingResults, created)
        step("profileResults.update") { updateResults(updatedResults) }
        val successStatus = automaticProfileSuccessStatus(
            created,
            totalInputFrames,
            selectedResultTitle
        )
        step("status.build") { updateStatus(successStatus) }
        event("ui_state", "done")

        event("callback", "start")
        step("callback") { onStackCompleted() }

        if (failures.isNotEmpty()) {
            step("status.warning") {
                updateStatus("$successStatus\n$POST_COMPLETION_WARNING")
            }
        }
        return failures.toList()
    }
}

internal fun appendUniqueResult(
    existing: List<JpegStackResult>,
    created: JpegStackResult
): List<JpegStackResult> = if (existing.any { sameResultIdentity(it, created) }) {
    existing
} else {
    existing + created
}

internal fun sameResultIdentity(first: JpegStackResult, second: JpegStackResult): Boolean =
    when {
        first.contentUri != null && second.contentUri != null -> first.contentUri == second.contentUri
        first.filePath != null && second.filePath != null -> first.filePath == second.filePath
        else -> first.fileName.equals(second.fileName, ignoreCase = true)
    }

internal fun automaticProfileSuccessStatus(
    created: JpegStackResult,
    totalInputFrames: Int,
    selectedResultTitle: String
): String = buildString {
    append("Готово: ${created.fileName}")
    append("\nПринято кадров: ${created.frameCount} / $totalInputFrames")
    append("\nВыбран результат: $selectedResultTitle")
    append("\nЗвёзды: ${created.starsBefore ?: 0} → ${created.starCount ?: 0}")
    append("\nFallback: ${if (created.fallbackUsed) "да" else "нет"}")
    created.warnings.forEach { append("\n$it") }
}

internal const val POST_COMPLETION_WARNING =
    "Результат сохранён, но не удалось обновить интерфейс"
