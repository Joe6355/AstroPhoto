package com.joe6355.astrophoto

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, read-only real photos. Screenshots stay in the QA app's private cache. */
class RealPhotoViewerDeviceAuditTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(300)
    @get:Rule(order = 1) val foregroundRule = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun viewOriginalsAndExistingProcessedFiles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit real-photo viewer audit only", args.getString("realViewerAudit") == "true")
        val prefixes = requireNotNull(args.getString("viewerSessionPrefixes")).split(',')
        require(prefixes.size in 1..10 && prefixes.all { it.matches(Regex("Session_[0-9_]+")) })
        val context = instrumentation.targetContext
        val sessions = SessionBrowserRepository(context).loadSessions()
        val sources = mutableListOf<Pair<String, ImageSourceReference>>()
        for (prefix in prefixes) {
            val session = sessions.single { it.folderName.startsWith(prefix) }
            val frame = SessionFramesRepository(context).loadFrames(session)
                .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }.minBy { it.fileName }
            sources += prefix + "-original" to imageSourceReference(frame.fileName, frame.displayPath,
                null, frame.contentUri, frame.filePath)
            val relative = "Pictures/AstroPhoto/${session.folderName}/Processed/"
            val results = mutableListOf<ImageSourceReference>()
            context.contentResolver.query(processedImagesCollection(),
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH}=?", arrayOf(relative), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name.endsWith(".png", true) || name.endsWith(".jpg", true)) results +=
                        ImageSourceReference(name, relative,
                            ContentUris.withAppendedId(processedImagesCollection(), cursor.getLong(0)).toString(), null)
                }
            }
            // Include an Edited file when present and a stack, so both result paths are covered.
            results.sortedBy { it.displayName }.firstOrNull { it.displayName.startsWith("Edited_") }?.let {
                sources += prefix + "-edited" to it
            }
            results.sortedBy { it.displayName }.firstOrNull { !it.displayName.startsWith("Edited_") }?.let {
                sources += prefix + "-processed" to it
            }
        }
        val current = mutableStateOf(sources.first().second)
        compose.setContent { AstroPhotoTheme { PhotoViewer(current.value, Modifier.fillMaxSize()) } }
        val output = File(context.cacheDir, "viewer-audit").apply { mkdirs() }
        for ((index, entry) in sources.withIndex()) {
            val (label, reference) = entry
            compose.runOnIdle { current.value = reference }
            compose.waitUntil(15000) { compose.onAllNodesWithText("1:1").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("1:1").performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("photo-resolution").fetchSemanticsNodes().any {
                    it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Детали загружены" &&
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text.endsWith("100%") }
                }
            }
            compose.onNodeWithTag("photo-viewport").captureToImage().asAndroidBitmap().let { bitmap ->
                File(output, "$label-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("Подсветка").performClick()
            compose.onNodeWithTag("photo-viewport").captureToImage().asAndroidBitmap().let { bitmap ->
                File(output, "$label-bright.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("Вписать").performClick()
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "VIEWER_PASS index=$index label=$label file=${reference.displayName}\n")
            })
        }
        assertTrue(sources.size >= prefixes.size)
    }
}
