package com.joe6355.astrophoto

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit real-photo audit. Copies sources once; only its own QA checkpoints may be damaged. */
class ProcessingRecoveryAuditDeviceTest {
    @get:Rule(order = 0) val foregroundRule = AstroUiForegroundRule()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun stopOrResumeRealProfile() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit recovery audit only", args.getString("recoveryAudit") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val folder = requireNotNull(args.getString("auditDestination"))
        require(folder.matches(Regex("Session_QA_RECOVERY_20261022_[A-Z0-9_]+")))
        val phase = requireNotNull(args.getString("auditPhase"))
        require(phase.matches(Regex("[A-Z0-9_]+")))
        val stop = args.getString("auditStop") ?: "finish"
        require(stop in listOf("analysis", "registration", "integration", "post", "kill", "finish"))
        args.getString("priorAuditFolder")?.let { prior ->
            require(prior.startsWith("Session_QA_RECOVERY_20261022_") && prior != folder)
            check(!SessionProcessingCoordinator.isActive(prior))
            val journal = ProcessingRunJournal(context)
            File(context.filesDir, "jpeg-processing-journal").listFiles().orEmpty()
                .filter { it.name.matches(Regex("run-[0-9a-f]{32}\\.properties")) }
                .mapNotNull { journal.read(it.name.removePrefix("run-").removeSuffix(".properties")) }
                .filter { it.sessionFolder == prior && !it.completed && !it.recovered }
                .forEach { journal.markRecovered(it.runId) }
        }
        val auditRoot = File(context.filesDir, "processing-recovery-audit/$folder").apply { mkdirs() }
        withContext(Dispatchers.Main) {
            composeRule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val sourceName = requireNotNull(args.getString("sourceSession"))
        val source = SessionBrowserRepository(context).loadSessions().single { it.folderName == sourceName }
        val sourceFrames = SessionFramesRepository(context).loadFrames(source)
            .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }.sortedBy { it.fileName }
        require(sourceFrames.size == 10) { "This audit uses the ten-frame source" }
        val sourceHashes = sourceFrames.associate { frame -> frame.fileName to hash {
            context.contentResolver.openInputStream(Uri.parse(requireNotNull(frame.contentUri)))!!
        } }
        val destination = source.copy(folderName = folder, sessionName = folder,
            relativePath = "Pictures/AstroPhoto/$folder/", infoContent = "")
        var frames = SessionFramesRepository(context).loadFrames(destination)
            .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }.sortedBy { it.fileName }
        if (frames.isEmpty()) {
            check(phase == "ANALYSIS_STOP") { "Only the first audit phase creates copies" }
            sourceFrames.forEach { frame ->
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, frame.fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroPhoto/$folder/Lights/JPEG/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(context.contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
                context.contentResolver.openInputStream(Uri.parse(requireNotNull(frame.contentUri)))!!.use { input ->
                    context.contentResolver.openOutputStream(uri, "w")!!.use { output -> input.copyTo(output) }
                }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            frames = SessionFramesRepository(context).loadFrames(destination)
                .filter { it.category == SessionFrameCategory.LIGHTS_JPEG }.sortedBy { it.fileName }
        }
        assertEquals(10, frames.size)
        fun copyHashes() = frames.associate { frame -> frame.fileName to hash {
            context.contentResolver.openInputStream(Uri.parse(requireNotNull(frame.contentUri)))!!
        } }
        assertEquals("QA copies must match the original JPEGs", sourceHashes, copyHashes())
        val roots = listOf("jpeg-profile-analysis-checkpoints", "jpeg-profile-registration-checkpoints",
            "jpeg-profile-integration-checkpoints")
        fun directories(root: String) = File(context.filesDir, root).listFiles().orEmpty()
            .filter { it.isDirectory }.map { it.name }.toSet()
        val before = roots.associateWith(::directories)
        val ownedFile = File(auditRoot, "owned-checkpoints.json")
        val owned = if (ownedFile.isFile) JSONObject(ownedFile.readText()) else JSONObject()
        val damage = args.getString("auditDamage")
        if (damage != null) {
            require(damage == "integration") { "Only the explicitly owned integrated pixels may be changed" }
            val root = "jpeg-profile-integration-checkpoints"
            val directory = File(File(context.filesDir, root), owned.getString(root))
            val pixels = File(directory, "stacked.argb")
            check(pixels.isFile && pixels.canonicalFile.parentFile == directory.canonicalFile)
            val previous = hash { pixels.inputStream() }
            java.io.RandomAccessFile(pixels, "rw").use { file ->
                val first = file.readUnsignedByte()
                file.seek(0)
                file.write(first xor 1)
                file.fd.sync()
            }
            check(previous != hash { pixels.inputStream() })
        }
        val progress = mutableListOf<String>()
        val firstProgress = CompletableDeferred<Unit>()
        val backgroundProgress = CompletableDeferred<Unit>()
        val inBackground = AtomicBoolean(false)
        var created: JpegStackResult? = null
        var failure: Throwable? = null
        var reached = false
        val started = SystemClock.elapsedRealtime()
        val journalDirectory = File(context.filesDir, "jpeg-processing-journal")
        val journalFilesBefore = journalDirectory.listFiles().orEmpty().map { it.name }.toSet()
        fun report(message: String) {
            android.util.Log.i("AstroPhotoRecoveryQA", message)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
        }
        fun record(status: String) {
            roots.forEach { root ->
                val additions = directories(root) - before.getValue(root)
                if (!owned.has(root) && additions.size == 1) owned.put(root, additions.single())
            }
            ownedFile.writeText(owned.toString(2))
            File(auditRoot, "$phase.json").writeText(JSONObject().apply {
                put("phase", phase); put("stop", stop); put("status", status)
                put("destination", folder); put("damage", damage)
                put("seconds", (SystemClock.elapsedRealtime() - started) / 1000.0)
                put("progress", JSONArray(progress)); put("originalHashes", JSONObject(sourceHashes))
                put("copyHashes", JSONObject(copyHashes())); put("ownedCheckpoints", owned)
                put("resultFile", created?.fileName); put("resultUri", created?.contentUri)
                put("usedFrames", created?.statistics?.usedFrames)
                put("failure", failure?.stackTraceToString())
                put("progressWhileActivityStopped", backgroundProgress.isCompleted)
                val newJournal = journalDirectory.listFiles().orEmpty().singleOrNull {
                    it.name !in journalFilesBefore && it.name.endsWith(".properties")
                }
                put("runId", newJournal?.name?.removePrefix("run-")?.removeSuffix(".properties"))
            }.toString(2))
        }
        report("RECOVERY_START phase=$phase stop=$stop source=$sourceName destination=$folder")
        val stacker = JpegStacker(context)
        fun publishedPngCount(): Int = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/AstroPhoto/$folder/Processed/%", "%.png"), null
        ).use { requireNotNull(it).count }
        val previousPngCount = publishedPngCount()
        val job = withContext(Dispatchers.Main) {
            checkNotNull(SessionProcessingCoordinator.start(context, folder, "Профиль DeepSky", "Подготовка", 10) {
                try {
                    created = stacker.profileStack(destination, frames, AstroProcessingProfile.DEEP_SKY) { stage, c, t ->
                        SessionProcessingCoordinator.update(folder, stage, c, t)
                        firstProgress.complete(Unit)
                        if (inBackground.get()) backgroundProgress.complete(Unit)
                        progress += stage
                        if (progress.size == 1 || progress.getOrNull(progress.lastIndex - 1) != stage) {
                            report("RECOVERY_PROGRESS phase=$phase stage=$stage current=$c total=$t")
                        }
                        val shouldStop = when (stop) {
                            "analysis" -> stage.startsWith("Анализ JPEG-кадров") && c == 3
                            "registration" -> stage == "Расчёт весов кадров"
                            "integration" -> stage == "Уточнение маски неба"
                            "post", "kill" -> stage == "Сохранение PNG без потерь"
                            else -> false
                        }
                        if (shouldStop) {
                            reached = true
                            if (stop == "kill") {
                                record("READY_FOR_PROCESS_KILL")
                                report("RECOVERY_READY_FOR_KILL phase=$phase")
                                awaitCancellation()
                            }
                            SessionProcessingCoordinator.cancel(folder)
                            currentCoroutineContext().ensureActive()
                        }
                    }.getOrThrow()
                    SessionProcessingCoordinator.update(folder, "Готово: ${created.fileName}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failure = error
                    throw error
                }
            })
        }
        try {
            if (stop == "finish") {
                withTimeout(30_000) { firstProgress.await() }
                composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                inBackground.set(true)
                try {
                    withTimeout(120_000) { backgroundProgress.await() }
                    assertTrue("The real pipeline must keep running with its Activity stopped", job.isActive)
                } finally {
                    inBackground.set(false)
                    composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
            }
            withTimeout(12 * 60 * 1000L) { job.join() }
            failure?.let { throw AssertionError("Real processing failed", it) }
            if (stop == "finish") {
                val result = requireNotNull(created)
                assertEquals(7, result.statistics?.usedFrames)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(requireNotNull(result.contentUri))).use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                assertEquals(1440, bounds.outWidth); assertEquals(1920, bounds.outHeight)
            } else {
                assertTrue("Requested stop stage was not reached: $stop", reached)
                assertTrue("The real coordinator job must be cancelled", job.isCancelled)
                assertNull(created)
                assertEquals("Cancelled phases must not publish PNGs", previousPngCount, publishedPngCount())
                val newJournal = journalDirectory.listFiles().orEmpty().single {
                    it.name !in journalFilesBefore && it.name.endsWith(".properties")
                }
                val record = checkNotNull(ProcessingRunJournal(context).read(
                    newJournal.name.removePrefix("run-").removeSuffix(".properties")))
                assertEquals(folder, record.sessionFolder)
                assertTrue("Explicit cancellation must finish its journal: $record", record.completed)
                assertEquals("cancelled", record.lastCompletedStage)
            }
            val restored = mapOf("analysis" to "Восстановление анализа",
                "registration" to "Выравнивание восстановлено из checkpoint",
                "full" to "Full-resolution alignment восстановлен",
                "integration" to "Интеграция восстановлена из checkpoint",
                "post" to "Постобработка восстановлена из checkpoint")
            args.getString("auditExpectRestore").orEmpty().split(',').filter { it.isNotBlank() }.forEach { stage ->
                assertTrue("Missing restored stage $stage: $progress", progress.any { it.startsWith(restored.getValue(stage)) })
            }
            args.getString("auditExpectRecompute").orEmpty().split(',').filter { it.isNotBlank() }.forEach { stage ->
                assertFalse("Damaged stage $stage was reused", progress.any { it.startsWith(restored.getValue(stage)) })
            }
            assertEquals(sourceHashes, copyHashes())
            assertFalse(SessionProcessingCoordinator.isActive(folder))
            assertFalse(ProcessingRecoveryStore(context).pendingInterrupted().any { it.sessionFolder == folder })
            record("PASS")
            report("RECOVERY_PASS phase=$phase seconds=${(SystemClock.elapsedRealtime() - started) / 1000.0}")
        } catch (error: Throwable) {
            failure = error
            record("FAIL")
            throw error
        } finally {
            SessionProcessingCoordinator.cancel(folder)
            withContext(NonCancellable) { withTimeout(20_000) { job.join() } }
        }
    }

    private fun hash(open: () -> java.io.InputStream): String = open().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
