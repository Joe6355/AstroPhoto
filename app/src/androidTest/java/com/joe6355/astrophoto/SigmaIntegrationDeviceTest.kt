package com.joe6355.astrophoto

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import java.util.UUID
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in QA: a temporary learned limit exercises Sigma while Camera2 captures real exposures. */
@SdkSuppress(minSdkVersion = 29)
class SigmaIntegrationDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun sigmaThirtySecondsPlansAndCapturesThreeRealTenSecondFrames() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("sigmaIntegrationAudit") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(args.getString("sigmaIntegrationTag"))
        require(tag.matches(Regex("[A-Za-z0-9_]{1,40}")))
        val directory = File(context.cacheDir, "sigma-integration-audit/$tag")
        require(!directory.exists() && directory.mkdirs())
        val activities = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun serviceRunning() = activities.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SeriesCaptureForegroundService::class.java.name
        }
        check(!serviceRunning()) { "Do not interrupt an existing series" }
        val settings = CameraSettingsStore(context)
        val originalSettings = settings.load()
        val sessions = ShootingSessionStore(context)
        val originalSession = sessions.load()
        val prefsName = "astrophoto_manual_camera_capabilities"
        val prefsExisted = File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml").exists()
        val overrides = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val originalOverrides = overrides.all.toMap()
        val capabilityStore = ManualCameraCapabilityStore(context)
        val manager = context.getSystemService(CameraManager::class.java)
        val candidates = readRearCameraCandidates(manager, Build.MANUFACTURER, Build.MODEL) {
            capabilityStore.load(it).maximumExposureNs
        }
        val cameraId = requireNotNull(selectBestRearCameraId(candidates))
        val nativeRange = requireNotNull(manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
        require(nativeRange.contains(10_000_000_000L)) { "This audit requires a real ten-second exposure" }
        val touchedIds = candidates.filter { (it.maximumExposureNs ?: 0L) > 10_000_000_000L }
            .map { it.cameraId }
        require(touchedIds.isNotEmpty())
        val session = ShootingSession("QA Sigma 30s", "Session_QA_Sigma30_${UUID.randomUUID()}",
            System.currentTimeMillis(), "Temporary ten-second QA limit; real Camera2 series")
        val showCamera = mutableStateOf(true)
        val proof = JSONObject().put("folder", session.folderName).put("tag", tag)
            .put("processId", android.os.Process.myPid())
            .put("originalCameraId", cameraId).put("nativeExposureMaximumNs", nativeRange.upper)
            .put("temporaryQaExposureMaximumNs", 10_000_000_000L)
            .put("limitedRearIds", JSONArray(touchedIds))
            .put("physicalNativeLimitBelowThirtySecondsTested", false)
        try {
            // Limit all long-exposure rear candidates so selection cannot escape to another ID.
            touchedIds.forEach { capabilityStore.limitMaximumExposure(it, 10_000_000_000L) }
            val limitedId = readCameraDiagnostics(context).getOrThrow().rows.single { it.name == "Camera ID" }.value
            assertEquals("Keep the same physical capture camera", cameraId, limitedId)
            proof.put("cameraIdUnderLimit", limitedId)
            settings.save(originalSettings.copy(iso = 400, exposureTimeNs = 33_333_333L,
                seriesFrameCount = 40, seriesDelaySeconds = 0, startTimerSeconds = 0,
                focusMode = "INFINITY", focusDistance = 0f, jpegQuality = 92,
                panelExpanded = true, histogramEnabled = false, astroModeEnabled = false,
                applyLongExposureToPreview = false, fastPreviewEnabled = true,
                vibrationAfterSeries = false, soundAfterSeries = false))
            sessions.save(session)
            composeRule.setContent {
                AstroPhotoTheme {
                    if (showCamera.value) CameraScreen(onBackToDiagnostics = {}, onOpenHelp = {})
                }
            }
            composeRule.waitUntil(20_000L) {
                composeRule.onAllNodesWithText("Σ 30 сек").fetchSemanticsNodes().size == 1
            }
            // Reveal the vertical section before scrolling its nested horizontal preset row.
            composeRule.onNodeWithText("Ручная выдержка").performScrollTo()
            composeRule.onNodeWithText("Σ 30 сек").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            composeRule.waitUntil(5_000L) {
                settings.load().let { it.seriesFrameCount == 3 && it.exposureTimeNs == 10_000_000_000L }
            }
            assertEquals("SERIES", settings.load().captureMode)
            assertEquals("JPEG", settings.load().seriesFormat)
            proof.put("uiPlannedFrameCount", 3).put("uiPlannedExposureNs", settings.load().exposureTimeNs)
            composeRule.onNodeWithText("Старт серии").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(5_000L) {
                runCatching { composeRule.onNodeWithText("Перед запуском серии").assertIsDisplayed() }.isSuccess
            }
            composeRule.onNodeWithText("3 кадров × 10 сек").assertIsDisplayed()
            composeRule.onNodeWithText("Ожидаемое время: ≈ 30 сек").assertIsDisplayed()
            proof.put("confirmationDialogMatchesPlan", true)
            fun cameraLog(): String = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "logcat -d --pid=${android.os.Process.myPid()} -s AstroPhotoCamera:D"
                )
            ).bufferedReader().use { it.readText() }
            val readyDeadline = SystemClock.elapsedRealtime() + 15_000L
            while (!cameraLog().contains("session configured") && SystemClock.elapsedRealtime() < readyDeadline) {
                SystemClock.sleep(100)
            }
            check(cameraLog().contains("session configured")) { "Camera session did not configure" }
            proof.put("cameraConfiguredBeforeCapture", true)
            File(directory, "planned.json").writeText(proof.toString(2))
            composeRule.onAllNodesWithText("Начать серию").filter(hasAnyAncestor(isDialog()))[0].performClick()
            composeRule.waitUntil(95_000L) {
                val state = SeriesCaptureCoordinator.state.value
                state?.running == false && state.current == 3 && state.total == 3
            }
            val state = requireNotNull(SeriesCaptureCoordinator.state.value)
            assertTrue(state.message.orEmpty(), state.message.orEmpty().startsWith("Серия завершена"))
            composeRule.waitUntil(5_000L) { sessions.load()?.lightFrames == 3 }
            assertEquals(0, requireNotNull(sessions.load()).darkFrames)
            val rows = JSONArray()
            var totalExposure = 0.0
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "_display_name"), "relative_path=?",
                arrayOf("Pictures/AstroPhoto/${session.folderName}/Lights/JPEG/"), "_display_name ASC")?.use { cursor ->
                assertEquals(3, cursor.count)
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val exif = context.contentResolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)) }
                    val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
                    val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                    assertTrue("Actual exposure: $exposure", abs(exposure - 10.0) < 0.1)
                    assertEquals(400, iso)
                    val bitmap = context.contentResolver.openInputStream(uri).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    rows.put(JSONObject().put("file", cursor.getString(1)).put("width", bitmap.width)
                        .put("height", bitmap.height).put("exposureSeconds", exposure).put("iso", iso))
                    bitmap.recycle()
                    totalExposure += exposure
                }
            } ?: fail("Own series JPEG rows unavailable")
            assertTrue("Actual total exposure: $totalExposure", totalExposure >= 29.7)
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (serviceRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(!serviceRunning())
            proof.put("frames", rows).put("actualTotalExposureSeconds", totalExposure)
                .put("lightFrames", 3).put("darkFrames", 0).put("completed", true)
            File(directory, "proof.json").writeText(proof.toString(2))
        } catch (error: Throwable) {
            runCatching {
                File(directory, "failure-tree.txt").writeText(composeRule.onRoot(useUnmergedTree = true).printToString())
            }
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "failure.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            throw error
        } finally {
            if (serviceRunning()) {
                instrumentation.runOnMainSync { SeriesCaptureCoordinator.requestStop(context) }
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                while (serviceRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            }
            check(!serviceRunning()) { "Capture still active; inspect before restoring" }
            composeRule.runOnIdle { showCamera.value = false }
            composeRule.waitForIdle()
            settings.save(originalSettings)
            if (originalSession != null) sessions.save(originalSession) else {
                check(sessions.load()?.folderName == session.folderName)
                check(context.deleteSharedPreferences("astrophoto_shooting_session"))
            }
            val restore = overrides.edit()
            for (id in touchedIds) {
                val key = "${Build.FINGERPRINT}|${id}_exposure_max"
                when (val value = originalOverrides[key]) {
                    null -> restore.remove(key)
                    is Long -> restore.putLong(key, value)
                    else -> error("Unexpected original exposure preference type")
                }
            }
            check(restore.commit())
            assertEquals(originalOverrides, overrides.all)
            if (!prefsExisted) {
                check(originalOverrides.isEmpty())
                check(context.deleteSharedPreferences(prefsName))
            }
        }
    }
}
