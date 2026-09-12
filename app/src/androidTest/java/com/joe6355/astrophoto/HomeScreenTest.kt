package com.joe6355.astrophoto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule(order = 0)
    val timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule(order = 1)
    val foregroundRule = AstroUiForegroundRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

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
        composeRule.onNodeWithText("Подготовка").performClick()
        composeRule.runOnIdle { assertEquals("help", destination) }
        composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
            .performScrollToNode(hasTestTag(AstroTestTags.HomeFooter))
        composeRule.onNodeWithTag(AstroTestTags.HomeFooter).assertIsDisplayed()
        composeRule.onNodeWithText("О приложении").performClick()
        composeRule.runOnIdle { assertEquals("about", destination) }
        composeRule.onNodeWithText("Самопроверка").performClick()
        composeRule.runOnIdle { assertEquals("self-check", destination) }
    }

    @Test
    fun footerIsBelowContentAndNearBottomOfTallViewport() {
        composeRule.setContent {
            AstroPhotoTheme {
                AstroHomeScreen({}, {}, {}, {}, {}, {})
            }
        }

        val home = composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
            .fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag(AstroTestTags.HomeMainContent)
            .fetchSemanticsNode().boundsInRoot
        val footer = composeRule.onNodeWithTag(AstroTestTags.HomeFooter)
            .fetchSemanticsNode().boundsInRoot
        val maximumBottomGap = with(composeRule.density) { 96.dp.toPx() }

        assertTrue(footer.top >= content.bottom)
        assertTrue(home.bottom - footer.bottom <= maximumBottomGap)
    }

    @Test
    fun shortViewportCanScrollToFooter() {
        composeRule.setContent {
            AstroPhotoTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    AstroHomeScreen({}, {}, {}, {}, {}, {})
                }
            }
        }

        composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
            .performScrollToNode(hasTestTag(AstroTestTags.HomeFooter))
        composeRule.onNodeWithTag(AstroTestTags.HomeFooter).assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsPrimaryActionReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                AstroPhotoTheme { AstroHomeScreen({}, {}, {}, {}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
            .performScrollToNode(hasTestTag(AstroTestTags.HomePrimaryAction))
        composeRule.onNodeWithTag(AstroTestTags.HomePrimaryAction).assertIsDisplayed()
    }
}
