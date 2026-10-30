package com.joe6355.astrophoto

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.integration.IntegrationCheckpointFrameSignature
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationRun
import com.joe6355.astrophoto.processing.jpeg.v2.model.*
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptiveProcessingResult
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Explicit QA-only audit. Creates new fixtures; never alters an existing session. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class NativeStorageRegressionDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)

    @Test fun jpegPublicationAndNativeMp4CanFailCleanlyAndRetry() = runBlocking {
        val (context, directory) = auditContext()
        check(!File(directory, "media-proof.json").exists()) { "Use a new audit tag" }
        val resolver = context.contentResolver
        val tag = directory.name
        val folder = "Session_${tag}_QA_NATIVE_STORAGE"
        val relativePath = "Pictures/AstroPhoto/$folder/Lights/JPEG/"
        val sources = (1..3).map { index ->
            val bytes = jpeg(index)
            val uri = newJpeg(context, relativePath, "native_$index.jpg")
            writePendingCapturedJpeg(bytes, { resolver.openOutputStream(uri, "w") }, {
                resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
            }, { resolver.delete(uri, null, null) })
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            uri to bytes
        }
        val jpegFailures = JSONArray()
        for (mode in listOf("no-space", "publication-rejected")) {
            val uri = newJpeg(context, relativePath, "failed_$mode.jpg")
            val failure = IOException("write failed: ENOSPC")
            val error = assertThrows(Exception::class.java) {
                writePendingCapturedJpeg(sources.first().second, {
                    val raw = resolver.openOutputStream(uri, "w")!!
                    if (mode == "no-space") object : OutputStream() {
                        override fun write(value: Int) { raw.write(value); throw failure }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            raw.write(bytes, offset, minOf(32, length))
                            throw failure
                        }
                        override fun close() = raw.close()
                    } else raw
                }, { 0 }, { resolver.delete(uri, null, null) })
            }
            if (mode == "no-space") {
                assertEquals(CAPTURE_STORAGE_FULL_MESSAGE, error.message)
                assertSame(failure, error.cause)
            }
            resolver.query(uri, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
            jpegFailures.put(JSONObject().put("mode", mode).put("newRowRemoved", true))
        }

        val now = System.currentTimeMillis()
        val info = "sessionName: QA_NATIVE_STORAGE\nnote: own native storage fixtures\n"
        val session = SessionSummary(folder, tag, "Pictures/AstroPhoto/$folder/", now, 3, 0, 0, info)
        SessionInfoStore(context).update(folder, info) { info }
        val metadata = File(context.filesDir, "session-info/$folder.txt")
        val infoBefore = metadata.readBytes()
        val rowsBefore = videoRows(context)
        val exporter = SessionTimelapseExporter(context) { Date(now) }
        val videoFailures = JSONArray()
        for (cancel in listOf(false, true)) {
            val fault = if (cancel) CancellationException("QA native video cancellation")
                else IOException("write failed: ENOSPC")
            var triggered = false
            try {
                val result = exporter.export(session, 5) { progress ->
                    if (progress.message.startsWith("Кодирование кадра")) {
                        triggered = true
                        throw fault
                    }
                }
                assertFalse(cancel)
                assertTrue(result.isFailure)
                assertEquals(TIMELAPSE_STORAGE_FULL_MESSAGE, result.exceptionOrNull()!!.message)
                assertSame(fault, result.exceptionOrNull()!!.cause)
            } catch (error: CancellationException) {
                assertTrue(cancel)
                assertSame(fault, error)
            }
            assertTrue(triggered)
            assertEquals("No pending or published failed MP4", rowsBefore, videoRows(context))
            assertArrayEquals(infoBefore, metadata.readBytes())
            requireNotNull(sessionAccess.acquire(folder)).close()
            videoFailures.put(JSONObject().put("mode", if (cancel) "cancel" else "no-space")
                .put("faultAfterFirstFrame", true).put("newRowRemoved", true))
        }

        val exports = JSONArray()
        for (fps in listOf(5, 30)) {
            val result = exporter.export(session, fps).getOrThrow()
            assertEquals(3, result.frameCount)
            assertEquals(640, result.width)
            assertEquals(480, result.height)
            val uri = result.contentUri!!.toUri()
            val copy = File(context.cacheDir, "$tag-$fps.mp4")
            check(!copy.exists())
            resolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            assertTrue(copy.length() > 0)
            assertEquals(copy.length(), result.sizeBytes)
            var mime = ""
            resolver.query(uri, arrayOf("is_pending", "mime_type", "_size", "_display_name"), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                mime = it.getString(1)
                assertEquals(copy.length(), it.getLong(2))
                assertEquals("Result must report the actual collision-safe name", it.getString(3), result.fileName)
                assertEquals("Movies/AstroPhoto/${it.getString(3)}", result.location)
            }
            exports.put(JSONObject().put("fps", fps).put("frames", 3).put("cacheFile", copy.name)
                .put("uri", result.contentUri).put("name", result.fileName).put("bytes", copy.length())
                .put("mime", mime).put("sha256", sha256(copy.readBytes())))
        }
        assertNotEquals("Fixed time must exercise a filename collision",
            exports.getJSONObject(0).getString("name"), exports.getJSONObject(1).getString("name"))
        assertEquals(rowsBefore.size + 2, videoRows(context).size)
        sources.forEach { (uri, bytes) ->
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        }
        assertArrayEquals(infoBefore, metadata.readBytes())
        val proof = JSONObject().put("folder", folder).put("sourceCount", 3)
            .put("jpegFailures", jpegFailures).put("videoFailures", videoFailures)
            .put("exports", exports).put("sourceAndMetadataUnchanged", true)
        File(directory, "media-proof.json").writeText(proof.toString(2))
        for (index in 0 until exports.length()) {
            assertEquals("Published MP4 must use the container MIME", "video/mp4", exports.getJSONObject(index).getString("mime"))
        }
    }

    @Test fun checkpointIoFailureOnAndroidKeepsProgressAndRestoresExactPixels() {
        val (_, directory) = auditContext()
        val isolatedRoot = File(directory, "checkpoint-fixture").apply { check(mkdir()) }
        TemporaryPipelineFiles.create(isolatedRoot).use { source ->
            val bytes = ByteArray(16) { (it + 1).toByte() }
            val stack = FileBackedImage(source.file("stack.argb").apply { writeBytes(bytes) }, 2, 2)
            val coverage = FileBackedFloatPlane(source.file("coverage.f32").apply {
                writeBytes(ByteArray(16) { 21 })
            }, 2, 2)
            val run = ProfileIntegrationRun(IntegrationDiagnostics(2, 2, 2, 2, 1,
                IntegrationMode.LINEAR_WEIGHTED_AVERAGE, false, 100f, 1f, 1f, 10, 64, false),
                stack, coverage, null, SensorDefectFilteringReport(), 10)
            val registration = RegistrationResult(0f, 0f, 0f, 1f, 10, 10, 10, 0f, 1f, true, null)
            val store = ProfileIntegrationCheckpointStore.open(isolatedRoot, "own-fixture",
                AstroProcessingProfile.DEEP_SKY, 2, 2,
                listOf(IntegrationCheckpointFrameSignature("frame", "frame.jpg", 100, 200, registration, 1f)),
                SensorDefectMask.empty(2, 2), SkyMask.full(2, 2), forTesting = true)
            store.write(run)
            store.writePostProcessing("own-inputs", FileBackedAdaptiveProcessingResult(stack,
                AdaptiveProcessingDiagnostics("DEEP_SKY", SkyStatisticsResult.EMPTY, SkyStatisticsResult.EMPTY,
                    GradientRemovalDiagnostics(0.8f, 2, 2, 4, 0.1f),
                    NeutralizationDiagnostics(LinearRgb(0.01f, 0.02f, 0.03f)),
                    StretchDiagnostics(0.01f, 0.9f, 3f, 0.2f, 0.4f, 1f),
                    ChromaNoiseDiagnostics(0.2f, 1), StarEnhancementDiagnostics(0.1f, 8, 6, 2, 0f),
                    0, 1000, emptyMap())))
            val checkpoint = isolatedRoot.walkTopDown().first { it.name == "manifest.bin" }.parentFile!!
            val before = fileHashes(checkpoint)
            TemporaryPipelineFiles.create(isolatedRoot).use { restored ->
                val blocked = restored.file("checkpoint-valid-coverage.f32").apply { check(mkdir()) }
                val sentinel = File(blocked, "owned-sentinel").apply { writeText("keep") }
                val error = assertThrows(IOException::class.java) { store.readInto(restored) }
                assertTrue(error.message!!.contains("Прогресс сохранён"))
                assertEquals(before, fileHashes(checkpoint))
                assertFalse(restored.file("checkpoint-integrated-sky.argb").exists())
                check(sentinel.delete() && blocked.delete())
                assertArrayEquals(bytes, store.readInto(restored)!!.stackedSky.file.readBytes())
                val noSpace = IOException("write failed: ENOSPC")
                val postError = assertThrows(IOException::class.java) {
                    store.readPostProcessing("own-inputs", restored) { input, output, _ ->
                        output.writeBytes(input.readBytes().copyOf(3))
                        throw noSpace
                    }
                }
                assertSame(noSpace, postError.cause)
                assertEquals(before, fileHashes(checkpoint))
                assertFalse(restored.file("checkpoint-postprocessed.argb").exists())
                assertArrayEquals(bytes, store.readPostProcessing("own-inputs", restored)!!.image.file.readBytes())
            }
            File(directory, "checkpoint-proof.json").writeText(JSONObject()
                .put("realDestinationIoFailure", true).put("postprocessingEnospcInjected", true)
                .put("savedCheckpointUnchanged", true).put("partialCopiesRemoved", true)
                .put("retryExactPixels", true).put("checkpointHashes", JSONObject(before)).toString(2))
        }
    }

    private fun auditContext(): Pair<Context, File> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeStorageAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(args.getString("nativeAuditTag"))
        require(tag.matches(Regex("native_[A-Za-z0-9_]{1,50}")))
        val directory = File(context.cacheDir, tag).apply { check(isDirectory || mkdir()) }
        return context to directory
    }

    private fun newJpeg(context: Context, path: String, name: String) = requireNotNull(
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", name)
            put("mime_type", "image/jpeg")
            put("relative_path", path)
            put("is_pending", 1)
        }))

    private fun videoRows(context: Context): Map<Long, String> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Movies/AstroPhoto/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return buildMap {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "_display_name", "is_pending"), args, null)!!.use { cursor ->
                while (cursor.moveToNext()) put(cursor.getLong(0), "${cursor.getString(1)}|${cursor.getInt(2)}")
            }
        }
    }

    private fun jpeg(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(640 * 480) { position ->
                if (position / 640 < 240) 0xffff0000.toInt() or (index * 8 shl 8)
                else 0xff0000ff.toInt() or (index * 8 shl 8)
            }
            bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 480)
            return ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun fileHashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to sha256(it.readBytes()) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
