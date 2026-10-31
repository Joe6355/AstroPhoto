package com.joe6355.astrophoto

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.AstroSegmentedControl
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Real radio controls must keep complete words at narrow widths and large text. */
class SegmentedControlFontDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(60)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun themeLabelsAtTwoHundredPercentAnd320Dp() = checkThemes(2f, 320)
    @Test fun themeLabelsAtTwoHundredPercentAnd240Dp() = checkThemes(2f, 240)
    @Test fun themeLabelsAtNormalFontAnd360Dp() = checkThemes(1f, 360)

    private fun checkThemes(fontScale: Float, widthDp: Int) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("segmentedFontAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val stage = args.getString("visualStage") ?: "run"
        require(stage.matches(Regex("[a-z-]{1,16}")))
        val labels = listOf("Светлая", "Тёмная", "Очень тёмная", "Красная")
        val clicks = mutableListOf<String>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AstroPhotoTheme(redNight = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            var selected by remember { mutableStateOf("Красная") }
                            AstroSegmentedControl(labels, selected, { it }, {
                                selected = it
                                clicks.add(it)
                            }, Modifier.width(widthDp.dp))
                        }
                    }
                }
            }
        }
        val directory = File(context.cacheDir, "segmented_font_20261031").apply { mkdirs() }
        val key = "$stage-${fontScale.toInt()}-$widthDp"
        File(directory, "$key.png").outputStream().use {
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val results = JSONArray()
        val failures = mutableListOf<String>()
        labels.forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val lines = (0 until layout.lineCount).map { line ->
                label.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true))
            }
            val splitWord = (0 until layout.lineCount - 1).any { line ->
                val end = layout.getLineEnd(line, visibleEnd = true)
                end in 1 until label.length && !label[end - 1].isWhitespace() && !label[end].isWhitespace()
            }
            // A cached paragraph can be wider than a short Text's wrap-content size.
            // Check painted lines and truncation rather than that paragraph width.
            val clipped = layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines ||
                (0 until layout.lineCount).any { line ->
                    layout.getLineLeft(line) < -1f || layout.getLineRight(line) > layout.size.width + 1f
                }
            results.put(JSONObject().put("label", label).put("lines", JSONArray(lines))
                .put("visualOverflow", layout.hasVisualOverflow).put("splitWord", splitWord)
                .put("clipped", clipped)
                .put("didOverflowHeight", layout.didOverflowHeight).put("didOverflowWidth", layout.didOverflowWidth)
                .put("widthPx", layout.size.width).put("heightPx", layout.size.height)
                .put("paragraphHeightPx", layout.multiParagraph.height)
                .put("paragraphWidthPx", layout.multiParagraph.width))
            if (clipped || splitWord) failures.add(label)
        }
        File(directory, "$key.json").writeText(JSONObject().put("fontScale", fontScale)
            .put("widthDp", widthDp).put("labels", results).toString(2))
        assertTrue("Complete theme words must remain readable: $failures", failures.isEmpty())
        labels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed().performClick().assertIsSelected()
            labels.filter { it != label }.forEach { other ->
                composeRule.onNodeWithText(other).assertIsNotSelected()
            }
        }
        composeRule.runOnIdle { assertEquals(labels, clicks) }
    }
}
