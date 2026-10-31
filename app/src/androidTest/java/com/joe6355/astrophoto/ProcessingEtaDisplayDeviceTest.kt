package com.joe6355.astrophoto

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Checks real UI/service wiring with an isolated job, not astronomical processing accuracy. */
class ProcessingEtaDisplayDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(60)
    @get:Rule(order = 1) val foregroundRule = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var folder: String? = null
    private var job: Job? = null
    private val changeStage = CompletableDeferred<Unit>()

    @After fun finishOwnJob(): Unit = runBlocking {
        changeStage.complete(Unit)
        folder?.let(SessionProcessingCoordinator::cancel)
        withTimeout(10_000) { job?.join() }
        folder?.let {
            ProcessingRecoveryStore(context).markFinished(it)
            FrameMarksStore(context).delete(it)
        }
    }

    @Test fun etaMatchesScreenAndNotificationThenClearsOnStageChangeAndStop() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("etaDisplayAudit") == "true")
        check(context.packageName == "com.joe6355.astrophoto.qa20261017")
        assertTrue("Do not interrupt an existing processing task",
            SessionProcessingCoordinator.states.value.values.none { it.running })
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue("The driver must temporarily allow QA notifications", notifications.areNotificationsEnabled())
        val ownFolder = "Session_QA_ETA_UI_${UUID.randomUUID()}".also { folder = it }
        val session = SessionSummary(
            folderName = ownFolder,
            sessionName = "QA ETA",
            relativePath = "Pictures/AstroPhoto/$ownFolder/",
            createdAtMillis = System.currentTimeMillis(),
            lightsJpeg = 0,
            darksJpeg = 0,
            totalSizeBytes = 0L,
            infoContent = ""
        )
        composeRule.setContent {
            AstroPhotoTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    JpegStackingBlock(session, refreshKey = 0, onStackCompleted = {})
                }
            }
        }
        val ready = CompletableDeferred<Unit>()
        val changed = CompletableDeferred<Unit>()
        val firstStage = "QA ETA: анализ"
        val nextStage = "QA ETA: другой этап"
        job = withContext(Dispatchers.Main) {
            SessionProcessingCoordinator.start(context, ownFolder, "QA ETA", firstStage, 10) {
                SessionProcessingCoordinator.update(ownFolder, firstStage, 0, 10)
                delay(650)
                SessionProcessingCoordinator.update(ownFolder, firstStage, 1, 10)
                delay(650)
                SessionProcessingCoordinator.update(ownFolder, firstStage, 2, 10)
                ready.complete(Unit)
                changeStage.await()
                SessionProcessingCoordinator.update(ownFolder, nextStage, 0, 4)
                changed.complete(Unit)
                awaitCancellation()
            }
        }
        assertNotNull(job)
        withTimeout(10_000) { ready.await() }
        val state = SessionProcessingCoordinator.states.value.getValue(ownFolder)
        assertTrue(requireNotNull(state.stageEtaMillis) > 0)
        val etaText = state.statusWithEta
        composeRule.onNodeWithText(etaText).performScrollTo().assertIsDisplayed()
        val firstNotification = awaitNotification(notifications) {
            it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == etaText
        }
        assertEquals(etaText, firstNotification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(2, firstNotification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(10, firstNotification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        val output = File(context.cacheDir, "eta-display-audit/${UUID.randomUUID()}")
        check(output.mkdirs())
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "screen.png").outputStream().use {
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()

        changeStage.complete(Unit)
        withTimeout(5_000) { changed.await() }
        assertNull(SessionProcessingCoordinator.states.value.getValue(ownFolder).stageEtaMillis)
        composeRule.onNodeWithText(nextStage).assertIsDisplayed()
        composeRule.onAllNodesWithText("До конца этапа", substring = true).assertCountEquals(0)
        val changedNotification = awaitNotification(notifications) {
            it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == nextStage
        }
        assertFalse(changedNotification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            .contains("До конца этапа"))
        assertEquals(0, changedNotification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(4, changedNotification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        val stop = changedNotification.actions.single { it.title.toString() == "Остановить" }
        stop.actionIntent.send()
        withTimeout(10_000) { requireNotNull(job).join() }
        val finished = SessionProcessingCoordinator.states.value.getValue(ownFolder)
        assertFalse(finished.running)
        assertNull(finished.stageEtaMillis)
        assertEquals("Обработка остановлена", finished.statusWithEta)
        composeRule.onAllNodesWithText("До конца этапа", substring = true).assertCountEquals(0)
        withTimeout(5_000) {
            while (notifications.activeNotifications.any { it.id == 2101 }) delay(50)
        }
        assertFalse(ProcessingRecoveryStore(context).pendingInterrupted().any { it.sessionFolder == ownFolder })
        val proof = JSONObject().apply {
            put("sessionFolder", ownFolder)
            put("measuredEtaMillis", state.stageEtaMillis)
            put("etaText", etaText)
            put("screenAndNotificationAgree", true)
            put("progressCurrent", 2)
            put("progressTotal", 10)
            put("stageChangeClearsEta", true)
            put("stoppedThroughNotificationPendingIntent", true)
            put("notificationRemoved", true)
            put("photosReadOrWritten", false)
            put("realPipelineAccuracyTested", false)
        }
        File(output, "proof.json").writeText(proof.toString(2))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "QA_ETA_PROOF ${output.relativeTo(context.cacheDir).invariantSeparatorsPath}\n")
        })
    }

    private suspend fun awaitNotification(manager: NotificationManager,
        predicate: (Notification) -> Boolean): Notification = withTimeout(5_000) {
        var result: Notification? = null
        while (result == null) {
            result = manager.activeNotifications.firstOrNull { it.id == 2101 }
                ?.notification?.takeIf(predicate)
            if (result == null) delay(50)
        }
        checkNotNull(result)
    }
}
