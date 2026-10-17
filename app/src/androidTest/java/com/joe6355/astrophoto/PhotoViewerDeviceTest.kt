package com.joe6355.astrophoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoViewerDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order = 1) val foregroundRule = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): File {
        val file = File.createTempFile("qa-viewer-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(2, 3, 4))
        // Single source pixels and alternating one-pixel lines disappear in thumbnails.
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (x % 17 == 0 || (x % 43 == 1 && y % 37 == 1))
                bitmap.setPixel(x, y, Color.rgb(220, 170, 130))
        }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    private fun reference(file: File) = ImageSourceReference(file.name, null, null, file.absolutePath)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun originalPngPixelsSurviveNativeAndHigherZoom() {
        val file = fixture()
        try {
            val before = hash(file)
            val original = BitmapFactory.decodeFile(file.absolutePath)
            PhotoRegionSource.open(context, reference(file)).use { source ->
                val request = photoRegionRequest(Rect(1700f, 1200f, 2200f, 1700f), 4096, 3072, 1f)
                val decoded = source.decode(request)
                assertEquals(1, decoded.sample)
                for (y in 0 until decoded.bitmap.height) for (x in 0 until decoded.bitmap.width) {
                    assertEquals("Source pixel $x,$y", original.getPixel(x + request.left, y + request.top),
                        decoded.bitmap.getPixel(x, y))
                }
                assertTrue(decoded.bitmap.allocationByteCount <= MAX_PHOTO_REGION_PIXELS * 4)
                decoded.bitmap.recycle()
            }
            original.recycle()
            assertEquals(before, hash(file))
        } finally { file.delete() }
    }

    @Test fun allExifOrientationsIncludeMirrorsAndPreserveTheCorners() {
        val width = 40; val height = 30
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.BLACK)
        val canvas = Canvas(input)
        val paint = Paint()
        for ((rect, color) in listOf(
            android.graphics.Rect(0, 0, 20, 15) to Color.RED,
            android.graphics.Rect(20, 0, 40, 15) to Color.GREEN,
            android.graphics.Rect(0, 15, 20, 30) to Color.BLUE,
            android.graphics.Rect(20, 15, 40, 30) to Color.YELLOW)) {
            paint.color = color; canvas.drawRect(rect, paint)
        }
        val expectedTopLeft = listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE,
            Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN)
        for (orientation in 1..8) {
            val file = File.createTempFile("qa-viewer-exif-", ".jpg", context.cacheDir)
            try {
                file.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                }
                PhotoRegionSource.open(context, reference(file)).use { source ->
                    val expectedSize = if (orientation in 5..8) IntSize(height, width) else IntSize(width, height)
                    assertEquals(expectedSize, source.orientedSize)
                    val tile = source.decode(PhotoRegionRequest(0, 0, width, height, 1))
                    val displayed = Bitmap.createBitmap(expectedSize.width, expectedSize.height, Bitmap.Config.ARGB_8888)
                    Canvas(displayed).apply { concat(source.orientationMatrix); drawBitmap(tile.bitmap, 0f, 0f, null) }
                    val actual = displayed.getPixel(5, 5)
                    val expected = expectedTopLeft[orientation - 1]
                    for (channel in listOf(Color::red, Color::green, Color::blue))
                        assertTrue("EXIF $orientation", kotlin.math.abs(channel(actual) - channel(expected)) <= 5)
                    displayed.recycle(); tile.bitmap.recycle()
                }
            } finally { file.delete() }
        }
        input.recycle()
    }

    @Test fun screenShowsIndividualSourcePixelsAtOneToOneAndBrighteningDoesNotWriteTheFile() {
        val file = fixture()
        try {
            val before = hash(file)
            compose.setContent { AstroPhotoTheme { PhotoViewer(reference(file), Modifier.fillMaxSize()) } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("1:1").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("1:1").performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("photo-resolution").fetchSemanticsNodes().any {
                    it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { "" } == "Детали загружены" &&
                        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { text -> text.text.endsWith("100%") }
                }
            }
            val rendered = compose.onNodeWithTag("photo-viewport").captureToImage().asAndroidBitmap()
            val row = IntArray(rendered.width)
            rendered.getPixels(row, 0, row.size, 0, rendered.height / 2, row.size, 1)
            val bright = row.count { Color.red(it) >= 200 }
            val dark = row.count { Color.red(it) < 10 }
            assertTrue("Thin source lines must remain visible: $bright", bright >= row.size / 20)
            assertTrue("No blur between source lines: $dark", dark >= row.size * 8 / 10)
            compose.onNodeWithText("Подсветка").performClick()
            compose.onNodeWithTag("photo-resolution").assertTextContains("100%", substring = true)
            val brighter = compose.onNodeWithTag("photo-viewport").captureToImage().asAndroidBitmap()
            assertTrue("Dark source pixels must become visible", Color.red(brighter.getPixel(30, brighter.height / 2)) >
                Color.red(rendered.getPixel(30, rendered.height / 2)))
            compose.onNodeWithText("Вписать").performClick()
            compose.onNodeWithTag("photo-viewport").performTouchInput { doubleClick(center) }
            assertEquals(before, hash(file))
        } finally { file.delete() }
    }
}
