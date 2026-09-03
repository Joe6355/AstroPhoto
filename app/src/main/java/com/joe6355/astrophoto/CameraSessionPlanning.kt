package com.joe6355.astrophoto

internal fun canStartSingleCapture(
    isCapturing: Boolean,
    seriesRunning: Boolean,
    darkFramesRunning: Boolean,
    testShotRunning: Boolean,
    permissionRequestPending: Boolean
): Boolean = !isCapturing && !seriesRunning && !darkFramesRunning &&
    !testShotRunning && !permissionRequestPending

internal fun estimatedSeriesDurationMillis(
    exposureTimeNs: Long,
    frameCount: Int,
    delaySeconds: Int,
    startTimerSeconds: Int
): Long {
    val frames = frameCount.coerceAtLeast(0).toLong()
    val exposureMillis = (exposureTimeNs.coerceAtLeast(0L) / 1_000_000L) +
        if (exposureTimeNs.coerceAtLeast(0L) % 1_000_000L == 0L) 0L else 1L
    val pauses = (frameCount - 1).coerceAtLeast(0).toLong()
    return frames * exposureMillis +
        pauses * delaySeconds.coerceAtLeast(0).toLong() * 1_000L +
        startTimerSeconds.coerceAtLeast(0).toLong() * 1_000L
}

internal fun estimatedRemainingSeriesDurationMillis(
    elapsedCaptureMillis: Long,
    completedFrames: Int,
    totalFrames: Int,
    delaySeconds: Int
): Long {
    val completed = completedFrames.coerceIn(0, totalFrames.coerceAtLeast(0))
    val remainingFrames = (totalFrames - completed).coerceAtLeast(0)
    if (completed == 0 || remainingFrames == 0) return 0L
    val delayMillis = delaySeconds.coerceAtLeast(0).toLong() * 1_000L
    val elapsedDelays = (completed - 1).toLong() * delayMillis
    val observedCaptureMillis = (elapsedCaptureMillis - elapsedDelays).coerceAtLeast(1L)
    val averageCaptureMillis = observedCaptureMillis / completed
    return remainingFrames.toLong() * (averageCaptureMillis + delayMillis)
}

internal fun formatSeriesDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L +
        if (durationMillis.coerceAtLeast(0L) % 1_000L == 0L) 0L else 1L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return buildList {
        if (hours > 0L) add("$hours ч")
        if (minutes > 0L) add("$minutes мин")
        if (seconds > 0L || isEmpty()) add("$seconds сек")
    }.joinToString(" ")
}

internal fun seriesRemainingLabel(remainingMillis: Long): String =
    if (remainingMillis > 0L) "Осталось ≈ ${formatSeriesDuration(remainingMillis)}" else "Завершение…"

internal data class AdaptedCameraPreset(
    val iso: Int,
    val exposureTimeNs: Long,
    val format: UiCaptureType,
    val frameCount: Int,
    val delaySeconds: Int,
    val focusMode: CameraFocusMode,
    val wasAdapted: Boolean
)

internal fun adaptCameraPreset(
    preset: CameraPreset,
    capabilities: ManualCameraCapabilities
): AdaptedCameraPreset {
    val adaptedIso = capabilities.isoRange?.let { preset.iso.coerceIn(it.first, it.last) } ?: preset.iso
    val adaptedExposure = capabilities.exposureRangeNs?.let { range ->
        when {
            range.contains(preset.exposureTimeNs) -> preset.exposureTimeNs
            preset.useMaximumExposureWhenNeeded -> range.last
            else -> preset.exposureTimeNs.coerceIn(range.first, range.last)
        }
    } ?: preset.exposureTimeNs
    val adaptedFormat = if (preset.preferRaw && capabilities.supportsRawCapture) {
        UiCaptureType.RAW
    } else {
        UiCaptureType.JPEG
    }
    val adaptedFocusMode = if (preset.focusMode != CameraFocusMode.AF && !capabilities.supportsManualFocus) {
        CameraFocusMode.AF
    } else {
        preset.focusMode
    }
    return AdaptedCameraPreset(
        adaptedIso,
        adaptedExposure,
        adaptedFormat,
        preset.frameCount,
        preset.delaySeconds,
        adaptedFocusMode,
        adaptedIso != preset.iso || adaptedExposure != preset.exposureTimeNs ||
            (preset.preferRaw && adaptedFormat != UiCaptureType.RAW) || adaptedFocusMode != preset.focusMode
    )
}

internal val SERIES_FRAME_COUNTS = listOf(1, 3, 5, 10, 20, 30, 40, 50, 100, 150, 200, 300, 500)

internal data class AstroIntegrationPlan(
    val exposurePerFrameNs: Long,
    val frameCount: Int,
    val totalIntegrationNs: Long,
    val reachesTarget: Boolean
)

internal fun astroIntegrationPlan(
    exposureRangeNs: LongRange,
    targetIntegrationNs: Long = MINIMUM_ASTRO_INTEGRATION_NS,
    preferredFrameCount: Int = 1
): AstroIntegrationPlan? {
    if (exposureRangeNs.first <= 0L || exposureRangeNs.last < exposureRangeNs.first || targetIntegrationNs <= 0L) {
        return null
    }
    val exposurePerFrame = minOf(exposureRangeNs.last, targetIntegrationNs)
    val requiredFrames = ((targetIntegrationNs - 1L) / exposurePerFrame + 1L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val frameCount = maxOf(requiredFrames, preferredFrameCount.coerceAtLeast(1))
        .coerceAtMost(MAX_AUTOMATIC_INTEGRATION_FRAMES)
    val totalIntegration = exposurePerFrame * frameCount.toLong()
    return AstroIntegrationPlan(
        exposurePerFrame,
        frameCount,
        totalIntegration,
        totalIntegration >= targetIntegrationNs
    )
}

internal const val MINIMUM_ASTRO_INTEGRATION_NS = 30_000_000_000L
private const val MAX_AUTOMATIC_INTEGRATION_FRAMES = 500
