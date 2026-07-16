package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.Context
import android.util.Log

data class ProcessingFailureSummary(
    val runId: String,
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
            "JPEG-результат был сохранён, но приложение завершилось при обновлении интерфейса. " +
                "Файлы сессии не удалялись."
        } else {
            "Предыдущая JPEG-обработка остановилась на этапе «${unfinished.lastCompletedStage}». " +
                "Android сообщил: ${kind.displayName}. Файлы сессии не удалялись."
        }
        return ProcessingFailureSummary(
            runId = unfinished.runId,
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
