package com.joe6355.astrophoto

import android.content.pm.ActivityInfo
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.joe6355.astrophoto.ui.AstroTestTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real app and its camera View, rather than a separate orientation test host. */
class MainActivityRotationTest {
    @get:Rule(order = 0)
    val timeout = org.junit.rules.Timeout.seconds(90)

    @get:Rule(order = 1)
    val foregroundRule = AstroUiForegroundRule(MainActivity::class.java)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun sessionBrowserStaysOpenAcrossBothLandscapeDirections() {
        openTab("Съёмки")
        composeRule.onNodeWithText("Ваши съёмки").assertIsDisplayed()
        rotateWithoutRestart {
            composeRule.onNodeWithText("Ваши съёмки").assertIsDisplayed()
        }
    }

    @Test
    fun cameraKeepsPreviewAndExpandedSettingsAcrossRotation() {
        openTab("Камера")
        if (composeRule.onNodeWithTag(AstroTestTags.CameraSettingsPanel).fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] != CameraPanelAnchor.EXPANDED.name) {
            composeRule.onNodeWithTag(AstroTestTags.CameraSettingsHandle).performClick()
        }
        val preview = composeRule.runOnIdle {
            requireNotNull(findPreview(composeRule.activity.window.decorView))
        }
        composeRule.waitUntil(15_000) { preview.isAvailable }
        rotateWithoutRestart {
            composeRule.onNodeWithTag(AstroTestTags.CameraSettingsPanel).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription, CameraPanelAnchor.EXPANDED.name
                )
            )
            composeRule.runOnIdle {
                assertSame(preview, findPreview(composeRule.activity.window.decorView))
                assertTrue(preview.isAvailable)
            }
        }
    }

    private fun openTab(label: String) {
        // The launch splash uses a real coroutine delay, independent of the Compose test clock.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Съёмки").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText(label) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    }

    private fun rotateWithoutRestart(assertScreen: () -> Unit) {
        val activity = composeRule.activity
        val originalOrientation = activity.requestedOrientation
        try {
            for ((orientation, rotation) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Surface.ROTATION_90,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to Surface.ROTATION_270,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Surface.ROTATION_0
            )) {
                composeRule.runOnIdle { activity.requestedOrientation = orientation }
                composeRule.waitUntil(10_000) {
                    @Suppress("DEPRECATION")
                    activity.windowManager.defaultDisplay.rotation == rotation
                }
                composeRule.waitForIdle()
                composeRule.runOnIdle {
                    assertFalse("Rotation must not destroy the activity", activity.isDestroyed)
                    assertSame(activity, composeRule.activity)
                }
                assertScreen()
            }
        } finally {
            composeRule.runOnIdle { activity.requestedOrientation = originalOrientation }
        }
    }

    private fun findPreview(view: View): CameraPreviewView? {
        if (view is CameraPreviewView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPreview(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
