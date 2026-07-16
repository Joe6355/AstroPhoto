package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.content.Context
import android.util.Log

data class ProcessingFailureSummary(
    val runId: String,
    val lastCompletedStage: String,
    val exitKind: PreviousProcessExitKind,
    val message: String
)

class PreviousProcessingFailureDetector(private val context: Context) {
    fun detect(): ProcessingFailureSummary? {
        val journal = ProcessingRunJournal(context)
        val unfinished = journal.latestUnfinished() ?: return null
        val exit = PreviousProcessExitReader(context).latest(unfinished.startedAtMillis)
        val kind = exit?.kind ?: PreviousProcessExitKind.UNKNOWN
        Log.w(
            TAG,
            "unfinishedRun=${unfinished.runId.take(8)} lastStage=${unfinished.lastCompletedStage} " +
                "exitKind=${kind.name} exitReason=${exit?.reasonCode ?: -1} " +
                "maxHeap=${unfinished.runtimeMaxHeapBytes} usedAtStart=${unfinished.heapUsedAtStartBytes} " +
                "safeBudget=${unfinished.safeWorkingBudgetBytes}"
        )
        return ProcessingFailureSummary(
            runId = unfinished.runId,
            lastCompletedStage = unfinished.lastCompletedStage,
            exitKind = kind,
            message = "Предыдущая JPEG-обработка остановилась на этапе «${unfinished.lastCompletedStage}». " +
                "Android сообщил: ${kind.displayName}. Файлы сессии не удалялись."
        )
    }

    fun dismiss(summary: ProcessingFailureSummary) {
        ProcessingRunJournal(context).markRecovered(summary.runId)
    }

    companion object {
        private const val TAG = "AstroPhotoJpegExit"
    }
}
