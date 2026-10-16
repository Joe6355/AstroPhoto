package com.joe6355.astrophoto

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
    fun onlyJpegPreferenceIsPreserved() {
        assertEquals("JPEG", canonicalCaptureFormat("JPEG"))
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
            seriesFormat = "JPEG",
            darkFramesFormat = "JPEG"
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
        assertEquals("JPEG", merged.seriesFormat)
        assertEquals("JPEG", merged.darkFramesFormat)
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
            singleFormat = "JPEG",
            seriesFormat = "JPEG",
            darkFramesFormat = "JPEG",
            darkFramesCount = 10
        )

        val merged = mergeCameraSettings(persisted, camera)

        assertEquals(800, merged.iso)
        assertEquals("JPEG", merged.singleFormat)
        assertEquals("JPEG", merged.seriesFormat)
        assertEquals("JPEG", merged.darkFramesFormat)
        assertEquals(10, merged.darkFramesCount)
        assertEquals(AppThemeMode.RED_NIGHT.name, merged.themeMode)
        assertEquals(false, merged.deletionProtectionEnabled)
        assertEquals(false, merged.vibrationAfterProcessing)
        assertEquals(false, merged.soundAfterProcessing)
    }
}
