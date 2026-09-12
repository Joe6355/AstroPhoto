package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationControllerTest {
    @Test fun rootTabsReturnHomeWithoutReplayingOldTabs() {
        val navigation = AppNavigationController()
        navigation.navigateTopLevel(AppScreen.Camera)
        navigation.navigateTopLevel(AppScreen.Sessions)
        navigation.navigateTo(AppScreen.SessionDetails)
        navigation.navigateTopLevel(AppScreen.Camera)
        navigation.navigateBack()
        assertEquals(AppScreen.Diagnostics, navigation.currentScreen.value)
        assertFalse(navigation.showExitDialog.value)
        navigation.navigateBack()
        assertTrue(navigation.showExitDialog.value)
    }

    @Test fun duplicateNavigationDoesNotDuplicateBackStack() {
        val navigation = AppNavigationController()
        navigation.navigateTo(AppScreen.Camera)
        navigation.navigateTo(AppScreen.Camera)
        navigation.navigateTo(AppScreen.Help)
        navigation.navigateBack()
        assertEquals(AppScreen.Camera, navigation.currentScreen.value)
        navigation.navigateBack()
        assertEquals(AppScreen.Diagnostics, navigation.currentScreen.value)
        assertFalse(navigation.showExitDialog.value)
        navigation.navigateBack()
        assertTrue(navigation.showExitDialog.value)
    }

    @Test fun deletedSessionCannotBeReopenedThroughBackStack() {
        val navigation = AppNavigationController()
        navigation.navigateTo(AppScreen.Sessions)
        navigation.navigateTo(AppScreen.SessionDetails)
        navigation.navigateToSessionsAfterDelete()
        assertEquals(AppScreen.Sessions, navigation.currentScreen.value)
        navigation.navigateBack()
        assertEquals(AppScreen.Diagnostics, navigation.currentScreen.value)
    }

    @Test fun cameraControlsPreserveSettingsAndSafeFormatDefaults() {
        val settings = SavedCameraSettings(exposureTimeNs = 4_000_000_000L, iso = 1600,
            focusDistance = 1.5f, focusMode = "invalid", fastPreviewEnabled = true,
            seriesFrameCount = 30, jpegQuality = 95)
        val controls = CameraControlsState(settings)
        assertEquals(settings.exposureTimeNs, controls.exposureTimeNs.longValue)
        assertEquals(settings.iso, controls.iso.intValue)
        assertEquals(settings.focusDistance, controls.focusDistance.floatValue, 0f)
        assertEquals(CameraFocusMode.INFINITY, controls.focusMode.value)
        assertEquals(30, controls.seriesFrameCount.intValue)
        assertEquals(95, controls.jpegQuality.intValue)
        assertFalse(controls.applyLongExposureToPreview.value)
        assertEquals(UiCaptureType.JPEG, controls.singleFormat.value)
        assertEquals(UiCaptureMode.SERIES, controls.captureMode.value)
        controls.setAstroMode(true)
        assertEquals(settings.exposureTimeNs, controls.exposureTimeNs.longValue)
        controls.setAstroMode(false)
        assertFalse(controls.astroDefaultsApplied.value)
    }

    @Test fun astroDefaultsRespectHardwareLimitsAndUseSeriesIntegration() {
        val controls = CameraControlsState(SavedCameraSettings())
        controls.capabilities.value = ManualCameraCapabilities("0", 1_000_000L..8_000_000_000L,
            100..640, 10f, true, true, true, true, false)
        controls.setAstroMode(true)
        assertTrue(controls.exposureTimeNs.longValue <= 8_000_000_000L)
        assertTrue(controls.exposureTimeNs.longValue * controls.seriesFrameCount.intValue >= MINIMUM_ASTRO_INTEGRATION_NS)
        assertEquals(640, controls.iso.intValue)
        assertEquals(CameraFocusMode.INFINITY, controls.focusMode.value)
        assertEquals(0f, controls.focusDistance.floatValue, 0f)
        assertTrue(controls.astroDefaultsApplied.value)
    }
}
