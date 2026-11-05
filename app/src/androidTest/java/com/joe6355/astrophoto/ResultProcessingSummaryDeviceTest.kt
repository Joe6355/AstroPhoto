package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.artifactSessionId
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ResultProcessingSummaryDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun guard(font: Float = 1f) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("summaryAudit") == "true")
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        require(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        require(kotlin.math.abs(context.resources.configuration.fontScale - font) < 0.02f)
    }
    private fun originalReport(first: Boolean) = File(context.cacheDir,
        "summary-reader-inputs/series${if (first) 1 else 2}.processing.json").readText()
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private data class Fixture(val directory: File, val session: SessionSummary, val result: ProcessedResult,
        val frames: List<SessionFrame>, val reportFile: File?, val reportText: String)

    private fun fixture(first: Boolean, referenceAvailable: Boolean = true, reportAvailable: Boolean = true): Fixture {
        val folder = "Session_QA_Summary_" + UUID.randomUUID().toString().replace("-", "")
        val directory = File(context.cacheDir, "qa-result-summary-$folder").apply { check(mkdir()) }
        val json = JSONObject(originalReport(first))
        json.put("artifactSessionId", artifactSessionId(folder))
        val name = json.getString("outputPngDisplayName")
        val resultFile = File(directory, name)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 28, 44))
        resultFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val refName = json.getString("selectedReference")
        val firstName = refName.replace(Regex("_[0-9]{3}\\.jpg$"), "_001.jpg")
        val frames = buildList {
            listOf(firstName, refName).distinct().forEachIndexed { index, fileName ->
                if (index == 1 && !referenceAvailable) return@forEachIndexed
                val file = File(directory, fileName)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                add(SessionFrame(file.absolutePath, fileName, SessionFrameCategory.LIGHTS_JPEG, file.length(),
                    index.toLong(), file.absolutePath, null, file.absolutePath))
            }
        }
        bitmap.recycle()
        val report = if (reportAvailable) AppSpecificProcessingReportStore(context).write(folder, name, json.toString()).file else null
        return Fixture(directory, SessionSummary(folder, "QA summary", "Pictures/AstroPhoto/$folder/", 0, frames.size, 0, 0, ""),
            ProcessedResult(resultFile.absolutePath, name, if (first) ProcessedResultType.ENHANCED else ProcessedResultType.DEEP_SKY,
                0, resultFile.length(), resultFile.absolutePath, null, resultFile.absolutePath), frames, report, json.toString())
    }
    private fun withFixture(first: Boolean, referenceAvailable: Boolean = true, reportAvailable: Boolean = true,
        block: (Fixture) -> Unit) {
        val fixture = fixture(first, referenceAvailable, reportAvailable)
        try {
            val before = fixture.directory.listFiles().orEmpty().associate { it.name to hash(it) }
            val reportHash = fixture.reportFile?.let(::hash)
            block(fixture)
            assertEquals(before, fixture.directory.listFiles().orEmpty().associate { it.name to hash(it) })
            if (reportHash != null) assertEquals(reportHash, hash(fixture.reportFile!!))
        } finally {
            AppSpecificProcessingReportStore(context).delete(fixture.session.folderName, fixture.result.fileName)
            check(fixture.directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(fixture.directory.name.startsWith("qa-result-summary-"))
            fixture.directory.deleteRecursively()
        }
    }
    private fun screenshot(name: String, summary: Boolean = false) {
        val directory = File(context.cacheDir, "result-summary-acceptance").apply { mkdirs() }
        val node = if (summary) compose.onNode(isDialog() and hasAnyDescendant(hasTestTag("result-processing-summary")))
            else compose.onNode(isDialog())
        File(directory, "$name.png").outputStream().use {
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun show(fixture: Fixture, summaryLoader: (suspend () -> ResultProcessingSummary?)? = null,
        compared: (SessionFrame) -> Unit = {}) {
        val trace = File(context.cacheDir, "summary-show-trace.txt")
        val traceEnabled = InstrumentationRegistry.getArguments().getString("summaryTrace") == "true"
        val loader = if (!traceEnabled || summaryLoader != null) summaryLoader else suspend {
            trace.appendText("begin ${android.os.SystemClock.elapsedRealtime()} thread=${Thread.currentThread().name}\n")
            val loaded = ProcessedResultReportReader(context).load(fixture.session.folderName, fixture.result.fileName)
            trace.appendText("end ${android.os.SystemClock.elapsedRealtime()} found=${loaded != null}\n")
            loaded
        }
        compose.setContent { AstroPhotoTheme {
            ProcessedResultViewer(fixture.result, fixture.session, fixture.frames, true, { null }, compared, {}, loader)
        } }
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Загрузка сведений…").fetchSemanticsNodes().isEmpty() }
        } catch (failure: Throwable) {
            if (traceEnabled) {
                trace.appendText("timeout ${android.os.SystemClock.elapsedRealtime()} clock=${compose.mainClock.currentTime}\n")
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                    if (thread.name == "main" || thread.name.contains("DefaultDispatcher"))
                        trace.appendText("${thread.name}: ${stack.joinToString("\n")}\n")
                }
                trace.appendText(compose.onNode(isDialog()).printToString())
            }
            throw failure
        }
        compose.onNodeWithTag("open-result-processing-summary").assertIsEnabled()
    }

    @Test fun parsesBothActualReportCopiesAndRejectsUnpairedOrInconsistentData() {
        guard()
        for (first in listOf(true, false)) {
            val text = originalReport(first)
            val json = JSONObject(text)
            val name = json.getString("outputPngDisplayName")
            val sessionId = json.getString("artifactSessionId")
            val summary = requireNotNull(parseResultProcessingSummary(text, name, sessionId))
            assertEquals(if (first) 30 else 10, summary.inputFrames)
            assertEquals(if (first) 12 else 4, summary.acceptedFrames)
            assertEquals(if (first) listOf(12, 5, 1) else listOf(5, 1), summary.exclusions.map { it.count })
            assertEquals(if (first) "SAVED" else "REJECTED", summary.enhancedStatus)
            assertEquals(first, summary.isEnhancedOutput)
            assertNull(parseResultProcessingSummary(text, "Other.png", sessionId))
            assertNull(parseResultProcessingSummary(text, name, "other-session"))
            json.put("acceptedFrameCount", json.getInt("inputFrameCount") + 1)
            assertNull(parseResultProcessingSummary(json.toString(), name, sessionId))
            val longReference = JSONObject(text).put("selectedReference", "x".repeat(513))
            assertNull(parseResultProcessingSummary(longReference.toString(), name, sessionId))
        }
        assertNull(parseResultProcessingSummary("{", "DeepSky.png", "session"))
    }

    @Test fun readsExistingPrivateReportWithoutChangingItAndRejectsWrongIdentity() {
        guard()
        withFixture(first = false) { f ->
            val reader = ProcessedResultReportReader(context)
            val summary = requireNotNull(runBlocking { reader.load(f.session.folderName, f.result.fileName) })
            assertEquals(4, summary.acceptedFrames)
            assertNull(runBlocking { reader.load(f.session.folderName, "Other.png") })
            assertNull(runBlocking { reader.load("../outside", f.result.fileName) })
            assertNull(runBlocking { reader.load(f.session.folderName, "../outside.png") })
        }
    }

    @Test fun viewerShowsTwelveOfThirtyAndComparesToReferenceTwentySeven() {
        guard()
        withFixture(first = true) { f ->
            var compared: SessionFrame? = null
            show(f) { compared = it }
            compose.onNodeWithText("Итог: 12 из 30").assertIsDisplayed()
            compose.onNodeWithText("Сравнить с опорным кадром").assertIsDisplayed()
            screenshot("series1-viewer")
            compose.onNodeWithTag("open-result-processing-summary").performClick()
            compose.onNodeWithText("Использовано 12 из 30 кадров").assertIsDisplayed()
            compose.onNodeWithText("Сохранён Enhanced", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Опорный кадр").performScrollTo()
            screenshot("series1-summary", summary = true)
            compose.onNodeWithText("Закрыть").performClick()
            compose.onNodeWithText("Сравнить с опорным кадром").performClick()
            compose.runOnIdle { assertEquals(f.frames.last().key, compared?.key) }
        }
    }

    @Test fun rejectedEnhancedIsExplainedAndMissingReferenceUsesFirstLight() {
        guard()
        withFixture(first = false, referenceAvailable = false) { f ->
            var compared: SessionFrame? = null
            show(f) { compared = it }
            compose.onNodeWithText("Итог: 4 из 10").assertIsDisplayed()
            compose.onNodeWithTag("open-result-processing-summary").performClick()
            compose.onNodeWithText("Использовано 4 из 10 кадров").assertIsDisplayed()
            compose.onNodeWithText("Enhanced не сохранён", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("series2-summary", summary = true)
            compose.onNodeWithText("Закрыть").performClick()
            compose.onNodeWithText("Сравнить с первым кадром").performClick()
            compose.runOnIdle { assertEquals(f.frames.first().key, compared?.key) }
        }
    }

    @Test fun unavailableReportDoesNotInventCountsAndKeepsFileDetailsAndComparison() {
        guard()
        withFixture(first = false, reportAvailable = false) { f ->
            show(f)
            compose.onNodeWithText("Сравнить с первым кадром").assertIsDisplayed()
            compose.onNodeWithText("Сведения о файле").performClick()
            compose.onNodeWithText("Отчёт обработки для этого файла недоступен", substring = true).assertIsDisplayed()
            screenshot("no-report", summary = true)
            compose.onNodeWithText("Технические сведения").performScrollTo().performClick()
            compose.onNodeWithText("Файл: ${f.result.fileName}", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Закрыть").performClick()
        }
    }

    @Test fun summaryAtSystemTwoHundredPercentScrollsAndCloses() {
        guard(2f)
        withFixture(first = false) { f ->
            show(f)
            compose.onNodeWithTag("open-result-processing-summary").assertIsDisplayed().performClick()
            compose.onNodeWithText("Использовано 4 из 10 кадров").assertIsDisplayed()
            compose.onNodeWithText("Enhanced не сохранён", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("font200-summary", summary = true)
            compose.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
            compose.onNodeWithText("Сравнить с опорным кадром").assertIsDisplayed()
            compose.onNodeWithTag("result-viewer-actions").assertIsDisplayed().performClick()
            compose.onNodeWithText("В галерее").assertIsDisplayed()
            compose.onNodeWithText("Поделиться").assertIsDisplayed()
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
            ).use { it.readBytes() }
            compose.waitUntil(5000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
            screenshot("font200-viewer")
            val viewport = compose.onNodeWithTag("photo-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("Summary button must not remove the photograph: $viewport", viewport.height > 160f)
        }
    }

    @Test fun slowOptionalReportKeepsFallbackUsableAndDoesNotReplaceItWithLateData() {
        guard()
        withFixture(first = true) { f ->
            var compared: SessionFrame? = null
            show(f, summaryLoader = {
                delay(8_000)
                parseResultProcessingSummary(f.reportText, f.result.fileName, artifactSessionId(f.session.folderName))
            }) { compared = it }
            compose.onNodeWithText("Сведения о файле").assertIsDisplayed()
            compose.onNodeWithText("Сравнить с первым кадром").performClick()
            compose.runOnIdle { assertEquals(f.frames.first().key, compared?.key) }
            // Let the delayed report finish; the existing choice must remain explicit and stable.
            Thread.sleep(3500)
            compose.waitForIdle()
            compose.onNodeWithText("Сравнить с первым кадром").assertIsDisplayed()
            compose.onNodeWithText("Итог: 12 из 30").assertDoesNotExist()
            screenshot("slow-report-fallback")
        }
    }
}
