package com.joe6355.astrophoto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Read-only navigation checks on the five existing device fixtures; skips without them. */
class ExistingSessionsNavigationDeviceTest {
    @get:Rule(order = 0)
    val timeout = org.junit.rules.Timeout.seconds(120)

    @get:Rule(order = 1)
    val foregroundRule = AstroUiForegroundRule(MainActivity::class.java)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fiveSessionsReturnToTheirOriginalListPosition() {
        val sessions = existingSessions()
        val targets = fixturePrefixes.mapNotNull { prefix ->
            sessions.indexOfFirst { it.folderName.startsWith(prefix) }.takeIf { it >= 0 }
        }
        assumeTrue("Requires the five existing photo sessions", targets.size == 5)
        openSessionsTab()
        for (index in targets) {
            val session = sessions[index]
            composeRule.onNodeWithTag("sessions-list").performScrollToIndex(index)
            val before = composeRule.onNodeWithText(session.sessionName)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for (systemBack in listOf(true, false)) {
                composeRule.onNodeWithText(session.sessionName).performClick()
                composeRule.onNodeWithTag("session-details-list").assertIsDisplayed()
                if (systemBack) pressBack()
                else composeRule.onNodeWithText("Назад").performClick()
                waitForList("sessions-list")
                assertSamePosition(session.sessionName, before)
            }
        }
    }

    @Test
    fun returningFromFramesKeepsTheSessionPagePosition() {
        val sessions = existingSessions()
        val index = sessions.indexOfFirst { it.folderName.startsWith("Session_20260812_235602") }
        assumeTrue("Requires the existing 60-frame session", index >= 0)
        openSessionsTab()
        composeRule.onNodeWithTag("sessions-list").performScrollToIndex(index)
        composeRule.onNodeWithText(sessions[index].sessionName).performClick()
        composeRule.onNodeWithTag("session-details-list")
            .performScrollToNode(hasText("Кадры сессии"))
        val before = composeRule.onNodeWithText("Кадры сессии")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText("Кадры сессии").performClick()
        waitForList("session-frames-list")
        composeRule.onNodeWithTag("session-frames-list").performScrollToIndex(20)
        pressBack()
        composeRule.onNodeWithTag("session-details-list").assertIsDisplayed()
        assertSamePosition("Кадры сессии", before)
    }

    private fun existingSessions(): List<SessionSummary> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        assumeTrue("Requires preauthorized photo access",
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
        return runBlocking { SessionBrowserRepository(context).loadSessions() }
    }

    private fun openSessionsTab() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Съёмки").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Съёмки") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitForList("sessions-list")
    }

    private fun waitForList(tag: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun assertSamePosition(text: String, before: Rect) {
        val after = composeRule.onNodeWithText(text).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Vertical position of $text", before.top, after.top, 1f)
        assertEquals("Horizontal position of $text", before.left, after.left, 1f)
    }

    private val fixturePrefixes = listOf("Session_20260714_024539", "Session_20260812_223507",
        "Session_20260809_025611", "Session_20260805_035331", "Session_20260812_235602")
}
