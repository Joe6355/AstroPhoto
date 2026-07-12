package com.example.astrophoto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.theme.AstroPhotoTheme
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CameraSettingsPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    onNavigateBack: () -> Unit = {}
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("camera-settings-list")
                            .verticalScroll(scrollState)
                    ) {
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
