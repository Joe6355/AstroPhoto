package com.joe6355.astrophoto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
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
                    onOpenSettings = {}
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
                    onOpenSettings = { destination = "settings" }
                )
            }
        }

        composeRule.onNodeWithTag(AstroTestTags.HomeSecondaryNavigation)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Настройки").performClick()
        composeRule.runOnIdle { assertEquals("settings", destination) }
        composeRule.onNodeWithText("Камера").performClick()
        composeRule.onNodeWithText("Обработка").performClick()
        composeRule.runOnIdle { assertEquals("sessions", destination) }
        composeRule.onNodeWithText("Подготовка").assertDoesNotExist()
        composeRule.onNodeWithText("О приложении").assertDoesNotExist()
        composeRule.onNodeWithText("Самопроверка").assertDoesNotExist()
    }

    @Test
    fun sceneSwitchStartsWithMeteorsAndCyclesThroughAllFiveVariants() {
        composeRule.setContent { AstroPhotoTheme { AstroHomeScreen({}, {}, {}) } }
        composeRule.onNodeWithText("Метеоры ›").assertIsDisplayed()
        composeRule.onNodeWithTag("home-scene-switch").performClick()
        composeRule.onNodeWithText("Созвездия ›").assertIsDisplayed()
        composeRule.onNodeWithTag("home-scene-switch").performClick()
        composeRule.onNodeWithText("Затмение ›").assertIsDisplayed()
        composeRule.onNodeWithTag("home-scene-switch").performClick()
        composeRule.onNodeWithText("Орбиты ›").assertIsDisplayed()
        composeRule.onNodeWithTag("home-scene-switch").performClick()
        composeRule.onNodeWithText("Млечный путь ›").assertIsDisplayed()
        composeRule.onNodeWithTag("home-scene-switch").performClick()
        composeRule.onNodeWithText("Метеоры ›").assertIsDisplayed()
    }

    @Test
    fun shortViewportCanScrollToSessions() {
        composeRule.setContent {
            AstroPhotoTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    AstroHomeScreen({}, {}, {})
                }
            }
        }

        composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
            .performScrollToNode(hasTestTag(AstroTestTags.HomeSessions))
        composeRule.onNodeWithTag(AstroTestTags.HomeSessions).assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsPrimaryActionReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                AstroPhotoTheme { AstroHomeScreen({}, {}, {}) }
            }
        }
        composeRule.onNodeWithTag(AstroTestTags.HomeScreen)
            .performScrollToNode(hasTestTag(AstroTestTags.HomePrimaryAction))
        composeRule.onNodeWithTag(AstroTestTags.HomePrimaryAction).assertIsDisplayed()
    }

    @Test
    fun arrivingAdDoesNotMoveTheCameraActionAndAppearsAfterSessions() {
        var loaded by mutableStateOf(false)
        composeRule.setContent {
            AstroPhotoTheme {
                Box(Modifier.fillMaxWidth().height(700.dp)) {
                    AstroHomeScreen({}, {}, {}, nativeAdContent = if (loaded) {
                        { Box(Modifier.fillMaxWidth().height(140.dp).testTag("test-loaded-ad")) }
                    } else null)
                }
            }
        }
        val before = composeRule.onNodeWithTag(AstroTestTags.HomePrimaryAction).fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { loaded = true }
        val after = composeRule.onNodeWithTag(AstroTestTags.HomePrimaryAction).fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 0.5f)
        composeRule.onNodeWithTag(AstroTestTags.HomeScreen).performScrollToNode(hasTestTag("test-loaded-ad"))
        composeRule.onNodeWithTag("test-loaded-ad").assertIsDisplayed()
        val ad = composeRule.onNodeWithTag("test-loaded-ad").fetchSemanticsNode().boundsInRoot
        val sessions = composeRule.onNodeWithTag(AstroTestTags.HomeSessions).fetchSemanticsNode().boundsInRoot
        assertTrue(ad.top >= sessions.bottom)
    }
}
