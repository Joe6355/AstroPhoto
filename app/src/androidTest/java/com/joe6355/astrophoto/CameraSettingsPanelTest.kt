package com.joe6355.astrophoto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CameraSettingsPanelTest {
    @get:Rule(order = 0)
    val timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule(order = 1)
    val foregroundRule = AstroUiForegroundRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test
    fun handleTapExpandsPanel() {
        setPanelContent()

        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performClick()
        composeRule.waitForIdle()
        assertPanelState(CameraPanelAnchor.EXPANDED)
    }

    @Test
    fun dragWinsOverTapAndExpandsPanel() {
        setPanelContent()

        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle)
            .performTouchInput {
                swipe(
                    start = center,
                    end = Offset(center.x, center.y - 1_600f),
                    durationMillis = 500
                )
            }
        composeRule.waitForIdle()

        assertPanelState(CameraPanelAnchor.EXPANDED)
    }

    @Test
    fun listAtTopCanCollapseExpandedPanel() {
        setPanelContent(initial = CameraPanelAnchor.EXPANDED)

        composeRule.onNodeWithTag("camera-settings-list")
            .performTouchInput { swipeDown(durationMillis = 500) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsPanel).assert(
            SemanticsMatcher("panel left expanded anchor") {
                it.config[SemanticsProperties.StateDescription] !=
                    CameraPanelAnchor.EXPANDED.name
            }
        )
    }

    @Test
    fun slowListDragCommitsCollapsedAnchorBeforeBackNavigation() {
        var navigatedBack = false
        setPanelContent(initial = CameraPanelAnchor.EXPANDED,
            onNavigateBack = { navigatedBack = true })
        composeRule.onNodeWithTag("camera-settings-list")
            .performTouchInput { swipeDown(durationMillis = 1_800) }
        composeRule.waitForIdle()
        assertPanelState(CameraPanelAnchor.COLLAPSED)
        pressBack()
        composeRule.runOnIdle { assertTrue(navigatedBack) }
    }

    @Test
    fun backCollapsesBeforeCallingNavigation() {
        var navigatedBack = false
        setPanelContent(
            initial = CameraPanelAnchor.EXPANDED,
            onNavigateBack = { navigatedBack = true }
        )

        pressBack()
        composeRule.waitForIdle()

        assertPanelState(CameraPanelAnchor.COLLAPSED)
        composeRule.runOnIdle { assertFalse(navigatedBack) }
    }

    @Test
    fun anchorSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            PanelHarness(initial = CameraPanelAnchor.COLLAPSED)
        }
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performClick()
        composeRule.waitForIdle()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertPanelState(CameraPanelAnchor.EXPANDED)
    }

    @Test
    fun draggingDoesNotRepeatedlyRecomposeOrMeasurePreparedSettings() {
        val compositions = AtomicInteger()
        val measurements = AtomicInteger()
        composeRule.setContent {
            PanelHarness(initial = CameraPanelAnchor.COLLAPSED,
                onExpandedComposed = { compositions.incrementAndGet() },
                onExpandedMeasured = { measurements.incrementAndGet() })
        }
        composeRule.runOnIdle {
            assertTrue("Settings must be prepared before the first drag", compositions.get() > 0)
            assertTrue(measurements.get() > 0)
            compositions.set(0)
            measurements.set(0)
        }
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 1_600f), durationMillis = 700)
        }
        composeRule.waitForIdle()
        assertPanelState(CameraPanelAnchor.EXPANDED)
        composeRule.runOnIdle {
            // Committing the new anchor can update the owner once. Pointer/animation frames
            // must not repeatedly invalidate the large settings body.
            assertTrue("Settings compositions during drag: ${compositions.get()}", compositions.get() <= 1)
            assertTrue("Settings measurements during drag: ${measurements.get()}", measurements.get() <= 1)
        }
    }

    @Test
    fun hiddenSettingsAreNotDisplayedUntilExpanded() {
        setPanelContent()
        composeRule.onNodeWithTag("camera-settings-list").assertIsNotDisplayed()
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performClick()
        composeRule.onNodeWithTag("camera-settings-list").assertIsDisplayed()
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performClick()
        composeRule.onNodeWithTag("camera-settings-list").assertIsNotDisplayed()
    }

    @Test
    fun compactPanelBlocksTouchesToPreparedSettings() {
        val taps = AtomicInteger()
        composeRule.setContent {
            PanelHarness(initial = CameraPanelAnchor.COLLAPSED,
                onExpandedAction = { taps.incrementAndGet() })
        }
        val header = composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle)
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsPanel).performTouchInput {
            click(Offset(center.x, header.height + 24.dp.toPx()))
        }
        composeRule.runOnIdle { assertEquals(0, taps.get()) }
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performClick()
        composeRule.onNodeWithText("Проверочная настройка").performClick()
        composeRule.runOnIdle { assertEquals(1, taps.get()) }
    }

    @Test
    fun preparedSettingsDoNotIncreaseTheCollapsedPanelHeight() {
        setPanelContent()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The collapsed handle must remain in the lower half", header.top > root.center.y)
    }

    @Test
    fun draggingCompactContentContinuesThroughTheBodyTransition() {
        setPanelContent()
        composeRule.onNodeWithText("Снять").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 1_600f), durationMillis = 700)
        }
        composeRule.waitForIdle()
        assertPanelState(CameraPanelAnchor.EXPANDED)
    }

    private fun setPanelContent(
        initial: CameraPanelAnchor = CameraPanelAnchor.COLLAPSED,
        onNavigateBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            PanelHarness(initial = initial, onNavigateBack = onNavigateBack)
        }
    }

    private fun assertPanelState(anchor: CameraPanelAnchor) {
        composeRule.onNodeWithTag(AstroTestTags.CameraSettingsPanel).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                anchor.name
            )
        )
    }
}

@androidx.compose.runtime.Composable
private fun PanelHarness(
    initial: CameraPanelAnchor,
    onNavigateBack: () -> Unit = {},
    onExpandedComposed: () -> Unit = {},
    onExpandedMeasured: () -> Unit = {},
    onExpandedAction: () -> Unit = {}
) {
    var anchor by rememberCameraPanelAnchor(initial)
    BackHandler {
        val collapsed = cameraPanelBackTarget(anchor)
        if (collapsed != null) anchor = collapsed else onNavigateBack()
    }
    AstroPhotoTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            CameraSettingsPanel(
                anchor = anchor,
                onAnchorChanged = { anchor = it },
                summary = "JPEG · 1 с · ISO 800 · ∞",
                collapsedContent = {
                    Text("Снять", modifier = Modifier.fillMaxWidth())
                },
                expandedContent = { scrollState ->
                    SideEffect { onExpandedComposed() }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("camera-settings-list")
                            .layout { measurable, constraints ->
                                onExpandedMeasured()
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                            }
                            .verticalScroll(scrollState)
                    ) {
                        Button(onClick = onExpandedAction, modifier = Modifier.fillMaxWidth()) {
                            Text("Проверочная настройка")
                        }
                        repeat(30) { index ->
                            Text("Настройка $index")
                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                }
            )
        }
    }
}
