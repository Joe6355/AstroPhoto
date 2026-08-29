package com.example.astrophoto

import android.content.Context
import android.os.Build
import androidx.core.content.edit

internal data class ManualCapabilityOverrides(
    val maximumExposureNs: Long? = null,
    val minimumIso: Int? = null,
    val maximumIso: Int? = null
)

internal class ManualCameraCapabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "astrophoto_manual_camera_capabilities",
        Context.MODE_PRIVATE
    )

    fun load(cameraId: String): ManualCapabilityOverrides {
        val prefix = keyPrefix(cameraId)
        return ManualCapabilityOverrides(
            maximumExposureNs = preferences.getLong("${prefix}_exposure_max", 0L)
                .takeIf { it > 0L },
            minimumIso = preferences.getInt("${prefix}_iso_min", 0)
                .takeIf { it > 0 },
            maximumIso = preferences.getInt("${prefix}_iso_max", 0)
                .takeIf { it > 0 }
        )
    }

    fun limitMaximumExposure(cameraId: String, maximumExposureNs: Long) {
        if (maximumExposureNs <= 0L) return
        val key = "${keyPrefix(cameraId)}_exposure_max"
        val current = preferences.getLong(key, 0L)
        if (current == 0L || maximumExposureNs < current) {
            preferences.edit { putLong(key, maximumExposureNs) }
        }
    }

    fun limitIsoRange(cameraId: String, minimumIso: Int? = null, maximumIso: Int? = null) {
        val prefix = keyPrefix(cameraId)
        preferences.edit {
            minimumIso?.takeIf { it > 0 }?.let { value ->
                val key = "${prefix}_iso_min"
                val current = preferences.getInt(key, 0)
                if (current == 0 || value > current) putInt(key, value)
            }
            maximumIso?.takeIf { it > 0 }?.let { value ->
                val key = "${prefix}_iso_max"
                val current = preferences.getInt(key, 0)
                if (current == 0 || value < current) putInt(key, value)
            }
        }
    }

    private fun keyPrefix(cameraId: String): String =
        "${Build.FINGERPRINT}|$cameraId"
}
