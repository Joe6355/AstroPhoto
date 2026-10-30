package com.joe6355.astrophoto

import android.content.ClipData
import android.content.ClipboardManager
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Uses the real navigation and screens; restores the previous clipboard without exporting it. */
@SdkSuppress(minSdkVersion = 30)
class SupportScreensDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule(MainActivity::class.java)
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun helpVersionSelfCheckAndCopiedDiagnosticsWorkThroughRealNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("supportScreensAudit") == "true")
        val context = composeRule.activity
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        val oldClip = AtomicReference<ClipData?>()
        composeRule.runOnIdle { oldClip.set(clipboard.primaryClip) }
        val sessionBefore = ShootingSessionStore(context).load()
        val probesBefore = probeRows()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val proof = JSONObject().put("version", version).put("package", context.packageName)
        try {
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("Настройки").fetchSemanticsNodes().isNotEmpty()
            }
            click("Настройки")
            expand("О приложении", "Подготовка к съёмке")
            click("Подготовка к съёмке")
            composeRule.onNodeWithText("Помощь").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("MP4")
            closeSoftKeyboard()
            click("MP4-таймлапс")
            composeRule.onNodeWithText("Movies/AstroPhoto", substring = true).assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("zz_no_help_topic_42")
            composeRule.onNodeWithText("По вашему запросу ничего не найдено.").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNodeWithText("Быстрый старт").assertIsDisplayed()
            proof.put("helpSearchAndEmptyReset", true)
            pressBack()

            expand("О приложении", "Открыть сведения о приложении")
            click("Открыть сведения о приложении")
            scrollTo("Версия $version")
            composeRule.onNodeWithText("Версия $version").assertIsDisplayed()
            scrollTo("Дудин С.В.")
            composeRule.onNodeWithText("Дудин С.В.").assertIsDisplayed()
            proof.put("aboutVersionMatchesInstalledPackage", true).put("developerShown", true)

            click("Самопроверка")
            click("Запустить самопроверку")
            scrollTo("Скопировать отчёт")
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText("Скопировать отчёт").fetchSemanticsNodes()
                    .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
            }
            click("Скопировать отчёт")
            composeRule.onNodeWithText("Отчёт самопроверки скопирован").assertIsDisplayed()
            val report = copiedText(clipboard, "AstroPhoto self-check")
            assertTrue(report.startsWith("AstroPhoto self-check\n"))
            assertTrue(report.contains("App version: $version"))
            val statuses = JSONObject()
            for (id in SelfCheckId.entries) {
                val line = report.lineSequence().single { it.startsWith("${id.title}: ") && it.contains(" — ") }
                assertFalse(line, line.startsWith("${id.title}: ${SelfCheckStatus.ERROR.title}"))
                assertFalse(line, line.startsWith("${id.title}: ${SelfCheckStatus.NOT_CHECKED.title}"))
                statuses.put(id.name, line.substringAfter(": ").substringBefore(" — "))
            }
            proof.put("selfCheckStatuses", statuses).put("selfCheckClipboardVerified", true)
            assertEquals(probesBefore, probeRows())
            assertEquals(sessionBefore, ShootingSessionStore(context).load())
            pressBack()
            pressBack()

            expand("Дополнительно", "Диагностика камеры")
            click("Диагностика камеры")
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithText("Скопировать диагностику").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
            }
            click("Скопировать диагностику")
            composeRule.onNodeWithText("Диагностика скопирована").assertIsDisplayed()
            val diagnostics = copiedText(clipboard, "AstroPhoto diagnostics")
            assertTrue(diagnostics.startsWith("AstroPhoto diagnostics\n"))
            assertTrue(diagnostics.length > 200)
            assertTrue(diagnostics.lines().size > 10)
            proof.put("cameraDiagnosticsClipboardVerified", true)
                .put("diagnosticLines", diagnostics.lines().size)
                .put("temporaryProbesRemoved", probeRows() == probesBefore)
                .put("activeSessionUnchanged", ShootingSessionStore(context).load() == sessionBefore)
            pressBack()
        } finally {
            // Keep prior clipboard content in memory only. Do not log or serialize it.
            composeRule.runOnIdle {
                oldClip.get()?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            proof.put("previousClipboardRestored", true)
            val directory = File(context.cacheDir, "support_ui_20261030").apply { mkdirs() }
            File(directory, "proof.json").writeText(proof.toString(2))
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

    private fun copiedText(clipboard: ClipboardManager, label: String): String {
        val value = AtomicReference<String>()
        composeRule.runOnIdle {
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(label, clip.description.label.toString())
            value.set(clip.getItemAt(0).text.toString())
        }
        return value.get()
    }

    private fun probeRows(): Set<Long> {
        val resolver = composeRule.activity.contentResolver
        return buildSet {
            resolver.query(MediaStore.Files.getContentUri("external"), arrayOf("_id"),
                "_display_name LIKE ?", arrayOf("AstroPhoto_selfcheck_%"), null)!!.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
    }
}
