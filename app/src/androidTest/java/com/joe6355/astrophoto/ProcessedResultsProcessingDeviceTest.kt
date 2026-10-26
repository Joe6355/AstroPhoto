package com.joe6355.astrophoto

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.AstroTestTags
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ProcessedResultsProcessingDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(120)
    @get:Rule(order = 1) val foregroundRule = AstroUiForegroundRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun resultsStayReadOnlyDuringProcessingAndCancellationCleanup() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val folder = "Session_20261027_000000_QA_RELEASE_results_$suffix"
        val name = "guard_$suffix.png"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroPhoto/$folder/Processed/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var job: Job? = null
        try {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff203040.toInt())
                requireNotNull(resolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val before = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            val session = SessionSummary(folder, "QA guard", "Pictures/AstroPhoto/$folder/",
                System.currentTimeMillis(), 0, 0, before.size.toLong(), "")
            compose.setContent { AstroPhotoTheme { ProcessedResultsScreen(session, onBack = {}) } }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Пресеты").fetchSemanticsNodes().any {
                    !it.config.contains(SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithText("Пресеты").assertIsEnabled()
            compose.runOnIdle {
                job = SessionProcessingCoordinator.start(context, folder, "QA guard", "QA only", 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
                assertNotNull(job)
            }
            compose.waitForIdle()
            compose.onNodeWithText("Пресеты").assertIsNotEnabled()
            compose.onNodeWithTag(AstroTestTags.ResultActionsMenu).performScrollTo().performClick()
            for (action in listOf("Редактировать", "Экспорт / поделиться", "Переименовать", "Удалить")) {
                compose.onNodeWithText(action).assertIsNotEnabled()
            }
            pressBack()
            compose.onNodeWithText("Открыть").assertIsEnabled().performScrollTo().performClick()
            compose.onNodeWithText("Поделиться").assertIsNotEnabled()
            pressBack()
            compose.runOnIdle { SessionProcessingCoordinator.cancel(folder) }
            withTimeout(5_000) { cleanupStarted.await() }
            compose.waitForIdle()
            assertTrue(SessionProcessingCoordinator.isActive(folder))
            compose.onNodeWithText("Пресеты").assertIsNotEnabled()
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { requireNotNull(job).join() }
            compose.waitForIdle()
            compose.onNodeWithText("Пресеты").assertIsEnabled()
            compose.onNodeWithTag(AstroTestTags.ResultActionsMenu).performScrollTo().performClick()
            for (action in listOf("Редактировать", "Экспорт / поделиться", "Переименовать", "Удалить")) {
                compose.onNodeWithText(action).assertIsEnabled()
            }
            pressBack()
            assertArrayEquals(before, requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() })
        } finally {
            releaseCleanup.complete(Unit)
            SessionProcessingCoordinator.cancel(folder)
            withTimeout(10_000) { job?.join() }
            // Delete only the synthetic PNG created above, never an existing result.
            resolver.delete(uri, null, null)
            ProcessingRecoveryStore(context).markFinished(folder)
        }
    }
}
