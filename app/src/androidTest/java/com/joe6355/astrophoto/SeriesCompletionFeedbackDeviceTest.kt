package com.joe6355.astrophoto

import android.app.ActivityManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in real Camera2 completion; only a new audit directory is written. */
class SeriesCompletionFeedbackDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(100)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()

    @Test fun completedSeriesUsesRealFeedbackAndHonorsDisabledFlags() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("seriesFeedbackAudit") == "true")
        val context = instrumentation.targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val audio = context.getSystemService(AudioManager::class.java)
        val activities = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun serviceRunning() = activities.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SeriesCaptureForegroundService::class.java.name
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        fun ownVibrations() = shell("dumpsys vibrator_manager").lineSequence()
            .filter { it.contains("opPkg: ${context.packageName},") }.map(String::trim).toSet()
        fun ownAudioTracks(dump: String) = Regex(
            """(?m)^\s*\S+\s+\d+\s+(?:yes|no)\s+${android.os.Process.myPid()}\s+.*$"""
        ).findAll(dump).map { it.value.trim() }.toList()
        fun awaitAudioIdle() {
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (audio.isMusicActive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(!audio.isMusicActive) { "Audio is active; do not attribute another app's playback to this test" }
        }
        fun awaitIdle() {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (serviceRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(!serviceRunning()) { "Previous capture service is still active" }
        }
        check(!serviceRunning()) { "Do not interrupt an existing capture" }
        check(!audio.isMusicActive) { "Audio is already playing; do not attribute another app's playback to this test" }
        val originalSession = ShootingSessionStore(context).load()
        val folder = "Session_QA_Feedback_${System.currentTimeMillis()}"
        val relative = "AstroPhoto/$folder/Lights/JPEG"
        val mediaRelative = "Pictures/$relative/"
        val directory = File(context.cacheDir, "series_feedback_20261101").apply { mkdirs() }
        val proof = JSONObject().put("folder", folder)
            .put("musicVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("musicVolumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("ringerMode", audio.ringerMode).put("cases", JSONArray())
        try {
            for (enabled in listOf(true, false)) {
                awaitIdle()
                SystemClock.sleep(600)
                awaitAudioIdle()
                val before = ownVibrations()
                val request = SeriesCaptureRequest(
                    format = "JPEG", frameCount = 1, delaySeconds = 0, startTimerSeconds = 0,
                    exposureTimeNs = 33_333_333L, iso = 400, focusDistance = 0f, focusMode = "AF",
                    jpegQuality = 92, sessionFolder = folder, relativeDirectory = relative,
                    filePrefix = if (enabled) "QA_feedback_on" else "QA_feedback_off",
                    vibrationAfterSeries = enabled, soundAfterSeries = enabled
                )
                instrumentation.runOnMainSync { SeriesCaptureCoordinator.start(context, request) }
                var musicObserved = false
                var audioSnapshot = ""
                val deadline = SystemClock.elapsedRealtime() + 35_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (audio.isMusicActive) {
                        musicObserved = true
                        if (audioSnapshot.isEmpty()) audioSnapshot = shell("dumpsys media.audio_flinger")
                    }
                    if (SeriesCaptureCoordinator.state.value?.running == false) break
                    SystemClock.sleep(5)
                }
                val state = requireNotNull(SeriesCaptureCoordinator.state.value)
                assertFalse(state.toString(), state.running)
                assertTrue(state.message.orEmpty(), state.message.orEmpty().startsWith("Серия завершена"))
                assertEquals(1, state.current)
                // Tone may still be playing after the coordinator publishes completion.
                val feedbackDeadline = SystemClock.elapsedRealtime() + 400L
                while (SystemClock.elapsedRealtime() < feedbackDeadline) {
                    if (audio.isMusicActive) {
                        musicObserved = true
                        if (audioSnapshot.isEmpty()) audioSnapshot = shell("dumpsys media.audio_flinger")
                    }
                    SystemClock.sleep(5)
                }
                awaitIdle()
                SystemClock.sleep(600)
                instrumentation.runOnMainSync { /* Drain due main-looper cleanup callbacks. */ }
                val releaseWaitStarted = SystemClock.elapsedRealtime()
                var releaseSnapshot = shell("dumpsys media.audio_flinger")
                var tracksAfterRelease = ownAudioTracks(releaseSnapshot)
                val initialTracksAfterCompletion = tracksAfterRelease
                while (tracksAfterRelease.isNotEmpty() &&
                    SystemClock.elapsedRealtime() - releaseWaitStarted < 5_000L) {
                    SystemClock.sleep(100)
                    releaseSnapshot = shell("dumpsys media.audio_flinger")
                    tracksAfterRelease = ownAudioTracks(releaseSnapshot)
                }
                val vibrations = ownVibrations() - before
                val row = JSONObject().put("enabled", enabled).put("musicPlaybackObserved", musicObserved)
                    .put("vibrations", JSONArray(vibrations.toList())).put("savedFrames", state.current)
                    .put("globalMusicActiveAfterCleanup", audio.isMusicActive)
                    .put("ownAudioTracksDuringFeedback", JSONArray(ownAudioTracks(audioSnapshot)))
                    .put("initialAudioTracksAfterCompletion", JSONArray(initialTracksAfterCompletion))
                    .put("audioReleaseObservedAfterWaitMs", SystemClock.elapsedRealtime() - releaseWaitStarted)
                    .put("ownAudioTracksAfterRelease", JSONArray(tracksAfterRelease))
                proof.getJSONArray("cases").put(row)
                File(directory, "proof.json").writeText(proof.toString(2))
                if (audioSnapshot.isNotEmpty()) File(directory, "audio-${if (enabled) "on" else "off"}.txt")
                    .writeText(audioSnapshot)
                File(directory, "audio-released-${if (enabled) "on" else "off"}.txt").writeText(releaseSnapshot)
                if (enabled) {
                    assertTrue("No real media playback during completed series", musicObserved)
                    assertTrue("No native audio track owned by QA", ownAudioTracks(audioSnapshot).isNotEmpty())
                    assertTrue("No completed 200ms vibration: $vibrations", vibrations.any {
                        it.contains("status: finished") && it.contains("duration=200")
                    })
                } else {
                    assertFalse("Disabled sound must not play", musicObserved)
                    assertTrue("Disabled vibration must not issue a request: $vibrations", vibrations.isEmpty())
                }
                assertTrue("Completion must release QA's own audio track: $tracksAfterRelease", tracksAfterRelease.isEmpty())
                awaitAudioIdle()
            }
            val sizes = JSONArray()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf(mediaRelative), null)?.use { cursor ->
                assertEquals(2, cursor.count)
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                    assertNotNull("Saved JPEG must fully decode", bitmap)
                    sizes.put(JSONObject().put("file", cursor.getString(1)).put("width", bitmap!!.width).put("height", bitmap.height))
                    bitmap.recycle()
                }
            } ?: fail("Own captured JPEG rows unavailable")
            assertEquals(originalSession, ShootingSessionStore(context).load())
            proof.put("decodedJpegs", sizes).put("activeSessionUnchanged", true)
            File(directory, "proof.json").writeText(proof.toString(2))
        } finally {
            // This test checked that no capture service existed before it began.
            if (serviceRunning()) context.stopService(Intent(context, SeriesCaptureForegroundService::class.java))
        }
    }
}
