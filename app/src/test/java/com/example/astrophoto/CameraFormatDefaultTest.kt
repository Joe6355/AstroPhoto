package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFormatDefaultTest {
    @Test
    fun everyCaptureModeDefaultsToJpeg() {
        val defaults = SavedCameraSettings()

        assertEquals("JPEG", defaults.singleFormat)
        assertEquals("JPEG", defaults.seriesFormat)
        assertEquals("JPEG", defaults.darkFramesFormat)
        assertEquals("SERIES", defaults.captureMode)
    }

    @Test
    fun missingPreferenceDefaultsToJpeg() {
        assertEquals("JPEG", canonicalCaptureFormat(null))
    }

    @Test
    fun explicitSupportedPreferencesArePreserved() {
        assertEquals("JPEG", canonicalCaptureFormat("JPEG"))
        assertEquals("RAW", canonicalCaptureFormat("RAW"))
    }

    @Test
    fun unknownLegacyPreferenceMigratesPredictablyToJpeg() {
        assertEquals("JPEG", canonicalCaptureFormat("JPEG_RAW"))
    }

    @Test
    fun appSettingsMergeDoesNotOverwriteCameraSettings() {
        val persisted = SavedCameraSettings(
            iso = 1600,
            exposureTimeNs = 2_000_000_000L,
            seriesFormat = "RAW",
            darkFramesFormat = "RAW"
        )
        val editedFromStaleScreen = SavedCameraSettings(
            themeMode = AppThemeMode.LIGHT.name,
            jpegQuality = 100,
            vibrationAfterProcessing = false,
            soundAfterProcessing = false
        )

        val merged = mergeAppSettings(persisted, editedFromStaleScreen)

        assertEquals(1600, merged.iso)
        assertEquals(2_000_000_000L, merged.exposureTimeNs)
        assertEquals("RAW", merged.seriesFormat)
        assertEquals("RAW", merged.darkFramesFormat)
        assertEquals(AppThemeMode.LIGHT.name, merged.themeMode)
        assertEquals(100, merged.jpegQuality)
        assertEquals(false, merged.vibrationAfterProcessing)
        assertEquals(false, merged.soundAfterProcessing)
    }

    @Test
    fun cameraSettingsMergeDoesNotOverwriteAppSettings() {
        val persisted = SavedCameraSettings(
            themeMode = AppThemeMode.RED_NIGHT.name,
            deletionProtectionEnabled = false,
            vibrationAfterProcessing = false,
            soundAfterProcessing = false
        )
        val camera = SavedCameraSettings(
            iso = 800,
            singleFormat = "RAW",
            seriesFormat = "RAW",
            darkFramesFormat = "RAW",
            darkFramesCount = 10
        )

        val merged = mergeCameraSettings(persisted, camera)

        assertEquals(800, merged.iso)
        assertEquals("RAW", merged.singleFormat)
        assertEquals("RAW", merged.seriesFormat)
        assertEquals("RAW", merged.darkFramesFormat)
        assertEquals(10, merged.darkFramesCount)
        assertEquals(AppThemeMode.RED_NIGHT.name, merged.themeMode)
        assertEquals(false, merged.deletionProtectionEnabled)
        assertEquals(false, merged.vibrationAfterProcessing)
        assertEquals(false, merged.soundAfterProcessing)
    }
}
