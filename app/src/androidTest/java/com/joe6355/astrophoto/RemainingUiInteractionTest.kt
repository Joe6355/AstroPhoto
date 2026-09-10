package com.joe6355.astrophoto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.AstroConfirmationDialog
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemainingUiInteractionTest {
    @get:Rule(order = 0)
    val timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule(order = 1)
    val foregroundRule = AstroUiForegroundRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test
    fun longSessionNameDoesNotSqueezeActiveLabelAtLargeFontScale() {
        val name = "Наблюдения звёздного неба — очень длинное имя активной сессии"
        composeRule.setContent {
            AstroPhotoTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                    Box(Modifier.width(300.dp)) {
                        SessionSummaryCard(SessionSummary("Session_test", name, "Pictures/AstroPhoto/Session_test/",
                            0L, 30, 0, 0, 0, 1024L, ""), active = true, onClick = {})
                    }
                }
            }
        }
        val title = composeRule.onNodeWithText(name, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val active = composeRule.onNodeWithText("Активная", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Active label must be below the title", active.top >= title.bottom)
        assertTrue("Active label must not wrap one letter per line", active.width > active.height * 2)
    }

    @Test
    fun frameActionsStayInOneRowAndUseCorrectCallbacks() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            AstroPhotoTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        FrameActionRow(
                            bad = false,
                            favorite = false,
                            canCrop = true,
                            cropExists = false,
                            onToggleBad = { calls += "bad" },
                            onToggleFavorite = { calls += "favorite" },
                            onCrop = { calls += "crop" }
                        )
                    }
                }
            }
        }

        val bad = composeRule.onNodeWithTag(AstroTestTags.FrameBadAction)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val favorite = composeRule.onNodeWithTag(AstroTestTags.FrameFavoriteAction)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val crop = composeRule.onNodeWithTag(AstroTestTags.FrameCropAction)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(bad.top, favorite.top, 0.5f)
        assertEquals(bad.top, crop.top, 0.5f)
        assertEquals(bad.bottom, favorite.bottom, 0.5f)
        assertEquals(bad.bottom, crop.bottom, 0.5f)

        composeRule.onNodeWithTag(AstroTestTags.FrameBadAction).performClick()
        composeRule.onNodeWithTag(AstroTestTags.FrameFavoriteAction).performClick()
        composeRule.onNodeWithTag(AstroTestTags.FrameCropAction).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("bad", "favorite", "crop"), calls)
        }
    }

    @Test
    fun processingTabsSelectManualModeAndShowDisabledReason() {
        var selected by mutableStateOf(ProcessingUiMode.READY)
        composeRule.setContent {
            AstroPhotoTheme {
                Column {
                    ProcessingModeSelector(
                        selected = selected,
                        onSelected = { selected = it },
                        enabled = true
                    )
                    ProcessingProfileAvailability(
                        unavailableReason = "нужно минимум 5 кадров",
                        availableFrames = 2
                    )
                }
            }
        }

        composeRule.onNodeWithTag(AstroTestTags.ProcessingModeTabs).assertIsDisplayed()
        composeRule.onNodeWithText("Ручная обработка").performClick()
        composeRule.runOnIdle { assertEquals(ProcessingUiMode.MANUAL, selected) }
        composeRule.onNodeWithText("Недоступно: нужно минимум 5 кадров")
            .assertIsDisplayed()
    }

    @Test
    fun resultActionsOpenCorrectCommands() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            AstroPhotoTheme {
                ProcessedResultActions(
                    canOpen = true,
                    canEdit = true,
                    onOpen = { calls += "open" },
                    onEdit = { calls += "edit" },
                    onExport = { calls += "export" },
                    onRename = { calls += "rename" },
                    onDelete = { calls += "delete" }
                )
            }
        }

        composeRule.onNodeWithText("Открыть").performClick()
        composeRule.onNodeWithText("Пресеты").performClick()
        composeRule.onNodeWithTag(AstroTestTags.ResultActionsMenu).performClick()
        composeRule.onNodeWithText("Редактировать").performClick()
        composeRule.onNodeWithTag(AstroTestTags.ResultActionsMenu).performClick()
        composeRule.onNodeWithText("Экспорт / поделиться").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("open", "edit", "edit", "export"), calls)
        }
    }

    @Test
    fun confirmationDialogUsesSeparateConfirmAndDismissCallbacks() {
        var action = ""
        composeRule.setContent {
            AstroPhotoTheme {
                AstroConfirmationDialog(
                    title = "Удалить результат?",
                    message = "Действие нельзя отменить",
                    confirmText = "Удалить",
                    onConfirm = { action = "confirm" },
                    onDismiss = { action = "dismiss" }
                )
            }
        }

        composeRule.onNodeWithText("Удалить").performClick()
        composeRule.runOnIdle { assertEquals("confirm", action) }
    }
}
