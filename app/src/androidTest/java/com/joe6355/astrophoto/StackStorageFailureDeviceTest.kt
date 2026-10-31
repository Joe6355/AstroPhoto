package com.joe6355.astrophoto

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Injected I/O failures on new fixtures; never fills storage or processes user photographs. */
class StackStorageFailureDeviceTest {
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(90)

    @Test fun manualStackReportsFullStorageCleansTemporaryFilesAndRetries() = runBlocking {
        val fixture = fixture("manual")
        val fault = IOException("write failed: ENOSPC (No space left on device)")
        var injected = false
        val failed = JpegStacker(fixture.context).stack(fixture.session, fixture.frames,
            onProgress = { _, _ -> }, onAlignment = { current, _, message ->
                if (message.startsWith("Тайловая интеграция") && current > 0) {
                    injected = true
                    throw fault
                }
            })
        assertTrue("Reach the real tiled integration stage", injected)
        val error = requireNotNull(failed.exceptionOrNull())
        fixture.verifyFailureIsSafe(error)
        assertEquals(EXPECTED_MESSAGE, error.message)
        assertTrue(generateSequence(error) { it.cause }.any { it === fault })
        val result = JpegStacker(fixture.context).stack(fixture.session, fixture.frames,
            onProgress = { _, _ -> }).getOrThrow()
        val uri = android.net.Uri.parse(requireNotNull(result.contentUri))
        try {
            val bitmap = requireNotNull(fixture.context.contentResolver.openInputStream(uri)).use {
                requireNotNull(BitmapFactory.decodeStream(it))
            }
            try { assertEquals(64, bitmap.width); assertEquals(64, bitmap.height) }
            finally { bitmap.recycle() }
            assertEquals(4, result.frameCount)
            fixture.verifySources()
            fixture.report("manual", error, retryPassed = true)
        } finally {
            assertEquals(1, fixture.context.contentResolver.delete(uri, null, null))
        }
        assertEquals(0, fixture.outputRows())
    }

    @Test fun profileReportsFullStorageWithoutPublishingOutput() = runBlocking {
        val fixture = fixture("profile")
        val fault = IOException("write failed: ENOSPC (No space left on device)")
        var injected = false
        val failed = JpegStacker(fixture.context).profileStack(fixture.session, fixture.frames,
            AstroProcessingProfile.DEEP_SKY) { _, current, _ ->
            if (current > 0) { injected = true; throw fault }
        }
        assertTrue("Reach a real profile progress callback", injected)
        val error = requireNotNull(failed.exceptionOrNull())
        fixture.verifyFailureIsSafe(error)
        assertTrue(error.message.orEmpty().contains(EXPECTED_MESSAGE))
        assertTrue(generateSequence(error) { it.cause }.any { it === fault })
        fixture.report("profile", error, retryPassed = false)
    }

    private fun fixture(mode: String): Fixture {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("stackStorageAudit") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(arguments.getString("stackStorageTag"))
        require(tag.matches(Regex("[A-Za-z0-9_]{1,45}")))
        val directory = File(target.cacheDir, "stack-storage-audit/$tag/$mode")
        check(!directory.exists() && directory.mkdirs()) { "Use a fresh audit tag" }
        val files = File(directory, "files").apply { check(mkdir()) }
        val cache = File(directory, "cache").apply { check(mkdir()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        val folder = "Session_QA_STACK_STORAGE_${UUID.randomUUID()}"
        val session = SessionSummary(folder, "QA storage", "Pictures/AstroPhoto/$folder/",
            System.currentTimeMillis(), 4, 0, 0L, "sessionName: QA storage\nnote: original fixture\n")
        val frames = (1..4).map { index ->
            val file = File(directory, "source-$index.jpg")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff203040.toInt() + index * 0x010101)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            SessionFrame("fixture_$index", file.name, SessionFrameCategory.LIGHTS_JPEG,
                file.length(), System.currentTimeMillis(), file.path, null, file.path)
        }
        SessionInfoStore(context).update(folder, session.infoContent) { session.infoContent }
        val metadata = File(files, "session-info/$folder.txt")
        return Fixture(context, directory, session, frames, frames.associate { it.filePath!! to hash(File(it.filePath!!)) },
            metadata, metadata.readBytes())
    }

    private class Fixture(val context: Context, val directory: File, val session: SessionSummary,
        val frames: List<SessionFrame>, val hashes: Map<String, String>, val metadata: File, val info: ByteArray) {
        fun outputRows(): Int = requireNotNull(context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id"), "relative_path=?", arrayOf(session.relativePath + "Processed/"), null)).use { it.count }
        fun verifySources() = hashes.forEach { (path, expected) -> assertEquals(expected, hash(File(path))) }
        fun verifyFailureIsSafe(error: Throwable) {
            verifySources()
            assertArrayEquals(info, metadata.readBytes())
            assertEquals(0, outputRows())
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith(TemporaryPipelineFiles.DIRECTORY_PREFIX) })
            File(directory, "observed-before-assert.json").writeText(JSONObject().apply {
                put("message", error.message); put("sourcesUnchanged", true)
                put("metadataUnchanged", true); put("noOutput", true); put("temporaryRunsRemoved", true)
            }.toString(2))
        }
        fun report(mode: String, error: Throwable, retryPassed: Boolean) {
            File(directory, "proof.json").writeText(JSONObject().apply {
                put("mode", mode); put("message", error.message); put("retryPassed", retryPassed)
                put("sourcesUnchanged", true); put("faultInjectedFromProgressCallback", true)
                put("actualDiskFilled", false)
            }.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "QA_STACK_STORAGE_PASS mode=$mode retry=$retryPassed\n")
            })
        }
    }

    private companion object {
        const val EXPECTED_MESSAGE = "Недостаточно свободного места для обработки. Освободите место и повторите обработку."
        fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
