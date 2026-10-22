package com.joe6355.astrophoto

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.PreviousProcessingFailureDetector
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Lifecycle checks use synthetic jobs and never create or change photo sessions. */
class ProcessingLifecycleDeviceTest {
    @get:Rule(order = 0) val foregroundRule = AstroUiForegroundRule()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folders = mutableListOf<String>()
    private val jobs = mutableListOf<Job>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()

    @After fun finishOwnJobs() = runBlocking {
        releases.forEach { it.complete(Unit) }
        folders.forEach(SessionProcessingCoordinator::cancel)
        withTimeout(10_000) { jobs.forEach { it.join() } }
        folders.forEach { ProcessingRecoveryStore(context).markFinished(it) }
    }

    @Test fun cancellationHoldsSessionUntilItsFinalizerFinishes() = runBlocking {
        val folder = folder()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = release()
        val first = start(folder) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        }
        withTimeout(5_000) { entered.await() }
        withContext(Dispatchers.Main) { SessionProcessingCoordinator.cancel(folder) }
        withTimeout(5_000) { cleaning.await() }
        assertTrue(SessionProcessingCoordinator.isActive(folder))
        assertTrue(SessionProcessingCoordinator.states.value.getValue(folder).running)
        assertNull(withContext(Dispatchers.Main) {
            SessionProcessingCoordinator.start(context, folder, "Duplicate", "Duplicate", 1) {
                fail("A second writer must not start while the first is closing files")
            }
        })
        release.complete(Unit)
        withTimeout(5_000) { first.join() }
        assertFalse(SessionProcessingCoordinator.isActive(folder))
        assertFalse(SessionProcessingCoordinator.states.value.getValue(folder).running)
        start(folder) { SessionProcessingCoordinator.update(folder, "Restarted") }.join()
        assertEquals("Restarted", SessionProcessingCoordinator.states.value.getValue(folder).status)
    }

    @Test fun immediateCompletionAndFailureBothReleaseTheSession() = runBlocking {
        val folder = folder()
        repeat(3) {
            val job = start(folder) { SessionProcessingCoordinator.update(folder, "Done $it") }
            withTimeout(5_000) { job.join() }
            assertFalse(SessionProcessingCoordinator.isActive(folder))
            assertFalse(SessionProcessingCoordinator.states.value.getValue(folder).running)
            assertFalse(ProcessingRecoveryStore(context).pendingInterrupted().any { it.sessionFolder == folder })
        }
        start(folder) { error("Controlled QA failure") }.join()
        assertEquals("Controlled QA failure", SessionProcessingCoordinator.states.value.getValue(folder).status)
        start(folder) { }.join()
    }

    @Test fun twoRequestsForTheSameSessionStartOnlyOneWriter() = runBlocking {
        val folder = folder()
        val release = release()
        val first = start(folder) { release.await() }
        assertNull(withContext(Dispatchers.Main) {
            SessionProcessingCoordinator.start(context, folder, "Duplicate", "Duplicate", 1) {
                fail("Duplicate task executed")
            }
        })
        release.complete(Unit)
        withTimeout(5_000) { first.join() }
    }

    @Test fun foregroundStartFailureDoesNotLeaveBusyStateOrRecoveryMarker() = runBlocking {
        val folder = folder()
        val restricted = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): android.content.ComponentName? =
                throw IllegalStateException("Controlled foreground start rejection")
        }
        val job = withContext(Dispatchers.Main) {
            SessionProcessingCoordinator.start(restricted, folder, "QA", "Preparing", 1) {
                fail("Work must not start when its foreground service is unavailable")
            }
        }
        assertNull(job)
        assertFalse(SessionProcessingCoordinator.isActive(folder))
        val state = SessionProcessingCoordinator.states.value.getValue(folder)
        assertFalse(state.running)
        assertTrue(state.status.startsWith("Не удалось начать обработку"))
        assertFalse(ProcessingRecoveryStore(context).pendingInterrupted().any { it.sessionFolder == folder })
    }

    @Test fun notificationStopCancelsEveryRunningSession() = runBlocking {
        val firstFolder = folder()
        val secondFolder = folder()
        val first = start(firstFolder) { awaitCancellation() }
        val second = start(secondFolder) { awaitCancellation() }
        withContext(Dispatchers.Main) {
            context.startService(Intent(context, ProcessingForegroundService::class.java)
                .setAction(ProcessingForegroundService.ACTION_CANCEL_ALL))
        }
        withTimeout(10_000) { first.join(); second.join() }
        for (folder in listOf(firstFolder, secondFolder)) {
            assertFalse(SessionProcessingCoordinator.isActive(folder))
            assertEquals("Обработка остановлена", SessionProcessingCoordinator.states.value.getValue(folder).status)
        }
    }

    @Test fun recoveryRetainsEverySessionUntilExplicitAcknowledgement() {
        val first = folder()
        val second = folder()
        ProcessingRecoveryStore(context).markStarted(first, "First profile")
        ProcessingRecoveryStore(context).markStarted(second, "Second profile")
        repeat(2) {
            val pending = ProcessingRecoveryStore(context).pendingInterrupted().associateBy { it.sessionFolder }
            assertEquals("First profile", pending.getValue(first).label)
            assertEquals("Second profile", pending.getValue(second).label)
        }
        ProcessingRecoveryStore(context).markFinished(second)
        val remaining = ProcessingRecoveryStore(context).pendingInterrupted()
        assertTrue(remaining.any { it.sessionFolder == first })
        assertFalse(remaining.any { it.sessionFolder == second })
    }

    @Test fun oldSingleSessionRecordSurvivesMigration() {
        val old = folder()
        val next = folder()
        val preferences = context.getSharedPreferences("processing_recovery", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("session_folder", old).putString("label", "Old profile").commit())
        ProcessingRecoveryStore(context).markStarted(next, "New profile")
        val pending = ProcessingRecoveryStore(context).pendingInterrupted().associateBy { it.sessionFolder }
        assertEquals("Old profile", pending.getValue(old).label)
        assertEquals("New profile", pending.getValue(next).label)
        assertFalse(preferences.contains("session_folder"))
    }

    @Test fun currentRunIsNotReportedAsPreviousProcessFailure() = runBlocking {
        val folder = folder()
        val release = release()
        val first = start(folder) { release.await() }
        val journal = ProcessingRunJournal(context)
        val run = journal.start(folder, "DEEP_SKY", 100, 0, 100)
        try {
            assertNull(PreviousProcessingFailureDetector(context).detect())
            assertFalse(ProcessingRecoveryStore(context).pendingInterrupted().any { it.sessionFolder == folder })
        } finally {
            journal.markCompleted(run.runId, "qa_lifecycle_completed")
            release.complete(Unit)
            withTimeout(5_000) { first.join() }
        }
    }

    private suspend fun start(folder: String, block: suspend () -> Unit): Job =
        withContext(Dispatchers.Main) {
            checkNotNull(SessionProcessingCoordinator.start(context, folder, "QA lifecycle", "Preparing", 1, block))
                .also(jobs::add)
        }

    private fun folder() = "Session_QA_RECOVERY_20261022_LIFECYCLE_${UUID.randomUUID()}"
        .also(folders::add)

    private fun release() = CompletableDeferred<Unit>().also(releases::add)
}
