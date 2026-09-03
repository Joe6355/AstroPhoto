package com.joe6355.astrophoto

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf

internal class CameraControlsState(private val savedSettings: SavedCameraSettings) {
    val capabilities = mutableStateOf<ManualCameraCapabilities?>(null)
    val exposureTimeNs = mutableLongStateOf(savedSettings.exposureTimeNs)
    val iso = mutableIntStateOf(savedSettings.iso)
    val focusDistance = mutableFloatStateOf(savedSettings.focusDistance)
    val focusMode = mutableStateOf(
        runCatching { CameraFocusMode.valueOf(savedSettings.focusMode) }
            .getOrDefault(CameraFocusMode.INFINITY)
    )
    val applyLongExposureToPreview = mutableStateOf(!savedSettings.fastPreviewEnabled)
    val jpegQuality = mutableIntStateOf(savedSettings.jpegQuality)
    val singleFormat = mutableStateOf(UiCaptureType.JPEG)
    val seriesFormat = mutableStateOf(UiCaptureType.JPEG)
    val captureMode = mutableStateOf(UiCaptureMode.SERIES)
    val seriesFrameCount = mutableIntStateOf(savedSettings.seriesFrameCount)
    val seriesDelaySeconds = mutableIntStateOf(savedSettings.seriesDelaySeconds)
    val startTimerSeconds = mutableIntStateOf(savedSettings.startTimerSeconds)
    val astroModeEnabled = mutableStateOf(savedSettings.astroModeEnabled)
    val astroDefaultsApplied = mutableStateOf(false)
    val selectedPreset = mutableStateOf<CameraPreset?>(null)

    fun applyAstroDefaults(cameraCapabilities: ManualCameraCapabilities) {
        focusDistance.floatValue = 0f
        focusMode.value = CameraFocusMode.INFINITY
        iso.intValue = cameraCapabilities.isoRange
            ?.let { 800.coerceIn(it.first, it.last) }
            ?: 800
        val integrationPlan = cameraCapabilities.exposureRangeNs
            ?.takeIf { cameraCapabilities.supportsManualSensor }
            ?.let { range ->
                astroIntegrationPlan(
                    exposureRangeNs = range,
                    targetIntegrationNs = MINIMUM_ASTRO_INTEGRATION_NS,
                    preferredFrameCount = 10
                )
            }
        exposureTimeNs.longValue = integrationPlan?.exposurePerFrameNs
            ?: cameraCapabilities.exposureRangeNs?.last
            ?: 33_333_333L
        seriesFormat.value = UiCaptureType.JPEG
        captureMode.value = UiCaptureMode.SERIES
        seriesFrameCount.intValue = integrationPlan?.frameCount ?: 10
        seriesDelaySeconds.intValue = 1
        startTimerSeconds.intValue = 5
        astroDefaultsApplied.value = true
    }

    fun setAstroMode(enabled: Boolean) {
        astroModeEnabled.value = enabled
        if (enabled) {
            capabilities.value?.let(::applyAstroDefaults)
        } else {
            astroDefaultsApplied.value = false
        }
    }

    fun applyPreset(preset: CameraPreset) {
        val cameraCapabilities = capabilities.value ?: return
        val adaptedPreset = adaptCameraPreset(preset, cameraCapabilities)
        iso.intValue = adaptedPreset.iso
        exposureTimeNs.longValue = adaptedPreset.exposureTimeNs
        focusMode.value = adaptedPreset.focusMode
        if (adaptedPreset.focusMode == CameraFocusMode.INFINITY) {
            focusDistance.floatValue = 0f
        }
        singleFormat.value = UiCaptureType.JPEG
        seriesFormat.value = UiCaptureType.JPEG
        captureMode.value = UiCaptureMode.SERIES
        seriesFrameCount.intValue = adaptedPreset.frameCount.coerceAtLeast(1)
        seriesDelaySeconds.intValue = adaptedPreset.delaySeconds
        startTimerSeconds.intValue = 0
        astroModeEnabled.value = false
        astroDefaultsApplied.value = false
        selectedPreset.value = null
    }

}
