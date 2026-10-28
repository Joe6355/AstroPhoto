package com.joe6355.astrophoto

import android.content.Context
import android.os.Build
import androidx.core.content.edit

internal data class ManualCapabilityOverrides(
    val maximumExposureNs: Long? = null
)

internal class ManualCameraCapabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "astrophoto_manual_camera_capabilities",
        Context.MODE_PRIVATE
    )

    fun load(cameraId: String): ManualCapabilityOverrides {
        val prefix = keyPrefix(cameraId)
        // A capture's reported ISO is an observation, not proof of a global hardware limit.
        // Leave legacy *_iso_min/max preferences intact, but never use them to hide ISO values.
        return ManualCapabilityOverrides(
            maximumExposureNs = preferences.getLong("${prefix}_exposure_max", 0L)
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

    private fun keyPrefix(cameraId: String): String =
        "${Build.FINGERPRINT}|$cameraId"
}
