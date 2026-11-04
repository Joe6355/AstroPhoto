package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Separate emulator-only UI acceptance; fixtures never enter a user's sessions. */
class PhotoComparisonDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(second: Boolean): File {
        val file = File.createTempFile("qa-comparison-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            for (x in row.indices) {
                val horizontal = 60 + 120 * x / bitmap.width
                row[x] = if (second) Color.rgb(8, 30 + 100 * y / bitmap.height, horizontal)
                    else Color.rgb(horizontal, 30 + 100 * y / bitmap.height, 8)
            }
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun image(file: File) = ComparisonImage(file.name, file.absolutePath, null, file.absolutePath, null)
    private fun ready() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("photo-resolution").fetchSemanticsNodes().let { nodes ->
                nodes.size == 1 && nodes.single().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Детали загружены"
            }
        }
    }
    private fun resolution() = compose.onNodeWithTag("photo-resolution").fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { it.text }
    private fun pixel(): Int {
        val bitmap = compose.onNodeWithTag("photo-viewport").captureToImage().asAndroidBitmap()
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }
    private fun screenshot(name: String) {
        val folder = File(context.cacheDir, "photo-comparison-20261104").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            check(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun withPair(fontScale: Float = 1f, block: () -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("comparisonAudit") == "true")
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        require(kotlin.math.abs(context.resources.configuration.fontScale - fontScale) < 0.02f) {
            "Run this dialog test with system font_scale=$fontScale on the emulator"
        }
        val first = fixture(false)
        val second = fixture(true)
        try {
            val hashes = listOf(hash(first), hash(second))
            compose.setContent {
                AstroPhotoTheme {
                    ProcessedComparisonViewer(image(first), image(second), onDismiss = {})
                }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Масштаб, участок и подсветка общие.")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val foreground = layouts.single().layoutInput.style.color.luminance()
            val dialog = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            val background = ComposeColor(dialog.getPixel(5, 5)).luminance()
            val contrast = (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f)
            assertTrue("Comparison hint must be readable against its own background: $contrast", contrast >= 4.5f)
            block()
            assertEquals(hashes, listOf(hash(first), hash(second)))
        } finally { first.delete(); second.delete() }
    }

    @Test fun fullFrameSwitchKeepsZoomPanAndDisplayLightAndUsesOnlyVisibleGestures() = withPair {
        compose.onNodeWithText("Фото 1").performClick()
        ready()
        val fit = resolution()
        // The hidden second image is above the first in the draw tree. It must not intercept this gesture.
        compose.onNodeWithTag("photo-viewport").performTouchInput { doubleClick(center) }
        ready()
        assertNotEquals("Double tap on first photo must change scale", fit, resolution())
        compose.onNodeWithTag("photo-viewport").performTouchInput {
            swipe(center, center + Offset(90f, 70f), 300)
        }
        ready()
        compose.onNodeWithText("Подсветка").performClick()
        // Keep both coordinate channels below clipping so the pixel comparison measures panning.
        compose.onNodeWithTag("photo-exposure").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.onNodeWithText("+0.5 EV").assertIsDisplayed()
        ready()
        val scale = resolution()
        val first = pixel()
        assertTrue(Color.red(first) > Color.blue(first))
        screenshot("first-zoomed")
        repeat(3) {
            compose.onNodeWithText("Фото 2").performClick()
            ready()
            assertEquals(scale, resolution())
            val second = pixel()
            assertTrue(Color.blue(second) > Color.red(second))
            assertTrue("Same source x after switching", kotlin.math.abs(Color.red(first) - Color.blue(second)) <= 1)
            assertTrue("Same source y and exposure after switching", kotlin.math.abs(Color.green(first) - Color.green(second)) <= 1)
            compose.onNodeWithText("Фото 1").performClick()
            ready()
            assertEquals(scale, resolution())
            assertEquals(first, pixel())
        }
        compose.onNodeWithText("Фото 2").performClick()
        ready()
        screenshot("second-zoomed")
        compose.onNodeWithText("Вписать").performClick()
        ready()
        val secondFit = resolution()
        compose.onNodeWithTag("photo-viewport").performTouchInput { doubleClick(center) }
        ready()
        assertNotEquals("Second photo must also receive gestures", secondFit, resolution())
        compose.onNodeWithText("Рядом").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("photo-viewport").fetchSemanticsNodes().size == 2 }
        screenshot("side-by-side")
    }

    @Test fun fullFrameControlsRemainAccessibleAtTwoHundredPercent() = withPair(2f) {
        for (label in listOf("Фото 1", "Фото 2")) {
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
            ready()
            val bounds = compose.onNodeWithTag("photo-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("Photograph keeps useful height at 200%: $bounds", bounds.height > 160f)
            screenshot(if (label == "Фото 1") "font200-first" else "font200-second")
        }
        compose.onNodeWithText("Подсветка").performClick()
        compose.waitForIdle()
        screenshot("font200-exposure")
        val litBounds = compose.onNodeWithTag("photo-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Photo must remain usable with 200% text and exposure controls: $litBounds", litBounds.height > 160f)
        compose.onNodeWithText("Файл не меняется").assertIsDisplayed()
        compose.onNodeWithText("Рядом").assertIsDisplayed().performClick()
        compose.onNodeWithText("Подсветка").assertIsDisplayed()
    }
}
