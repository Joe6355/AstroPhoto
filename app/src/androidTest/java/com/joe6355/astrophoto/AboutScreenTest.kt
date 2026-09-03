package com.joe6355.astrophoto

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
