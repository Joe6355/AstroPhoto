package com.joe6355.astrophoto

import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.exifinterface.media.ExifInterface
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.joe6355.astrophoto.ui.theme.AstroPhotoTheme
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.math.abs

/** Runs only after the driver has obtained the owner's covered-lens confirmation. */
@SdkSuppress(minSdkVersion = 29)
class CoveredDarkFramesDeviceTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(180)
    @get:Rule(order = 1) val foreground = AstroUiForegroundRule()
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<AstroUiTestActivity>()

    @Test fun actualCameraUiSavesThreeDarkFramesAfterCoveredLensConfirmation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("coveredDarkAudit") == "true")
        assumeTrue(args.getString("coveredLensConfirmed") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.joe6355.astrophoto.qa20261017")
        val tag = requireNotNull(args.getString("coveredDarkTag"))
        require(tag.matches(Regex("[A-Za-z0-9_]{1,40}")))
        val directory = File(context.cacheDir, "covered-darks-audit/$tag")
        require(!directory.exists() && directory.mkdirs())
        val settingsStore = CameraSettingsStore(context)
        val originalSettings = settingsStore.load()
        val sessionStore = ShootingSessionStore(context)
        val originalSession = sessionStore.load()
        val session = ShootingSession("QA covered darks", "Session_QA_CoveredDarks_${UUID.randomUUID()}",
            System.currentTimeMillis(), "Covered-lens audit; own frames only")
        val proof = JSONObject().put("folder", session.folderName).put("tag", tag)
            .put("requestedIso", 400).put("requestedExposureNs", 33_333_333L)
        val showCamera = mutableStateOf(true)
        try {
            settingsStore.save(originalSettings.copy(
                iso = 400, exposureTimeNs = 33_333_333L, focusMode = "INFINITY", focusDistance = 0f,
                darkFramesCount = 3, darkFramesFormat = "JPEG", panelExpanded = true,
                applyLongExposureToPreview = false, histogramEnabled = false,
                astroModeEnabled = false, vibrationAfterSeries = false, soundAfterSeries = false,
                jpegQuality = 92, startTimerSeconds = 0
            ))
            sessionStore.save(session)
            composeRule.setContent {
                AstroPhotoTheme {
                    if (showCamera.value) CameraScreen(onBackToDiagnostics = {}, onOpenHelp = {})
                }
            }
            composeRule.onNodeWithText("Снять тёмные кадры").performScrollTo()
            composeRule.waitUntil(20_000L) {
                runCatching { composeRule.onNodeWithText("Снять тёмные кадры").assertIsEnabled() }.isSuccess
            }
            fun ownCameraLog(): String = ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "logcat -d --pid=${android.os.Process.myPid()} -s AstroPhotoCamera:D"
                )
            ).bufferedReader().use { it.readText() }
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (!ownCameraLog().contains("session configured") && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            check(ownCameraLog().contains("session configured")) { "Camera session did not configure" }
            proof.put("cameraSessionConfiguredBeforeCapture", true)
            File(directory, "ready.json").writeText(proof.toString(2))
            proof.put("ownerCoveredLensConfirmedBeforeCapture", true)
            File(directory, "capture-started.json").writeText(proof.toString(2))
            composeRule.onNodeWithText("Снять тёмные кадры").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(35_000L) { sessionStore.load()?.darkFrames == 3 }
            composeRule.onNodeWithText("Снять тёмные кадры").assertIsEnabled()
            val completed = requireNotNull(sessionStore.load())
            assertEquals(session.folderName, completed.folderName)
            assertEquals(0, completed.lightFrames)
            assertEquals(3, completed.darkFrames)
            val frames = JSONArray()
            val relative = "Pictures/AstroPhoto/${session.folderName}/Darks/JPEG/"
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "_display_name"), "relative_path=?", arrayOf(relative), "_display_name ASC")?.use { cursor ->
                assertEquals(3, cursor.count)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    assertTrue(name, name.matches(Regex("DarkFrames_\\d{8}_\\d{6}_00[123]\\.jpg")))
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val exif = context.contentResolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)) }
                    val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                    val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
                    assertEquals(400, iso)
                    assertTrue("Actual exposure differs: $exposure", abs(exposure - 1.0 / 30.0) < 0.003)
                    val bitmap = context.contentResolver.openInputStream(uri).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val histogram = IntArray(256)
                    var sum = 0L
                    for (pixel in pixels) {
                        val value = (54 * ((pixel ushr 16) and 255) + 183 * ((pixel ushr 8) and 255) + 19 * (pixel and 255)) ushr 8
                        histogram[value]++
                        sum += value
                    }
                    var cumulative = 0L
                    val p99 = histogram.indices.first { value -> cumulative += histogram[value]; cumulative >= pixels.size * 0.99 }
                    frames.put(JSONObject().put("file", name).put("uri", uri.toString())
                        .put("width", bitmap.width).put("height", bitmap.height)
                        .put("iso", iso).put("exposureSeconds", exposure)
                        .put("meanLuma0to255", sum.toDouble() / pixels.size).put("p99Luma0to255", p99)
                        .put("fractionLumaAtMost8", histogram.take(9).sum().toDouble() / pixels.size))
                    bitmap.recycle()
                }
            } ?: fail("Own dark JPEG rows unavailable")
            proof.put("frames", frames).put("darkFrames", 3).put("lightFrames", 0)
                .put("completed", true).put("astronomicalCalibrationQualityEvaluated", false)
            File(directory, "proof.json").writeText(proof.toString(2))
        } finally {
            composeRule.runOnIdle { showCamera.value = false }
            composeRule.waitForIdle()
            settingsStore.save(originalSettings)
            if (originalSession != null) sessionStore.save(originalSession) else {
                check(sessionStore.load()?.folderName == session.folderName) { "Do not remove another active session" }
                check(context.deleteSharedPreferences("astrophoto_shooting_session"))
            }
        }
    }
}
