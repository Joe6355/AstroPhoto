package com.joe6355.astrophoto

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.AstroTestTags
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in UI checks on the explicitly created recovery-audit copy. */
class ProcessingRecoveryUiDeviceTest {
    @get:Rule(order = 0) val foregroundRule = AstroUiForegroundRule(MainActivity::class.java)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun interruptedProfileOpensItsSessionAndSurvivesActivityRecreation() {
        val folder = auditFolder("resume")
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("processing-recovery-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText(folder, substring = true) and
            hasAnyAncestor(hasTestTag("processing-recovery-dialog"))).assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("processing-recovery-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Открыть сессию").performClick()
        waitForList("session-details-list")
        composeRule.onNodeWithTag("processing-recovery-dialog").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(ProcessingRecoveryStore(context).pendingInterrupted().any { it.sessionFolder == folder })
    }

    @Test fun leavingSessionBackgroundingAndRecreatingActivityKeepOneTaskAlive() = runBlocking {
        val folder = auditFolder("navigation")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = SessionBrowserRepository(context).loadSessions()
        val index = sessions.indexOfFirst { it.folderName == folder }
        require(index >= 0)
        fun openCopy() {
            openSessions()
            composeRule.onNodeWithTag("sessions-list").performScrollToIndex(index)
            composeRule.onNodeWithText(sessions[index].sessionName).performClick()
            waitForList("session-details-list")
        }
        openCopy()
        val release = CompletableDeferred<Unit>()
        val ticks = AtomicInteger()
        val job = withContext(Dispatchers.Main) {
            checkNotNull(SessionProcessingCoordinator.start(context, folder, "QA navigation", "QA background", 1000) {
                while (!release.isCompleted) {
                    SessionProcessingCoordinator.update(folder, "QA background", ticks.incrementAndGet(), 1000)
                    delay(100)
                }
                SessionProcessingCoordinator.update(folder, "QA navigation complete")
            })
        }
        try {
            composeRule.onNodeWithText("Назад").performClick()
            waitForList("sessions-list")
            assertTrue(SessionProcessingCoordinator.isActive(folder))
            openCopy()
            composeRule.onNodeWithTag("session-details-list").performScrollToNode(hasTestTag(AstroTestTags.ProcessingProgress))
            composeRule.onNodeWithTag(AstroTestTags.ProcessingProgress).assertIsDisplayed()
            withContext(Dispatchers.Main) { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            waitForList("sessions-list")
            assertTrue(SessionProcessingCoordinator.isActive(folder))
            openCopy()
            val before = ticks.get()
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            delay(700)
            assertTrue("Processing must advance while its Activity is stopped", ticks.get() > before)
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Съёмки").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("processing-recovery-dialog").assertDoesNotExist()
            assertTrue(SessionProcessingCoordinator.isActive(folder))
            assertNull(withContext(Dispatchers.Main) {
                SessionProcessingCoordinator.start(context, folder, "Duplicate", "Duplicate", 1) {
                    fail("A duplicate must not execute after Activity recreation")
                }
            })
            openCopy()
            composeRule.onNodeWithTag("session-details-list").performScrollToNode(hasTestTag(AstroTestTags.ProcessingProgress))
            composeRule.onNodeWithTag(AstroTestTags.ProcessingProgress).assertIsDisplayed()
            composeRule.onNodeWithText("Остановить").performClick()
            withTimeout(10_000) { job.join() }
            assertTrue("The session's Stop button must cancel the coordinator job", job.isCancelled)
        } catch (error: Throwable) {
            runCatching { println(composeRule.onRoot(useUnmergedTree = true).printToString()) }
            throw error
        } finally {
            release.complete(Unit)
            withTimeout(10_000) { job.join() }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(AstroTestTags.ProcessingProgress).fetchSemanticsNodes().isEmpty()
        }
        assertFalse(SessionProcessingCoordinator.isActive(folder))
    }

    private fun auditFolder(mode: String): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit recovery UI audit only", args.getString("recoveryUi") == mode)
        val folder = requireNotNull(args.getString("auditDestination"))
        require(folder.startsWith("Session_QA_RECOVERY_20261022_"))
        return folder
    }

    private fun openSessions() {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Съёмки").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("Съёмки") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitForList("sessions-list")
    }

    private fun waitForList(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }
}
