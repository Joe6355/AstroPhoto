package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.Context
import android.util.Log

data class ProcessingFailureSummary(
    val runId: String,
    val sessionFolder: String,
    val preset: String,
    val lastCompletedStage: String,
    val exitKind: PreviousProcessExitKind,
    val message: String,
    val classification: PreviousRunClassification = PreviousRunClassification.INTERRUPTED_PROCESSING
)

class PreviousProcessingFailureDetector(private val context: Context) {
    fun detect(): ProcessingFailureSummary? {
        val journal = ProcessingRunJournal(context)
        val unfinished = journal.latestUnfinished() ?: return null
        val exit = PreviousProcessExitReader(context).latest(unfinished.startedAtMillis)
        val kind = exit?.kind ?: PreviousProcessExitKind.UNKNOWN
        val artifactsValid = unfinished.lastCompletedStage == "report_published" &&
            PublishedArtifactVerifier(context).verify(unfinished)
        val classification = classifyPreviousRun(unfinished, artifactsValid)
        Log.w(
            TAG,
            "unfinishedRun=${unfinished.runId.take(8)} lastStage=${unfinished.lastCompletedStage} " +
                "exitKind=${kind.name} exitReason=${exit?.reasonCode ?: -1} " +
                "maxHeap=${unfinished.runtimeMaxHeapBytes} usedAtStart=${unfinished.heapUsedAtStartBytes} " +
                "safeBudget=${unfinished.safeWorkingBudgetBytes} classification=${classification.name}"
        )
        if (classification == PreviousRunClassification.PROCESSING_COMPLETED_UI_FAILURE) {
            completeJournalWithSingleRetry(
                markCompleted = {
                    journal.markCompleted(unfinished.runId, "completed_ui_failure_recovered")
                },
                onFailure = { attempt, error ->
                    Log.e(
                        TAG,
                        "artifact recovery journal completion failed run=${unfinished.runId.take(8)} " +
                            "attempt=$attempt exception=${error::class.java.simpleName}",
                        error
                    )
                }
            )
        }
        val message = if (classification == PreviousRunClassification.PROCESSING_COMPLETED_UI_FAILURE) {
            "JPEG-результат сессии «${unfinished.sessionFolder}» был сохранён, " +
                "но приложение завершилось при обновлении интерфейса. " +
                "Файлы сессии не удалялись."
        } else {
            "Обработка сессии «${unfinished.sessionFolder}» профилем " +
                "«${unfinished.preset}» остановилась на этапе " +
                "«${unfinished.lastCompletedStage.displayStage()}». Android сообщил: " +
                "${kind.displayName}. Исходные кадры сохранены; откройте сессию и " +
                "запустите обработку снова."
        }
        return ProcessingFailureSummary(
            runId = unfinished.runId,
            sessionFolder = unfinished.sessionFolder,
            preset = unfinished.preset,
            lastCompletedStage = unfinished.lastCompletedStage,
            exitKind = kind,
            message = message,
            classification = classification
        )
    }

    fun dismiss(summary: ProcessingFailureSummary) {
        ProcessingRunJournal(context).markRecovered(summary.runId)
    }

    companion object {
        private const val TAG = "AstroPhotoJpegExit"
    }
}

private fun String.displayStage(): String = when (this) {
    "journal_created" -> "подготовка"
    "frame_analysis_completed" -> "анализ кадров"
    "registration_completed" -> "выравнивание кадров"
    "integration_completed" -> "сложение кадров"
    "reference_and_mask_completed" -> "построение маски неба"
    "clean_stack_composed" -> "создание чистого стека"
    "clean_stack_validated" -> "проверка чистого стека"
    "adaptive_processing_completed" -> "адаптивная обработка"
    "final_candidate_selected" -> "выбор результата"
    "report_prepared" -> "подготовка отчёта"
    "report_published" -> "сохранение отчёта"
    else -> this.replace('_', ' ')
}
