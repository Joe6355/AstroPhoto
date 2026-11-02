package com.joe6355.astrophoto

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import androidx.test.espresso.Espresso.pressBack
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class CaptureStorageWarningDialogTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(60)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()
    private val captures = AtomicInteger()
    private val cancelled = AtomicInteger()

    @Test fun largeTextKeepsAdviceScrollableAndCancelReachable() {
        showWarning(StorageSpaceInfo(100L * MIB, 900L * MIB), fontScale = 2f)
        composeRule.onNodeWithText("Начать всё равно").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").assertIsDisplayed()
        val evidence = if (InstrumentationRegistry.getArguments().getString("resourceUiAudit") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.joe6355.astrophoto.qa20261017")
            File(context.cacheDir, "resource-ui-audit/${UUID.randomUUID()}").apply { check(mkdirs()) }
        } else null
        fun screenshot(name: String) {
            if (evidence == null) return
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try { File(evidence, "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        screenshot("warning-top")
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        composeRule.onNodeWithText("Отмена").assertIsDisplayed()
        screenshot("warning-advice")
        composeRule.onNodeWithText("Отмена").performClick()
        assertClosed(captures = 0, cancelled = 1)
        evidence?.let { directory ->
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "QA_RESOURCE_UI ${directory.name}\n")
            })
        }
    }

    @Test fun cancelOnCriticalStorageDoesNotStartCapture() {
        showWarning(StorageSpaceInfo(100L * MIB, 6L * MIB))
        composeRule.onNodeWithText("Может не хватить места для снимка").assertIsDisplayed()
        composeRule.onNodeWithText("Свободного места меньше 500 MB.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").performClick()
        assertClosed(captures = 0, cancelled = 1)
    }

    @Test fun explicitContinueApprovesOneCapture() {
        showWarning(StorageSpaceInfo(600L * MIB, 900L * MIB))
        composeRule.onNodeWithText("Свободного места меньше 500 MB.", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Начать всё равно").performClick()
        assertClosed(captures = 1, cancelled = 0)
    }

    @Test fun backDismissesWarningWithoutStartingCapture() {
        showWarning(StorageSpaceInfo(100L * MIB, 6L * MIB))
        composeRule.onNodeWithText("Может не хватить места для снимка").assertIsDisplayed()
        pressBack()
        assertClosed(captures = 0, cancelled = 1)
    }

    private fun showWarning(info: StorageSpaceInfo, fontScale: Float? = null) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                var visible by remember { mutableStateOf(true) }
                AstroPhotoTheme {
                    if (visible) CaptureStorageWarningDialog(info, "снимка", {
                        visible = false
                        cancelled.incrementAndGet()
                    }, {
                        visible = false
                        captures.incrementAndGet()
                    })
                }
            }
        }
    }

    private fun assertClosed(captures: Int, cancelled: Int) {
        composeRule.onNodeWithText("Может не хватить места для снимка").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(captures, this.captures.get())
            assertEquals(cancelled, this.cancelled.get())
        }
    }

    private companion object { const val MIB = 1024L * 1024L }
}
