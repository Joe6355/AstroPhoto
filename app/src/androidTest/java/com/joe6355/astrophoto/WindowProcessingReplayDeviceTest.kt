package com.joe6355.astrophoto

import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import java.io.File
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in diagnostic replay on new private QA copies; JUnit success does not assert image quality. */
class WindowProcessingReplayDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(1200)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()

    @Test fun exportsNativeAnalysisCopies(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("windowCatalogs") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        require(Build.MODEL == args.getString("windowExpectedModel"))
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val directory = File(context.cacheDir, "window-native-analysis")
        require(!directory.exists() && directory.mkdir())
        val stacker = JpegStacker(context)
        val records = JSONArray()
        for (tag in listOf("series2", "series1")) {
            val input = File(context.cacheDir, "window-replay-inputs/$tag")
            val manifest = JSONObject(File(input, "sha256.json").readText())
            val files = input.listFiles().orEmpty().filter { it.name.matches(Regex("AstroSeries_[0-9_]+\\.jpg")) }.sortedBy { it.name }
            require(files.size == if (tag == "series2") 10 else 30)
            for ((index, file) in files.withIndex()) {
                fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(manifest.getString(file.name), digest())
                val frame = SessionFrame(file.name, file.name, SessionFrameCategory.LIGHTS_JPEG,
                    file.length(), file.lastModified(), file.path, null, file.path)
                val bitmap = requireNotNull(stacker.decodeMedianFrame(frame, 720, 960))
                val target = File(directory, "$tag-${index + 1}.argb")
                try {
                    val row = IntArray(bitmap.width)
                    DataOutputStream(BufferedOutputStream(target.outputStream())).use { output ->
                        for (y in 0 until bitmap.height) {
                            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                            row.forEach(output::writeInt)
                        }
                    }
                    records.put(JSONObject().put("tag", tag).put("index", index + 1).put("file", target.name)
                        .put("source", file.name).put("width", bitmap.width).put("height", bitmap.height)
                        .put("inputSha256", digest()))
                } finally { bitmap.recycle() }
                assertEquals(manifest.getString(file.name), digest())
            }
        }
        File(directory, "manifest.json").writeText(records.toString(2))
    }

    @Test fun recordsFullPipelineStagesOnOwnWindowCopies(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("windowReplay") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        require(Build.MODEL == args.getString("windowExpectedModel"))
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        check(SessionProcessingCoordinator.states.value.values.none { it.running })
        check(SeriesCaptureCoordinator.state.value?.running != true)
        val tag = requireNotNull(args.getString("windowTag"))
        val inputTag = requireNotNull(args.getString("windowInputTag"))
        val sessionTag = args.getString("windowSessionTag") ?: tag
        require(listOf(tag, inputTag, sessionTag).all { it.matches(Regex("[A-Za-z0-9_]{1,45}")) })
        val directory = File(context.cacheDir, "window-stage-audit/$tag")
        require(!directory.exists() && directory.mkdirs())
        val input = File(context.cacheDir, "window-replay-inputs/$inputTag")
        val manifest = JSONObject(File(input, "sha256.json").readText())
        val files = input.listFiles().orEmpty().filter { it.name.matches(Regex("AstroSeries_[0-9_]+\\.jpg")) }.sortedBy { it.name }
        require(files.size == args.getString("windowCount")?.toInt())
        require(files.size in 4..40 && manifest.length() == files.size)
        fun hash(file: File): String = file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        files.forEach { require(it.canonicalFile.parentFile == input.canonicalFile); assertEquals(manifest.getString(it.name), hash(it)) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files.first().path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val folder = "Session_QA_WindowFix_$sessionTag"
        val session = SessionSummary(folder, "QA window $tag", "Pictures/AstroPhoto/$folder/",
            System.currentTimeMillis(), files.size, 0, files.sumOf { it.length() }, "sessionName: QA window $tag\n")
        val frames = files.map { file -> SessionFrame(file.name, file.name, SessionFrameCategory.LIGHTS_JPEG,
            file.length(), file.lastModified(), file.path, null, file.path) }
        val existing = context.cacheDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("jpeg-stage7-") }.map { it.name }.toSet()
        val objects = File(directory, "objects").apply { check(mkdir()) }
        val stages = JSONArray()
        val captured = linkedSetOf<String>()
        val interestingStages = setOf("Уточнение маски неба", "Объединение неба и переднего плана",
            "Анализ неба", "Удаление светового загрязнения", "Коррекция цвета неба",
            "Подавление яркостного шума", "Проявление слабых звёзд", "Подавление цветного шума",
            "Усиление звёзд", "Сохранение PNG без потерь")
        var capturedBytes = 0L
        val started = SystemClock.elapsedRealtime()
        val proof = JSONObject().put("tag", tag).put("inputTag", inputTag).put("folder", folder)
            .put("width", bounds.outWidth).put("height", bounds.outHeight).put("stages", stages)
            .put("inputCount", files.size).put("diagnosticOnly", true)
        fun saveProof() { File(directory, "proof.json").writeText(proof.toString(2)) }
        val stacker = JpegStacker(context)
        stacker.setUserApprovedInsufficientProfileFrames(args.getString("windowApproved") == "true")
        try {
            val result = stacker.profileStack(session, frames, AstroProcessingProfile.DEEP_SKY) { stage, current, total ->
                if (stage in interestingStages && captured.add(stage)) {
                    withContext(Dispatchers.IO) {
                        val run = context.cacheDir.listFiles().orEmpty().single {
                            it.isDirectory && it.name.startsWith("jpeg-stage7-") && it.name !in existing
                        }
                        val available = run.listFiles().orEmpty().filter {
                            it.isFile && it.length() > 0 && (it.name.startsWith("candidate-") ||
                                it.name.startsWith("work-") || it.name.startsWith("plane-") || it.name.startsWith("checkpoint-")) &&
                                it.extension in setOf("argb", "f32")
                        }.sortedBy { it.name }
                        val entries = JSONArray()
                        for (file in available) {
                            val target = File(objects, file.name)
                            if (!target.exists()) {
                                capturedBytes += file.length()
                                check(capturedBytes <= 512L * 1024L * 1024L) { "Bound diagnostic snapshot size" }
                                file.copyTo(target)
                            }
                            entries.put(JSONObject().put("file", file.name).put("bytes", target.length()))
                        }
                        stages.put(JSONObject().put("stage", stage).put("current", current).put("total", total)
                            .put("elapsedMillis", SystemClock.elapsedRealtime() - started).put("files", entries))
                        saveProof()
                    }
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("stream", "WINDOW_PROGRESS $tag $stage $current/$total\n") })
            }.getOrThrow()
            val uri = Uri.parse(requireNotNull(result.contentUri))
            val image = context.contentResolver.openInputStream(uri).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try { assertEquals(bounds.outWidth, image.width); assertEquals(bounds.outHeight, image.height) }
            finally { image.recycle() }
            context.contentResolver.openInputStream(uri).use { stream ->
                File(directory, result.fileName).outputStream().use { requireNotNull(stream).copyTo(it) }
            }
            val reportDir = File(directory, "reports").apply { check(mkdir()) }
            AppSpecificProcessingReportStore(context).listForSession(folder).forEach { it.file.copyTo(File(reportDir, it.reportFileName)) }
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf("_id", "_display_name"),
                "relative_path=?", arrayOf("Pictures/AstroPhoto/$folder/Processed/"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (!name.endsWith(".processing.json")) continue
                    val reportUri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(0))
                    context.contentResolver.openInputStream(reportUri).use { stream ->
                        File(reportDir, name).outputStream().use { requireNotNull(stream).copyTo(it) }
                    }
                }
            }
            check(reportDir.listFiles().orEmpty().isNotEmpty()) { "Full report required for diagnosis" }
            proof.put("outputFile", result.fileName).put("contentUri", result.contentUri)
                .put("statistics", result.statistics.toString()).put("warnings", JSONArray(result.warnings))
                .put("completed", true)
        } catch (error: Throwable) {
            proof.put("failure", error.toString())
            throw error
        } finally {
            files.forEach { assertEquals(manifest.getString(it.name), hash(it)) }
            proof.put("inputHashesUnchanged", true).put("capturedBytes", capturedBytes)
                .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            saveProof()
        }
    }
}
