package com.joe6355.astrophoto

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in audit: reads existing JPEGs; all output goes into a new, separate QA session. */
@RunWith(AndroidJUnit4::class)
class RealDeviceProcessingAuditTest {
    @Test fun processExistingPhotosWithoutModifyingSourceSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit real-photo audit only", args.getString("realPhotoAudit") == "true")
        val context = instrumentation.targetContext
        val requested = requireNotNull(args.getString("sourceSession"))
        val source = SessionBrowserRepository(context).loadSessions().single { it.folderName == requested }
        val frames = SessionFramesRepository(context).loadFrames(source)
            .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }.sortedBy { it.fileName }
        require(frames.size >= 3)
        val requestedDestination = args.getString("auditDestination")
        require(requestedDestination == null || requestedDestination.matches(Regex("Session_QA_[A-Za-z0-9_-]+")))
        val folder = requestedDestination ?: "Session_QA_${System.currentTimeMillis()}"
        val destination = source.copy(folderName = folder, sessionName = "QA processing audit",
            relativePath = "Pictures/AstroPhoto/$folder/", infoContent = "", createdAtMillis = System.currentTimeMillis())
        val mode = args.getString("auditMode") ?: "average3"
        val requestedCount = when (mode) {
            "average3", "cancel", "invalid" -> 3
            "average10", "deep10", "deepreg10" -> 10
            "median35", "sigma35" -> 35
            "deepAll" -> frames.size
            else -> error("Unknown audit mode: $mode")
        }
        require(frames.size >= requestedCount) {
            "Audit $mode needs $requestedCount frames, found ${frames.size}"
        }
        var lastStage = ""
        var lastReported = 0L
        fun report(message: String) {
            android.util.Log.i("AstroPhotoDeviceQA", message)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
        }
        val progress: suspend (String, Int, Int) -> Unit = { stage, current, total ->
            val now = SystemClock.elapsedRealtime()
            if (stage != lastStage || now - lastReported > 15000) {
                report("QA_PROGRESS mode=$mode stage=$stage current=$current total=$total")
                lastStage = stage
                lastReported = now
            }
        }
        report("QA_START mode=$mode source=$requested available=${frames.size} requested=$requestedCount output=$folder")
        val started = SystemClock.elapsedRealtime()
        val stacker = JpegStacker(context)
        val selected = frames.take(requestedCount)
        if (mode == "invalid") {
            val bad = selected.map { it.copy(contentUri = null, filePath = "/nonexistent/qa-unreadable.jpg") }
            val failed = stacker.medianStack(destination, bad, false, onProgress = progress)
            assertTrue("Unreadable JPEG must fail cleanly", failed.isFailure)
            val error = requireNotNull(failed.exceptionOrNull())
            assertTrue("Unexpected unreadable-input failure: $error", error is IllegalArgumentException)
            assertEquals("После анализа качества осталось меньше двух читаемых JPEG кадров", error.message)
            report("QA_PASS mode=invalid error=${failed.exceptionOrNull()?.message}")
            return@runBlocking
        }
        if (mode == "cancel") {
            val requestedCancellation = CancellationException("QA requested cancellation")
            var cancellationRequested = false
            val work = async {
                stacker.stack(destination, selected, onProgress = { _, _ -> },
                    onAlignment = { _, _, stage ->
                        if (stage.startsWith("Тайловая интеграция")) {
                            cancellationRequested = true
                            throw requestedCancellation
                        }
                    })
            }
            try {
                work.await()
                fail("Cancellation must propagate")
            } catch (error: CancellationException) {
                assertTrue("The audit must reach tiled integration before cancellation", cancellationRequested)
                assertTrue("Unexpected cancellation", generateSequence<Throwable>(error) { it.cause }
                    .any { it === requestedCancellation })
                report("QA_PASS mode=cancel elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
            return@runBlocking
        }
        if (mode == "deepreg10") {
            var registrationCompleted = false
            val registrationStop = CancellationException("QA registration-only audit completed")
            try {
                val result = withTimeout(5 * 60 * 1000L) {
                    stacker.profileStack(
                        destination,
                        selected,
                        AstroProcessingProfile.DEEP_SKY,
                        onProgress = { stage, current, total ->
                            progress(stage, current, total)
                            if (stage == "Расчёт весов кадров") {
                                registrationCompleted = true
                                throw registrationStop
                            }
                        }
                    )
                }
                // Any returned failure, including a quality rejection, fails this success audit.
                // Keep the original cause so technical errors cannot become a green JUnit result.
                result.getOrThrow()
                fail("Registration-only audit must stop before integration")
            } catch (error: TimeoutCancellationException) {
                fail("Registration-only audit timed out: ${error.message}")
            } catch (error: CancellationException) {
                assertTrue("Full-resolution registration stage must complete", registrationCompleted)
                assertTrue("Unexpected cancellation", generateSequence<Throwable>(error) { it.cause }
                    .any { it === registrationStop })
                report("QA_PASS mode=deepreg10 elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
            return@runBlocking
        }
        val result = withTimeout(20 * 60 * 1000L) {
            when(mode) {
                "average3", "average10" -> stacker.stack(destination, selected, alignFrames = mode == "average10",
                    onProgress = { _, _ -> }, onAlignment = { c, t, s -> progress(s, c, t) }, autoStretch = true)
                "median35" -> stacker.medianStack(destination, selected, false, autoStretch = true, onProgress = progress)
                "sigma35" -> stacker.sigmaStack(destination, selected, 2.0, false, autoStretch = true, onProgress = progress)
                "deep10", "deepAll" -> stacker.profileStack(destination, selected, AstroProcessingProfile.DEEP_SKY, onProgress = progress)
                else -> error("Unknown audit mode: $mode")
            }.getOrThrow()
        }
        val uri = requireNotNull(result.contentUri).let(Uri::parse)
        if (mode == "deepAll") {
            val statistics = requireNotNull(result.statistics)
            assertEquals("The full series must reach analysis", frames.size, statistics.inputFrames)
            assertTrue("A stack must use multiple input frames", statistics.usedFrames >= 2)
            assertEquals(frames.size, statistics.usedFrames + statistics.droppedFrames)
        }
        if (args.getString("requireFullResolutionRecovery") == "true") {
            assertTrue("A replacement reference must pass full-resolution verification",
                result.warnings.any { it.startsWith("Опорный кадр заменён после полноразмерной проверки:") })
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        assertTrue("Saved output must decode", options.outWidth > 0 && options.outHeight > 0)
        val preview = BitmapFactory.Options().apply { inSampleSize = 8 }
        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, preview) }
        assertNotNull("Saved output pixels must decode", decoded)
        decoded?.recycle()
        report("QA_DETAILS mode=$mode input=${selected.size} result=$result")
        report("QA_PASS mode=$mode elapsedMs=${SystemClock.elapsedRealtime() - started} size=${options.outWidth}x${options.outHeight} output=${result.fileName} uri=$uri")
    }
}
