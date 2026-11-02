package com.joe6355.astrophoto

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real Average pipeline with controlled callback delays; never uses existing sessions. */
class ProcessingEtaPipelineDeviceTest {
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(90)

    private data class Observation(val current: Int, val total: Int, val at: Long, val eta: Long?)

    @Test fun etaAdaptsToControlledSlowdownDuringRealAverage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("etaPipelineAudit") == "true")
        val target = instrumentation.targetContext
        check(target.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = UUID.randomUUID().toString()
        val directory = File(target.cacheDir, "eta-pipeline-audit/$tag").apply { check(mkdirs()) }
        val files = File(directory, "files").apply { check(mkdir()) }
        val cache = File(directory, "cache").apply { check(mkdir()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        val folder = "Session_QA_ETA_${UUID.randomUUID()}"
        val session = SessionSummary(folder, "QA ETA", "Pictures/AstroPhoto/$folder/",
            System.currentTimeMillis(), 4, 0, 0L, "sessionName: QA ETA\n")
        val frames = (1..4).map { index ->
            val file = File(directory, "source-$index.jpg")
            val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff203040.toInt() + index * 0x010101)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally { bitmap.recycle() }
            SessionFrame("fixture_$index", file.name, SessionFrameCategory.LIGHTS_JPEG,
                file.length(), System.currentTimeMillis(), file.path, null, file.path)
        }
        val hashes = frames.associate { it.filePath!! to hash(File(it.filePath!!)) }
        val estimator = ProcessingEtaEstimator()
        val observations = mutableListOf<Observation>()
        var output: Uri? = null
        try {
            val result = JpegStacker(context).stack(session, frames, onProgress = { _, _ -> },
                onAlignment = { current, total, message ->
                    if (message.startsWith("Тайловая интеграция")) {
                        // The intentional delay changes progress cost without changing any pixels.
                        delay(if (current <= total * 3 / 8) 200L else 1_800L)
                        val now = SystemClock.elapsedRealtime()
                        observations += Observation(current, total, now,
                            estimator.update(message, current, total, now))
                    }
                }).getOrThrow()
            output = Uri.parse(requireNotNull(result.contentUri))
            requireNotNull(context.contentResolver.openInputStream(output)).use {
                val bitmap = requireNotNull(BitmapFactory.decodeStream(it))
                try { assertEquals(128, bitmap.width); assertEquals(256, bitmap.height) }
                finally { bitmap.recycle() }
            }
            hashes.forEach { (path, expected) -> assertEquals(expected, hash(File(path))) }
            assertTrue(observations.size >= 6)
            val first = observations.first()
            val last = observations.last()
            assertEquals(last.total, last.current)
            val late = observations.filter { it.current >= it.total * 3 / 4 && it.current < it.total }
            assertTrue(late.size >= 2)
            val rows = JSONArray()
            var recentError = 0.0
            var previousError = 0.0
            late.forEach { sample ->
                val actual = last.at - sample.at
                val previous = (sample.at - first.at).toDouble() /
                    (sample.current - first.current) * (sample.total - sample.current)
                val recent = requireNotNull(sample.eta)
                recentError += abs(recent - actual).toDouble()
                previousError += abs(previous - actual)
                rows.put(JSONObject().put("current", sample.current).put("total", sample.total)
                    .put("actualRemainingMillis", actual).put("recentEtaMillis", recent)
                    .put("previousEtaMillis", previous.toLong()))
            }
            recentError /= late.size
            previousError /= late.size
            val proof = JSONObject().put("controlledCallbackSlowdown", true)
                .put("actualAveragePipeline", true).put("samples", observations.size)
                .put("lateComparisons", rows).put("recentMeanAbsoluteErrorMillis", recentError)
                .put("previousMeanAbsoluteErrorMillis", previousError)
                .put("sourceJpegHashesUnchanged", true).put("outputFullyDecoded", true)
                .put("physicalNightAccuracyTested", false)
            File(directory, "proof.json").writeText(proof.toString(2))
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "QA_ETA_PIPELINE $tag\n")
            })
            assertTrue("Recent rate must improve this controlled slowdown: $proof",
                recentError < previousError * 0.65)
        } finally {
            output?.let { assertEquals(1, context.contentResolver.delete(it, null, null)) }
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
