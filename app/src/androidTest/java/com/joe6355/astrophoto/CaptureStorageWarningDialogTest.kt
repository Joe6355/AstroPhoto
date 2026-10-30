package com.joe6355.astrophoto

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun showWarning(info: StorageSpaceInfo) {
        composeRule.setContent {
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

    private fun assertClosed(captures: Int, cancelled: Int) {
        composeRule.onNodeWithText("Может не хватить места для снимка").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(captures, this.captures.get())
            assertEquals(cancelled, this.cancelled.get())
        }
    }

    private companion object { const val MIB = 1024L * 1024L }
}
