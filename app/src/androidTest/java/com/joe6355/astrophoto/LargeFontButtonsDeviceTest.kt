package com.joe6355.astrophoto

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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
import com.joe6355.astrophoto.ui.AstroPrimaryButton
import com.joe6355.astrophoto.ui.AstroSecondaryButton
import com.joe6355.astrophoto.ui.AstroTextButton
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Tests actual production buttons at 200% text scale without changing system settings. */
class LargeFontButtonsDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(60)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun primaryButtonKeepsFullLabelAtTwoHundredPercent() = checkButton("primary", "Запустить самопроверку")
    @Test fun secondaryButtonKeepsFullLabelAtTwoHundredPercent() = checkButton("secondary", "Открыть сведения о приложении")
    @Test fun textButtonKeepsFullLabelAtTwoHundredPercent() = checkButton("text", "Посмотреть диагностику камеры")

    private fun checkButton(kind: String, label: String) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("visualFontAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val stage = args.getString("visualStage") ?: "run"
        require(stage.matches(Regex("[a-z-]{1,16}")))
        val clicks = AtomicInteger()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AstroPhotoTheme(redNight = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                            val modifier = Modifier.width(240.dp).fillMaxWidth()
                            when (kind) {
                                "primary" -> AstroPrimaryButton(label, { clicks.incrementAndGet() }, modifier)
                                "secondary" -> AstroSecondaryButton(label, { clicks.incrementAndGet() }, modifier)
                                else -> AstroTextButton(label, { clicks.incrementAndGet() }, modifier)
                            }
                        }
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val directory = File(context.cacheDir, "night_font_20261030").apply { mkdirs() }
        File(directory, "$stage-$kind.png").outputStream().use {
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        File(directory, "$stage-$kind.json").writeText(JSONObject()
            .put("button", kind).put("fontScale", 2).put("widthDp", 240)
            .put("lineCount", layout.lineCount).put("visualOverflow", layout.hasVisualOverflow).toString(2))
        assertFalse("Full label must be readable: $label", layout.hasVisualOverflow)
        assertTrue("Long labels must wrap at 200%", layout.lineCount > 1)
        composeRule.onNodeWithText(label).performClick()
        composeRule.runOnIdle { assertEquals(1, clicks.get()) }
    }
}
