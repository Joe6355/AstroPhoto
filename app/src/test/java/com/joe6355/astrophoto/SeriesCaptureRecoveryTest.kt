package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesCaptureRecoveryTest {
    @Test
    fun savedFramesAreCountedOnceForShortAndLongSeries() {
        listOf(3, 10, 31).forEach { total ->
            var record = runningRecord(total)
            repeat(total) { index ->
                record = advanceSeriesRecord(record, "frame_${index + 1}.jpg")
            }
            record = advanceSeriesRecord(record, "frame_1.jpg")
            assertEquals(total, record.completed)
            assertEquals(total, record.savedFiles.size)
        }
    }

    @Test
    fun processRestartMarksOnlyRunningSeriesAsInterrupted() {
        val interrupted = interruptRunningSeries(runningRecord(30).copy(completed = 7))
        assertEquals(SeriesRecoveryStatus.INTERRUPTED, interrupted.status)
        assertEquals(7, interrupted.completed)
        val completed = runningRecord(3).copy(status = SeriesRecoveryStatus.COMPLETED)
        assertEquals(SeriesRecoveryStatus.COMPLETED, interruptRunningSeries(completed).status)
    }

    @Test
    fun terminalSeriesDoesNotAcceptLateFrameCallbacks() {
        val stopped = runningRecord(10).copy(completed = 4, status = SeriesRecoveryStatus.STOPPED)
        assertEquals(stopped, advanceSeriesRecord(stopped, "late.jpg"))
    }

    private fun runningRecord(total: Int) = SeriesRecoveryRecord(
        total = total,
        completed = 0,
        status = SeriesRecoveryStatus.RUNNING,
        sessionFolder = "session"
    )
}
