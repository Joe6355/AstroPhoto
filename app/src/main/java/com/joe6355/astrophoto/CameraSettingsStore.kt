package com.joe6355.astrophoto

import android.content.Context
import androidx.core.content.edit

enum class AppThemeMode {
    LIGHT,
    DARK,
    VERY_DARK,
    RED_NIGHT
}

data class SavedCameraSettings(
    val exposureTimeNs: Long = 33_333_333L,
    val iso: Int = 400,
    val focusDistance: Float = 0f,
    val focusMode: String = "INFINITY",
    val applyLongExposureToPreview: Boolean = false,
    val singleFormat: String = "JPEG",
    val seriesFormat: String = "JPEG",
    val darkFramesFormat: String = "JPEG",
    val darkFramesCount: Int = 3,
    val captureMode: String = "SERIES",
    val seriesFrameCount: Int = 3,
    val seriesDelaySeconds: Int = 0,
    val startTimerSeconds: Int = 0,
    val astroModeEnabled: Boolean = false,
    val panelExpanded: Boolean = true,
    val vibrationAfterSeries: Boolean = true,
    val soundAfterSeries: Boolean = true,
    val vibrationAfterProcessing: Boolean = true,
    val soundAfterProcessing: Boolean = true,
    val histogramEnabled: Boolean = true,
    val saveTestShots: Boolean = false,
    val jpegQuality: Int = 92,
    val fastPreviewEnabled: Boolean = true,
    val themeMode: String = AppThemeMode.DARK.name,
    val deletionProtectionEnabled: Boolean = true,
    val shootingGoal: String = "STARS"
)

class CameraSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "astrophoto_camera_settings",
        Context.MODE_PRIVATE
    )

    fun load(): SavedCameraSettings = runCatching {
        val legacyApplyLongExposure = preferences.getBoolean(
            "apply_long_exposure_to_preview",
            false
        )
        SavedCameraSettings(
            exposureTimeNs = preferences.getLong("exposure_time_ns", 33_333_333L),
            iso = preferences.getInt("iso", 400),
            focusDistance = preferences.getFloat("focus_distance", 0f),
            focusMode = preferences.getString("focus_mode", "INFINITY") ?: "INFINITY",
            applyLongExposureToPreview = legacyApplyLongExposure,
            singleFormat = canonicalCaptureFormat(preferences.getString("single_format", null)),
            seriesFormat = canonicalCaptureFormat(preferences.getString("series_format", null)),
            darkFramesFormat = canonicalCaptureFormat(
                preferences.getString("dark_frames_format", null)
            ),
            darkFramesCount = preferences.getInt("dark_frames_count", 3)
                .takeIf { it in DARK_FRAME_COUNT_VALUES } ?: 3,
            captureMode = preferences.getString("capture_mode", "SERIES") ?: "SERIES",
            seriesFrameCount = preferences.getInt("series_frame_count", 3),
            seriesDelaySeconds = preferences.getInt("series_delay_seconds", 0),
            startTimerSeconds = preferences.getInt("start_timer_seconds", 0),
            astroModeEnabled = preferences.getBoolean("astro_mode_enabled", false),
            panelExpanded = preferences.getBoolean("panel_expanded", true),
            vibrationAfterSeries = preferences.getBoolean("vibration_after_series", true),
            soundAfterSeries = preferences.getBoolean("sound_after_series", true),
            vibrationAfterProcessing = preferences.getBoolean(
                "vibration_after_processing",
                true
            ),
            soundAfterProcessing = preferences.getBoolean("sound_after_processing", true),
            histogramEnabled = preferences.getBoolean("histogram_enabled", true),
            saveTestShots = preferences.getBoolean("save_test_shots", false),
            jpegQuality = preferences.getInt("jpeg_quality", 92)
                .takeIf { it in JPEG_QUALITY_VALUES } ?: 92,
            fastPreviewEnabled = preferences.getBoolean(
                "fast_preview_enabled",
                !legacyApplyLongExposure
            ),
            themeMode = preferences.getString(
                "theme_mode",
                AppThemeMode.DARK.name
            )?.takeIf { value ->
                AppThemeMode.entries.any { it.name == value }
            } ?: AppThemeMode.DARK.name,
            deletionProtectionEnabled = preferences.getBoolean(
                "deletion_protection_enabled",
                true
            ),
            shootingGoal = preferences.getString("shooting_goal", "STARS") ?: "STARS"
        )
    }.getOrElse {
        preferences.edit {clear()}
        SavedCameraSettings()
    }

    fun save(settings: SavedCameraSettings) {
        preferences.edit {
                putLong("exposure_time_ns", settings.exposureTimeNs)
                .putInt("iso", settings.iso)
                .putFloat("focus_distance", settings.focusDistance)
                .putString("focus_mode", settings.focusMode)
                .putBoolean(
                    "apply_long_exposure_to_preview",
                    settings.applyLongExposureToPreview
                )
                .putString("single_format", settings.singleFormat)
                .putString("series_format", settings.seriesFormat)
                .putString("dark_frames_format", settings.darkFramesFormat)
                .putInt(
                    "dark_frames_count",
                    settings.darkFramesCount.takeIf { it in DARK_FRAME_COUNT_VALUES } ?: 3
                )
                .putString("capture_mode", settings.captureMode)
                .putInt("series_frame_count", settings.seriesFrameCount)
                .putInt("series_delay_seconds", settings.seriesDelaySeconds)
                .putInt("start_timer_seconds", settings.startTimerSeconds)
                .putBoolean("astro_mode_enabled", settings.astroModeEnabled)
                .putBoolean("panel_expanded", settings.panelExpanded)
                .putBoolean("vibration_after_series", settings.vibrationAfterSeries)
                .putBoolean("sound_after_series", settings.soundAfterSeries)
                .putBoolean("vibration_after_processing", settings.vibrationAfterProcessing)
                .putBoolean("sound_after_processing", settings.soundAfterProcessing)
                .putBoolean("histogram_enabled", settings.histogramEnabled)
                .putBoolean("save_test_shots", settings.saveTestShots)
                .putInt(
                    "jpeg_quality",
                    settings.jpegQuality.takeIf { it in JPEG_QUALITY_VALUES } ?: 92
                )
                .putBoolean("fast_preview_enabled", settings.fastPreviewEnabled)
                .putString("theme_mode", settings.themeMode)
                .putBoolean(
                    "deletion_protection_enabled",
                    settings.deletionProtectionEnabled
                )
                .putString("shooting_goal", settings.shootingGoal)
            }
    }

    fun saveAppSettings(settings: SavedCameraSettings) {
        save(mergeAppSettings(load(), settings))
    }

    fun saveCameraSettings(settings: SavedCameraSettings) {
        save(mergeCameraSettings(load(), settings))
    }

    fun reset(): SavedCameraSettings {
        preferences.edit {clear()}
        return SavedCameraSettings()
    }

    fun isOnboardingSeen(): Boolean = runCatching {
        preferences.getBoolean("onboarding_seen", false)
    }.getOrDefault(false)

    fun setOnboardingSeen(seen: Boolean) {
        preferences.edit {
                putBoolean("onboarding_seen", seen)
            }
    }

    companion object {
        val JPEG_QUALITY_VALUES = setOf(85, 92, 100)
        val DARK_FRAME_COUNT_VALUES = listOf(0, 3, 5, 10, 20)
    }
}

internal fun mergeAppSettings(
    persisted: SavedCameraSettings,
    updated: SavedCameraSettings
): SavedCameraSettings = persisted.copy(
    applyLongExposureToPreview = updated.applyLongExposureToPreview,
    vibrationAfterSeries = updated.vibrationAfterSeries,
    soundAfterSeries = updated.soundAfterSeries,
    vibrationAfterProcessing = updated.vibrationAfterProcessing,
    soundAfterProcessing = updated.soundAfterProcessing,
    histogramEnabled = updated.histogramEnabled,
    saveTestShots = updated.saveTestShots,
    jpegQuality = updated.jpegQuality,
    fastPreviewEnabled = updated.fastPreviewEnabled,
    themeMode = updated.themeMode,
    deletionProtectionEnabled = updated.deletionProtectionEnabled
)

internal fun mergeCameraSettings(
    persisted: SavedCameraSettings,
    updated: SavedCameraSettings
): SavedCameraSettings = updated.copy(
    themeMode = persisted.themeMode,
    deletionProtectionEnabled = persisted.deletionProtectionEnabled,
    vibrationAfterProcessing = persisted.vibrationAfterProcessing,
    soundAfterProcessing = persisted.soundAfterProcessing
)

internal fun canonicalCaptureFormat(storedValue: String?): String =
    storedValue?.takeIf { it == "JPEG" || it == "RAW" } ?: "JPEG"
