package com.joe6355.astrophoto

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.exifinterface.media.ExifInterface
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in real camera acceptance. Only a new QA session is written; existing settings are restored. */
@SdkSuppress(minSdkVersion = 29)
class CameraHardwareAcceptanceDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(180)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun capturesManualParametersStopsFromNotificationAndStacksOwnFrames() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cameraHardwareAudit") == "true")
        require(Build.MODEL == args.getString("cameraHardwareExpectedModel"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(args.getString("cameraHardwareTag"))
        require(tag.matches(Regex("[A-Za-z0-9_]{1,40}")))
        val directory = File(context.cacheDir, "camera-hardware-audit/$tag")
        require(!directory.exists() && directory.mkdirs())
        val activities = context.getSystemService(ActivityManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        @Suppress("DEPRECATION")
        fun serviceRunning() = activities.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SeriesCaptureForegroundService::class.java.name
        }
        fun notification() = notifications.activeNotifications.firstOrNull {
            it.notification.channelId == "series_capture"
        }
        check(!serviceRunning()) { "Do not interrupt an existing series" }
        val settings = CameraSettingsStore(context)
        val originalSettings = settings.load()
        val sessions = ShootingSessionStore(context)
        val originalSession = sessions.load()
        val diagnosticRows = readCameraDiagnostics(context).getOrThrow().rows
        val cameraId = diagnosticRows.single { it.name == "Camera ID" }.value
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val isoRange = requireNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
        val exposureRange = requireNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
        val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        check(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true)
        val folder = "Session_QA_Hardware_${UUID.randomUUID()}"
        val session = ShootingSession("QA hardware $tag", folder, System.currentTimeMillis(),
            "Own camera acceptance frames; existing photographs excluded")
        val proof = JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("folder", folder).put("cameraId", cameraId).put("tag", tag)
            .put("diagnostics", JSONArray(diagnosticRows.map {
                JSONObject().put("name", it.name).put("value", it.value)
            })).put("nightQualityTested", false).put("focusSharpnessTested", false)
        val showCamera = mutableStateOf(false)
        val scenarios = JSONArray()
        fun saveProof() { File(directory, "proof.json").writeText(proof.toString(2)) }
        fun await(description: String, timeoutMillis: Long = 20_000L, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(predicate()) { "$description: ${SeriesCaptureCoordinator.state.value}" }
        }
        fun configuredSessions(): Int = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "logcat -d --pid=${android.os.Process.myPid()} -s AstroPhotoCamera:D"
            )
        ).bufferedReader().use { it.readText().split("session configured").size - 1 }
        fun frames(): List<SessionFrame> {
            val result = mutableListOf<SessionFrame>()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "_display_name", "_size"), "relative_path=?",
                arrayOf("Pictures/AstroPhoto/$folder/Lights/JPEG/"), "_id ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    result += SessionFrame(cursor.getString(0), cursor.getString(1),
                        SessionFrameCategory.LIGHTS_JPEG, cursor.getLong(2), 0L,
                        "Pictures/AstroPhoto/$folder/Lights/JPEG/${cursor.getString(1)}", uri.toString(), null)
                }
            } ?: error("Own session unavailable")
            return result
        }
        fun hash(frame: SessionFrame): String = context.contentResolver
            .openInputStream(android.net.Uri.parse(requireNotNull(frame.contentUri))).use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var count = requireNotNull(stream).read(buffer)
                while (count >= 0) { digest.update(buffer, 0, count); count = stream.read(buffer) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        var averageFrames = emptyList<SessionFrame>()
        var dimensions: Pair<Int, Int>? = null
        try {
            sessions.save(session)
            composeRule.setContent {
                AstroPhotoTheme { if (showCamera.value) CameraScreen(onBackToDiagnostics = {}, onOpenHelp = {}) }
            }
            data class Scenario(val name: String, val iso: Int, val exposure: Long,
                val focus: String, val count: Int, val stop: Boolean = false)
            val cases = listOf(
                Scenario("af", 100, 16_666_667L, "AF", 1),
                Scenario("manual", 400, 4_000_000L, if (minimumFocusDistance > 0f) "MF" else "INFINITY", 1),
                Scenario("series", 400, 33_333_333L, "INFINITY", 3),
                Scenario("notification_stop", 400, 33_333_333L, "INFINITY", 5, stop = true)
            )
            for (case in cases) {
                val before = frames().map { it.key }.toSet()
                val iso = isoRange.clamp(case.iso)
                val exposure = exposureRange.clamp(case.exposure)
                val configuredBefore = configuredSessions()
                settings.save(originalSettings.copy(iso = iso, exposureTimeNs = exposure,
                    seriesFrameCount = case.count, seriesDelaySeconds = if (case.stop) 3 else 0,
                    startTimerSeconds = 0, captureMode = "SERIES", seriesFormat = "JPEG",
                    focusMode = case.focus, focusDistance = if (case.focus == "MF") minimumFocusDistance / 2f else 0f,
                    jpegQuality = 100, panelExpanded = true, histogramEnabled = false,
                    astroModeEnabled = false, applyLongExposureToPreview = false, fastPreviewEnabled = true,
                    vibrationAfterSeries = false, soundAfterSeries = false))
                composeRule.runOnIdle { showCamera.value = true }
                composeRule.waitUntil(20_000L) { configuredSessions() > configuredBefore }
                composeRule.onNodeWithText("Старт серии").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    composeRule.onAllNodesWithText("Перед запуском серии").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onAllNodesWithText("Начать серию").filter(hasAnyAncestor(isDialog()))[0].performClick()
                if (case.stop) {
                    await("First frame saved and pause started") {
                        SeriesCaptureCoordinator.state.value?.let {
                            it.running && it.status == "Пауза..." && it.current == 1
                        } == true && frames().size == before.size + 1 && notification() != null
                    }
                    requireNotNull(notification()).notification.actions.single {
                        it.title.toString() == "Остановить"
                    }.actionIntent.send()
                }
                val expectedCount = if (case.stop) 1 else case.count
                await("Series finished", 45_000L) {
                    SeriesCaptureCoordinator.state.value?.let {
                        !it.running && it.current == expectedCount && it.total == case.count
                    } == true && frames().size == before.size + expectedCount
                }
                val state = requireNotNull(SeriesCaptureCoordinator.state.value)
                assertTrue(state.message.orEmpty(), state.message.orEmpty().startsWith(
                    if (case.stop) "Серия остановлена" else "Серия завершена"))
                await("Capture service and notification removed") { !serviceRunning() && notification() == null }
                val added = frames().filter { it.key !in before }
                assertEquals(expectedCount, added.size)
                val files = JSONArray()
                scenarios.put(JSONObject().put("name", case.name).put("focus", case.focus)
                    .put("requestedIso", iso).put("requestedExposureNs", exposure)
                    .put("plannedCount", case.count).put("savedCount", added.size).put("files", files))
                proof.put("scenarios", scenarios)
                added.forEach { frame ->
                    val uri = android.net.Uri.parse(requireNotNull(frame.contentUri))
                    val exif = context.contentResolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)) }
                    val actualExposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
                    val actualIso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                    val bitmap = context.contentResolver.openInputStream(uri).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    try {
                        check(bitmap.width > 0 && bitmap.height > 0)
                        if (case.name == "series") {
                            dimensions = if (exif.rotationDegrees % 180 == 0) bitmap.width to bitmap.height
                                else bitmap.height to bitmap.width
                        }
                        files.put(JSONObject().put("name", frame.fileName).put("contentUri", frame.contentUri)
                            .put("width", bitmap.width).put("height", bitmap.height).put("iso", actualIso)
                            .put("exifRotationDegrees", exif.rotationDegrees)
                            .put("exposureSeconds", actualExposure).put("sha256", hash(frame)))
                    } finally { bitmap.recycle() }
                    saveProof()
                    assertTrue("${case.name} exposure=$actualExposure requested=$exposure",
                        abs(actualExposure - exposure / 1e9) <= maxOf(0.0005, exposure / 1e9 * 0.05))
                    assertTrue("${case.name} ISO=$actualIso requested=$iso", abs(actualIso - iso) <= maxOf(1, iso / 20))
                }
                if (case.name == "series") averageFrames = added
                saveProof()
                composeRule.runOnIdle { showCamera.value = false }
                composeRule.waitForIdle()
            }
            // Wait longer than the cancelled inter-frame pause: no late frame may appear.
            SystemClock.sleep(3_500L)
            assertEquals(6, frames().size)
            assertEquals(6, requireNotNull(sessions.load()).lightFrames)
            val sourceHashes = averageFrames.associate { it.key to hash(it) }
            val summary = SessionSummary(folder, session.sessionName, "Pictures/AstroPhoto/$folder/",
                session.createdAtMillis, 6, 0, 0L, "sessionName: ${session.sessionName}\n")
            val started = SystemClock.elapsedRealtime()
            val result = runBlocking { JpegStacker(context).stack(summary, averageFrames,
                onProgress = { _, _ -> }).getOrThrow() }
            val resultUri = android.net.Uri.parse(requireNotNull(result.contentUri))
            val bitmap = context.contentResolver.openInputStream(resultUri).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try {
                proof.put("average", JSONObject().put("frames", result.frameCount)
                    .put("contentUri", result.contentUri).put("width", bitmap.width).put("height", bitmap.height)
                    .put("elapsedMillis", SystemClock.elapsedRealtime() - started))
                saveProof()
                assertEquals(requireNotNull(dimensions).first, bitmap.width)
                assertEquals(requireNotNull(dimensions).second, bitmap.height)
            } finally { bitmap.recycle() }
            assertEquals(sourceHashes, averageFrames.associate { it.key to hash(it) })
            proof.put("noLateFrameAfterStop", true).put("sourceHashesUnchangedAfterAverage", true)
                .put("completed", true)
        } catch (error: Throwable) {
            proof.put("failure", error.toString())
            runCatching { File(directory, "failure-tree.txt").writeText(composeRule.onRoot(useUnmergedTree = true).printToString()) }
            throw error
        } finally {
            if (serviceRunning()) {
                instrumentation.runOnMainSync { SeriesCaptureCoordinator.requestStop(context) }
                await("Own capture stopped before restoring") { !serviceRunning() }
            }
            composeRule.runOnIdle { showCamera.value = false }
            composeRule.waitForIdle()
            settings.save(originalSettings)
            if (originalSession != null) sessions.save(originalSession) else {
                check(sessions.load()?.folderName == folder)
                sessions.clear()
            }
            assertEquals(originalSettings, settings.load())
            assertEquals(originalSession, sessions.load())
            proof.put("settingsAndActiveSessionRestored", true)
            saveProof()
        }
    }

    @Test fun verifiesExistingOwnCaptureAndAverageWithoutRecapture() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cameraHardwareResumeAudit") == "true")
        require(Build.MODEL == args.getString("cameraHardwareExpectedModel"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(args.getString("cameraHardwareTag"))
        require(tag.matches(Regex("[A-Za-z0-9_]{1,40}")))
        val directory = File(context.cacheDir, "camera-hardware-audit/$tag")
        val original = JSONObject(File(directory, "proof.json").readText())
        val folder = original.getString("folder")
        require(folder.matches(Regex("Session_QA_Hardware_[0-9a-f-]{36}")))
        check(original.getBoolean("settingsAndActiveSessionRestored"))
        val scenarios = original.getJSONArray("scenarios")
        assertEquals(4, scenarios.length())
        val expectedNames = listOf("af", "manual", "series", "notification_stop")
        var expectedDimensions: Pair<Int, Int>? = null
        var count = 0
        for (index in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(index)
            assertEquals(expectedNames[index], scenario.getString("name"))
            val files = scenario.getJSONArray("files")
            assertEquals(if (index == 2) 3 else 1, files.length())
            for (f in 0 until files.length()) {
                val frame = files.getJSONObject(f)
                val uri = android.net.Uri.parse(frame.getString("contentUri"))
                val actualHash = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var read = stream.read(buffer)
                    while (read >= 0) { digest.update(buffer, 0, read); read = stream.read(buffer) }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                assertEquals(frame.getString("sha256"), actualHash)
                val exif = context.contentResolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)) }
                assertEquals(scenario.getInt("requestedIso"), exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1))
                assertEquals(scenario.getLong("requestedExposureNs") / 1e9,
                    exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN), 0.0005)
                if (index == 2) {
                    val width = frame.getInt("width")
                    val height = frame.getInt("height")
                    expectedDimensions = if (exif.rotationDegrees % 180 == 0) width to height else height to width
                }
                count++
            }
        }
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id"), "relative_path=?", arrayOf("Pictures/AstroPhoto/$folder/Lights/JPEG/"), null)!!.use {
            assertEquals(6, it.count)
        }
        val result = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id"), "relative_path=?", arrayOf("Pictures/AstroPhoto/$folder/Processed/"), null)!!.use {
            assertEquals(1, it.count); check(it.moveToFirst())
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
        }
        val bitmap = context.contentResolver.openInputStream(result).use { requireNotNull(BitmapFactory.decodeStream(it)) }
        try {
            assertEquals(requireNotNull(expectedDimensions).first, bitmap.width)
            assertEquals(requireNotNull(expectedDimensions).second, bitmap.height)
            File(directory, "finishing-proof.json").writeText(JSONObject()
                .put("sourceTag", tag).put("folder", folder).put("existingJpegCount", count)
                .put("sourceHashesUnchangedAfterAverage", true).put("noLateFrameAfterStop", true)
                .put("exifOrientedWidth", bitmap.width).put("exifOrientedHeight", bitmap.height)
                .put("averageContentUri", result.toString()).put("averageFullyDecoded", true)
                .put("captureOrProcessingRepeated", false).put("completed", true).toString(2))
        } finally { bitmap.recycle() }
    }
}
