package com.example.astrophoto

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryActionOpensCameraOnce() {
        var cameraClicks = 0
        composeRule.setContent {
            AstroPhotoTheme {
                AstroHomeScreen(
                    onOpenCamera = { cameraClicks++ },
                    onOpenSessions = {},
                    onOpenSettings = {},
                    onOpenHelp = {},
                    onOpenAbout = {},
                    onOpenSelfCheck = {}
                )
            }
        }

        composeRule.onNodeWithTag(AstroTestTags.HomePrimaryAction)
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, cameraClicks) }
    }

    @Test
    fun secondaryNavigationUsesCorrectCallbacks() {
        var destination = ""
        composeRule.setContent {
            AstroPhotoTheme {
                AstroHomeScreen(
                    onOpenCamera = {},
                    onOpenSessions = { destination = "sessions" },
                    onOpenSettings = { destination = "settings" },
                    onOpenHelp = { destination = "help" },
                    onOpenAbout = { destination = "about" },
                    onOpenSelfCheck = { destination = "self-check" }
                )
            }
        }

        composeRule.onNodeWithTag(AstroTestTags.HomeSecondaryNavigation)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Настройки").performClick()
        composeRule.runOnIdle { assertEquals("settings", destination) }
        composeRule.onNodeWithText("Помощь").performClick()
        composeRule.runOnIdle { assertEquals("help", destination) }
    }
}
