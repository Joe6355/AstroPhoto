package com.joe6355.astrophoto

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Actual stacking UI completion with new tiny JPEGs; no user series or direct feedback calls. */
@SdkSuppress(minSdkVersion = 31)
class ProcessingCompletionFeedbackDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun realManualStackCompletionReadsEnabledAndDisabledFeedbackSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("processingFeedbackAudit") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.joe6355.astrophoto.qa20261017")
        check(SessionProcessingCoordinator.states.value.values.none { it.running })
        val settingsStore = CameraSettingsStore(context)
        val originalSettings = settingsStore.load()
        val originalSession = ShootingSessionStore(context).load()
        val audio = context.getSystemService(AudioManager::class.java)
        check(!audio.isMusicActive) { "Do not attribute another app's playback to this audit" }
        val folder = "Session_QA_ProcessingFeedback_${UUID.randomUUID()}"
        val relative = "Pictures/AstroPhoto/$folder/"
        val session = SessionSummary(folder, "QA processing feedback", relative,
            System.currentTimeMillis(), 2, 0, 0L, "")
        val completed = AtomicInteger()
        val results = mutableListOf<String>()
        val directory = File(context.cacheDir, "processing_feedback_20261101").apply { mkdirs() }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        fun ownVibrations() = shell("dumpsys vibrator_manager").lineSequence()
            .filter { it.contains("opPkg: ${context.packageName},") }.map(String::trim).toSet()
        fun ownTracks(dump: String) = Regex(
            """(?m)^\s*\S+\s+\d+\s+(?:yes|no)\s+${android.os.Process.myPid()}\s+.*$"""
        ).findAll(dump).map { it.value.trim() }.toList()
        val sources = (1..2).map { index ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "QA_source_$index.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relative + "Lights/JPEG/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff203040.toInt() + index * 0x010101)
                context.contentResolver.openOutputStream(uri).use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, requireNotNull(it)))
                }
            } finally { bitmap.recycle() }
            check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            uri
        }
        fun hash(uri: Uri): String = context.contentResolver.openInputStream(uri).use {
            MessageDigest.getInstance("SHA-256").digest(requireNotNull(it).readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
        val sourceHashes = sources.associateWith(::hash)
        val proof = JSONObject().put("folder", folder).put("sourceCount", sources.size)
            .put("musicVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("musicVolumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("cases", JSONArray())
        try {
            composeRule.setContent {
                AstroPhotoTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        JpegStackingBlock(session, 0, onStackCompleted = { completed.incrementAndGet() },
                            onResultReady = { results.add(it) })
                    }
                }
            }
            composeRule.onNode(hasText("Ручная обработка") and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .performScrollTo().performClick()
            composeRule.onNodeWithText("OFF").performScrollTo().performClick()
            for (enabled in listOf(true, false)) {
                settingsStore.save(originalSettings.copy(soundAfterProcessing = enabled, vibrationAfterProcessing = enabled))
                check(!audio.isMusicActive) { "Unrelated audio became active" }
                val vibrationsBefore = ownVibrations()
                val expected = completed.get() + 1
                var audioDuring = ""
                var playbackObserved = false
                composeRule.onNodeWithText("Сложить JPEG").performScrollTo().assertIsEnabled().performClick()
                val deadline = SystemClock.elapsedRealtime() + 35_000L
                while (completed.get() < expected && SystemClock.elapsedRealtime() < deadline) {
                    if (audio.isMusicActive) {
                        playbackObserved = true
                        if (audioDuring.isEmpty()) audioDuring = shell("dumpsys media.audio_flinger")
                    }
                    SystemClock.sleep(5)
                }
                assertEquals("Real UI processing must complete successfully", expected, completed.get())
                val feedbackDeadline = SystemClock.elapsedRealtime() + 400L
                while (SystemClock.elapsedRealtime() < feedbackDeadline) {
                    if (audio.isMusicActive) {
                        playbackObserved = true
                        if (audioDuring.isEmpty()) audioDuring = shell("dumpsys media.audio_flinger")
                    }
                    SystemClock.sleep(5)
                }
                instrumentation.runOnMainSync { }
                SystemClock.sleep(600)
                val cleanupStart = SystemClock.elapsedRealtime()
                var audioAfter = shell("dumpsys media.audio_flinger")
                while (ownTracks(audioAfter).isNotEmpty() && SystemClock.elapsedRealtime() - cleanupStart < 5_000L) {
                    SystemClock.sleep(100)
                    audioAfter = shell("dumpsys media.audio_flinger")
                }
                val vibrations = ownVibrations() - vibrationsBefore
                val row = JSONObject().put("enabled", enabled).put("completedCallbacks", completed.get())
                    .put("musicPlaybackObserved", playbackObserved).put("vibrations", JSONArray(vibrations.toList()))
                    .put("ownAudioTracksDuringFeedback", JSONArray(ownTracks(audioDuring)))
                    .put("ownAudioTracksAfterCleanup", JSONArray(ownTracks(audioAfter)))
                    .put("cleanupObservedAfterWaitMs", SystemClock.elapsedRealtime() - cleanupStart)
                proof.getJSONArray("cases").put(row)
                File(directory, "proof.json").writeText(proof.toString(2))
                File(directory, "audio-${if (enabled) "on" else "off"}.txt").writeText(audioDuring)
                File(directory, "audio-cleanup-${if (enabled) "on" else "off"}.txt").writeText(audioAfter)
                assertEquals(enabled, playbackObserved)
                if (enabled) {
                    assertTrue(ownTracks(audioDuring).isNotEmpty())
                    assertTrue(vibrations.toString(), vibrations.any { it.contains("status: finished") && it.contains("duration=200") })
                } else assertTrue(vibrations.toString(), vibrations.isEmpty())
                assertTrue("Own audio resource must be released", ownTracks(audioAfter).isEmpty())
                val quietDeadline = SystemClock.elapsedRealtime() + 5_000L
                while (audio.isMusicActive && SystemClock.elapsedRealtime() < quietDeadline) SystemClock.sleep(20)
                assertFalse("Audio must be idle before the next case", audio.isMusicActive)
            }
            val outputs = JSONArray()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "_display_name"), "relative_path=?", arrayOf(relative + "Processed/"), null)?.use { cursor ->
                assertEquals(2, cursor.count)
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val bitmap = context.contentResolver.openInputStream(uri).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    try { assertEquals(64, bitmap.width); assertEquals(64, bitmap.height) }
                    finally { bitmap.recycle() }
                    outputs.put(cursor.getString(1))
                }
            } ?: fail("Own PNG rows unavailable")
            assertEquals(2, results.distinct().size)
            sourceHashes.forEach { (uri, expected) -> assertEquals(expected, hash(uri)) }
            assertEquals(originalSession, ShootingSessionStore(context).load())
            proof.put("outputs", outputs).put("decodedOutputWidth", 64).put("decodedOutputHeight", 64)
                .put("sourcesUnchanged", true).put("activeSessionUnchanged", true)
            File(directory, "proof.json").writeText(proof.toString(2))
        } finally {
            SessionProcessingCoordinator.cancel(folder)
            runBlocking {
                withTimeout(10_000) {
                    while (SessionProcessingCoordinator.states.value[folder]?.running == true) delay(20)
                }
            }
            ProcessingRecoveryStore(context).markFinished(folder)
            settingsStore.save(originalSettings)
        }
    }
}
