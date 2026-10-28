package com.joe6355.astrophoto

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces a legacy learned ISO limit in QA, restoring every touched preference afterward. */
@RunWith(AndroidJUnit4::class)
class ManualIsoRangeDeviceTest {
    @Test
    fun legacyCaptureObservationsDoNotHideAdvertisedIsoValues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Explicit QA camera audit only",
            InstrumentationRegistry.getArguments().getString("auditIsoRange") == "true" &&
                context.packageName == "com.joe6355.astrophoto.qa20261017")
        val initial = readCameraDiagnostics(context).getOrThrow()
        val cameraId = initial.rows.single { it.name == "Camera ID" }.value
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensor = requireNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
        val boost = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
        assumeTrue("A nominal boost is required for this native-range assertion", boost == null || boost.contains(100))
        assumeTrue("A nontrivial sensor ISO range is required", sensor.lower < sensor.upper)
        val prefs = context.getSharedPreferences("astrophoto_manual_camera_capabilities", Context.MODE_PRIVATE)
        val prefix = "${Build.FINGERPRINT}|$cameraId"
        val minimumKey = "${prefix}_iso_min"
        val maximumKey = "${prefix}_iso_max"
        val sentinelKey = "qa_iso_range_audit_unrelated"
        val touched = listOf(minimumKey, maximumKey, sentinelKey)
        val previous = prefs.all.toMap()
        val cameraSettings = context.getSharedPreferences("astrophoto_camera_settings", Context.MODE_PRIVATE)
        val sessionSettings = context.getSharedPreferences("astrophoto_shooting_session", Context.MODE_PRIVATE)
        val savedCamera = cameraSettings.all.toMap()
        val savedSession = sessionSettings.all.toMap()
        val savedExposureLimit = ManualCameraCapabilityStore(context).load(cameraId).maximumExposureNs
        try {
            val staleMinimum = (sensor.upper.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            assertTrue(prefs.edit().putInt(minimumKey, staleMinimum).remove(maximumKey)
                .putString(sentinelKey, "keep").commit())
            val afterMinimum = availableIso(context, cameraId)
            assertEquals("A previous capture must not hide native low ISO", sensor.lower, afterMinimum.first)
            assertTrue("Native high ISO must remain available", afterMinimum.last >= sensor.upper)
            assertEquals("Legacy observations must not be erased", staleMinimum, prefs.getInt(minimumKey, 0))

            val staleMaximum = sensor.lower + (sensor.upper - sensor.lower) / 2
            assertTrue(prefs.edit().remove(minimumKey).putInt(maximumKey, staleMaximum).commit())
            val afterMaximum = availableIso(context, cameraId)
            assertEquals(sensor.lower, afterMaximum.first)
            assertTrue("A previous capture must not hide native high ISO", afterMaximum.last >= sensor.upper)
            assertEquals(staleMaximum, prefs.getInt(maximumKey, 0))
            assertEquals("Exposure safety limits must survive", savedExposureLimit,
                ManualCameraCapabilityStore(context).load(cameraId).maximumExposureNs)
            assertEquals("keep", prefs.getString(sentinelKey, null))
            assertEquals("Camera settings must not be reset", savedCamera, cameraSettings.all)
            assertEquals("The active session must not be changed", savedSession, sessionSettings.all)
            val proof = JSONObject().apply {
                put("status", "passed")
                put("cameraId", cameraId)
                put("sensorMinimum", sensor.lower)
                put("sensorMaximum", sensor.upper)
                put("seededLegacyMinimum", staleMinimum)
                put("seededLegacyMaximum", staleMaximum)
                put("availableMinimum", afterMinimum.first)
                put("availableMaximum", afterMinimum.last)
                put("cameraAndSessionSettingsUnchanged", true)
                put("exposureLimitPreserved", true)
            }
            context.cacheDir.resolve("manual-iso-range-proof.json").writeText(proof.toString(2))
        } finally {
            val restore = prefs.edit()
            for (key in touched) {
                when (val value = previous[key]) {
                    null -> restore.remove(key)
                    is Int -> restore.putInt(key, value)
                    is String -> restore.putString(key, value)
                    else -> error("Unexpected original audit preference type: $key")
                }
            }
            assertTrue("Restore only the temporary audit keys", restore.commit())
            assertEquals("All calibration preferences must be restored", previous, prefs.all)
        }
    }

    private fun availableIso(context: Context, cameraId: String): IntRange {
        val diagnostic = readCameraDiagnostics(context).getOrThrow()
        assertEquals(cameraId, diagnostic.rows.single { it.name == "Camera ID" }.value)
        val text = diagnostic.rows.single { it.name == "Эффективный ISO" }.value
        val values = requireNotNull(Regex("(\\d+)\\s*-\\s*(\\d+)").matchEntire(text)) {
            "Unexpected effective ISO diagnostic: $text"
        }
        return values.groupValues[1].toInt()..values.groupValues[2].toInt()
    }
}
