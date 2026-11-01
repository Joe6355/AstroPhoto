package com.joe6355.astrophoto

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.AstroTopBar
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Long, distinguishable names and readable error placement; no existing session is changed. */
class LongTextLayoutDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(65)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()

    private val longName = "Ночная съёмка Млечного Пути у реки — проверка длинного названия и последних слов"
    private val longFile = "Edited_20261101_milky_way_after_stack_full_resolution_quality_check_control_ending.png"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prepare(): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("longTextAudit") == "true")
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val stage = requireNotNull(args.getString("visualStage"))
        require(stage.matches(Regex("[a-z0-9-]{1,20}")))
        return stage
    }

    private fun content(scale: Float = 2f, block: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AstroPhotoTheme(redNight = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(320.dp)) { block() }
                        }
                    }
                }
            }
        }
    }

    private fun layout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun evidence(stage: String, key: String, label: String, result: TextLayoutResult): JSONObject {
        val directory = File(context.cacheDir, "long-text-audit/$stage").apply { mkdirs() }
        File(directory, "$key.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val proof = JSONObject().put("label", label).put("fontScale", if (key == "normal-header") 1 else 2)
            .put("widthDp", 320).put("lines", result.lineCount)
            .put("exceedsMaxLines", result.multiParagraph.didExceedMaxLines)
            .put("ellipsis", (0 until result.lineCount).any(result::isLineEllipsized))
            .put("heightOverflow", result.didOverflowHeight)
            .put("paintedOutsideWidth", (0 until result.lineCount).any {
                result.getLineLeft(it) < -1 || result.getLineRight(it) > result.size.width + 1
            })
        File(directory, "$key.json").writeText(proof.toString(2))
        return proof
    }

    private fun assertComplete(proof: JSONObject) {
        assertFalse(proof.toString(), proof.getBoolean("exceedsMaxLines"))
        assertFalse(proof.toString(), proof.getBoolean("ellipsis"))
        assertFalse(proof.toString(), proof.getBoolean("heightOverflow"))
        assertFalse(proof.toString(), proof.getBoolean("paintedOutsideWidth"))
    }

    private fun assertActionWords(stage: String, key: String, proof: JSONObject) {
        val failures = mutableListOf<String>()
        for (label in listOf("Открыть", "Пресеты")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val text = layout(label)
            val prefix = if (label == "Открыть") "openAction" else "presetAction"
            proof.put(prefix + "Lines", text.lineCount)
            // Cached centered paragraphs can retain a wider alignment offset; measure ink width.
            val clipped = text.didOverflowHeight || text.multiParagraph.didExceedMaxLines ||
                (0 until text.lineCount).any {
                    text.getLineRight(it) - text.getLineLeft(it) > text.size.width + 1
                }
            proof.put(prefix + "Clipped", clipped).put(prefix + "WidthPx", text.size.width)
                .put(prefix + "ParagraphWidthPx", text.multiParagraph.width)
                .put(prefix + "InkWidthPx", text.getLineRight(0) - text.getLineLeft(0))
            File(context.cacheDir, "long-text-audit/$stage/$key.json").writeText(proof.toString(2))
            if (text.lineCount != 1 || clipped || text.isLineEllipsized(0)) failures.add(label)
        }
        File(context.cacheDir, "long-text-audit/$stage/$key-actions.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertTrue("Whole action words must fit: $failures; $proof", failures.isEmpty())
    }

    @Test fun longSessionHeaderKeepsFullNameAndNavigationAtTwoHundredPercent() {
        val stage = prepare()
        var backs = 0; var helps = 0
        content {
            AstroTopBar(longName, onBack = { backs++ }, actions = {
                TextButton(onClick = { helps++ }) { Text("?") }
            })
        }
        val proof = evidence(stage, "session-header", longName, layout(longName))
        compose.onNodeWithText("Назад").assertIsDisplayed().performClick()
        compose.onNodeWithText("?").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(1, helps) }
        assertComplete(proof)
    }

    @Test fun normalFontHeaderRemainsCompactAndNavigationWorks() {
        val stage = prepare()
        var backs = 0
        content(1f) { AstroTopBar("Сессия", onBack = { backs++ }) }
        val proof = evidence(stage, "normal-header", "Сессия", layout("Сессия"))
        assertComplete(proof)
        assertEquals(1, proof.getInt("lines"))
        compose.onNodeWithText("Назад").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun sessionCardShowsFullDistinguishableNameAtTwoHundredPercent() {
        val stage = prepare()
        val session = SessionSummary("Session_QA_LongText_${UUID.randomUUID()}", longName,
            "Pictures/AstroPhoto/QA-nonexistent/", 1L, 0, 0, 0L, "")
        var opens = 0
        content { SessionSummaryCard(session, active = true, onClick = { opens++ }) }
        val proof = evidence(stage, "session-card", longName, layout(longName))
        compose.onNodeWithText(longName).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        assertComplete(proof)
    }

    @Test fun resultCardShowsFullFileNameAtTwoHundredPercent() = resultCase(empty = false)
    @Test fun resultErrorUsesCardWidthAndActionsRemainReachable() = resultCase(empty = true)

    private fun resultCase(empty: Boolean) {
        val stage = prepare()
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val folder = "Session_QA_LongText_${UUID.randomUUID()}"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, longFile)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroPhoto/$folder/Processed/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        try {
            resolver.openOutputStream(uri)!!.use { stream ->
                if (!empty) {
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(0xff283544.toInt())
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    } finally { bitmap.recycle() }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val session = SessionSummary(folder, longName, "Pictures/AstroPhoto/$folder/", 1L, 0, 0, 0L, "")
            content { ProcessedResultsScreen(session, onBack = {}) }
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText(longFile).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(longFile).performScrollTo().assertIsDisplayed()
            if (empty) {
                val message = "Файл не удалось открыть"
                compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
                val proof = evidence(stage, "result-error", message, layout(message))
                val card = compose.onNodeWithTag(AstroTestTags.ResultCard).fetchSemanticsNode().boundsInRoot
                val error = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
                val density = context.resources.displayMetrics.density
                proof.put("messageLeftInsetDp", (error.left - card.left) / density)
                File(context.cacheDir, "long-text-audit/$stage/result-error.json").writeText(proof.toString(2))
                compose.onNodeWithText("Открыть").performScrollTo().assertIsDisplayed()
                assertComplete(proof)
                assertTrue("Error must not be squeezed beside thumbnail: $proof", (error.left - card.left) / density <= 16f)
                assertActionWords(stage, "result-error", proof)
            } else {
                val proof = evidence(stage, "result-name", longFile, layout(longFile))
                compose.onNodeWithText("Открыть").performScrollTo().assertIsDisplayed().assertIsEnabled()
                assertComplete(proof)
                assertActionWords(stage, "result-name", proof)
            }
        } finally {
            // URI comes exclusively from this test's insert; existing user results are untouched.
            check(resolver.delete(uri, null, null) == 1)
        }
    }
}
