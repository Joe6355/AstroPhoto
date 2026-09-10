package com.joe6355.astrophoto

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule(order = 0)
    val timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule(order = 1)
    val foregroundRule = AstroUiForegroundRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test
    fun developerIsShownAsApplicationInformation() {
        composeRule.setContent {
            AstroPhotoTheme {
                AboutScreen(
                    cameraPermissionGranted = true,
                    onRequestCameraPermission = {},
                    onOpenHelp = {},
                    onOpenSettings = {},
                    onOpenSelfCheck = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Разработчик").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Дудин С.В.").performScrollTo().assertIsDisplayed()
    }
}
