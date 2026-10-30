package com.joe6355.astrophoto

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.AstroTestTags
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Read-only screen visit under an explicitly supplied system font scale; no shutter or save action. */
@SdkSuppress(minSdkVersion = 30)
class NightLargeFontNavigationDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule(MainActivity::class.java)
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun redThemeAndTwoHundredPercentTextKeepMainScreensReachable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nightNavigationAudit") == "true")
        val context = composeRule.activity
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        assertTrue("Driver must supply 200% system text", context.resources.configuration.fontScale >= 1.99f)
        val preferences = context.getSharedPreferences("astrophoto_camera_settings", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val sessionBefore = ShootingSessionStore(context).load()
        val proof = JSONObject().put("fontScale", context.resources.configuration.fontScale)
        try {
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("Настройки").fetchSemanticsNodes().isNotEmpty()
            }
            click("Настройки")
            expand("Интерфейс", "Красная")
            click("Красная")
            composeRule.runOnIdle { assertEquals(AppThemeMode.RED_NIGHT.name, CameraSettingsStore(context).load().themeMode) }
            screenshot("settings")
            proof.put("redThemeSelectedThroughSettings", true)
            pressBack()

            composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
                .performScrollToNode(hasTestTag(AstroTestTags.HomePrimaryAction))
            composeRule.onNodeWithTag(AstroTestTags.HomePrimaryAction).assertIsDisplayed()
            screenshot("home")
            click("Настройки")
            expand("О приложении", "Подготовка к съёмке")
            click("Подготовка к съёмке")
            composeRule.onNode(hasSetTextAction()).performTextReplacement("ISO")
            closeSoftKeyboard()
            composeRule.onNode(hasText("ISO") and !hasSetTextAction()).performClick()
            screenshot("help")
            pressBack()

            expand("О приложении", "Открыть сведения о приложении")
            click("Открыть сведения о приложении")
            scrollTo("Дудин С.В.")
            composeRule.onNodeWithText("Дудин С.В.").assertIsDisplayed()
            screenshot("about")
            pressBack()
            pressBack()

            composeRule.onNode(hasText("Съёмки") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("sessions-list").performScrollToNode(hasText("QA_NATIVE_STORAGE"))
            val fixture = composeRule.onAllNodesWithText("QA_NATIVE_STORAGE")[0]
            fixture.assertIsDisplayed()
            screenshot("session-card", fixture)
            fixture.performClick()
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag("session-details-list").fetchSemanticsNodes().isNotEmpty()
            }
            screenshot("session-details")

            composeRule.onNode(hasText("Камера") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithTag(AstroTestTags.CameraCapture).fetchSemanticsNodes().isNotEmpty()
            }
            val panels = composeRule.onAllNodesWithTag(AstroTestTags.CameraSettingsPanel).fetchSemanticsNodes()
            if (panels.singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) == CameraPanelAnchor.EXPANDED.name) {
                pressBack()
            }
            composeRule.onNodeWithTag(AstroTestTags.CameraCapture).assertIsDisplayed()
            // Capture only the action control, never the live camera preview.
            screenshot("camera-action", composeRule.onNodeWithTag(AstroTestTags.CameraCapture))
            assertEquals(sessionBefore, ShootingSessionStore(context).load())
            proof.put("visited", JSONArray(listOf("settings", "home", "help", "about", "session-card", "session-details", "camera-action")))
                .put("cameraShutterNotPressed", true).put("activeSessionUnchanged", true)
        } finally {
            val editor = preferences.edit().clear()
            before.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            check(editor.commit())
            proof.put("originalPreferenceValuesRestored", preferences.all == before)
            File(directory(), "navigation-proof.json").writeText(proof.toString(2))
        }
    }

    private fun expand(title: String, child: String) {
        scrollTo(title)
        if (composeRule.onAllNodesWithText(child).fetchSemanticsNodes().isEmpty()) click(title)
        scrollTo(child)
    }

    private fun scrollTo(text: String) {
        if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        }
        val node = composeRule.onNodeWithText(text)
        try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }
    }

    private fun click(text: String) {
        scrollTo(text)
        composeRule.onNodeWithText(text).performClick()
    }

    private fun directory() = File(composeRule.activity.cacheDir, "night_font_20261030").apply { mkdirs() }

    private fun screenshot(name: String, node: SemanticsNodeInteraction = composeRule.onRoot()) {
        File(directory(), "screen-$name.png").outputStream().use {
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
