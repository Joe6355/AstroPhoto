package com.joe6355.astrophoto

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationRun
import com.joe6355.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.joe6355.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.CachedArgbFrame
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.FileBackedArgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles

internal suspend fun runAutomaticSensorMaskedIntegration(
    checkpointStore: ProfileIntegrationCheckpointStore,
    temporaryFiles: TemporaryPipelineFiles,
    targetWidth: Int,
    targetHeight: Int,
    frames: List<WeightedIntegrationFrame<CachedArgbFrame>>,
    maximumWorkingMemory: Long,
    sensorDefectMask: SensorDefectMask,
    sensorDefectOriginalFrameIndices: List<Int>,
    sensorMaskConstructionDurationMillis: Long,
    integrationSkyMask: SkyMask,
    candidateStore: ResultCandidateStore,
    onProgress: suspend (message: String, current: Int, total: Int) -> Unit
): ProfileIntegrationRun {
    checkpointStore.readInto(temporaryFiles)?.let { restored ->
        withContext(Dispatchers.Main.immediate) {
            onProgress("Интеграция восстановлена из checkpoint", 1, 1)
        }
        return restored
    }
    val computed = computeAutomaticSensorMaskedIntegration(
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        frames = frames,
        maximumWorkingMemory = maximumWorkingMemory,
        sensorDefectMask = sensorDefectMask,
        sensorDefectOriginalFrameIndices = sensorDefectOriginalFrameIndices,
        sensorMaskConstructionDurationMillis = sensorMaskConstructionDurationMillis,
        integrationSkyMask = integrationSkyMask,
        candidateStore = candidateStore,
        onProgress = onProgress
    )
    try {
        checkpointStore.write(computed)
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Log.w("AstroPhotoCheckpoint", "integration checkpoint write failed", error)
    }
    return computed
}

internal suspend fun computeAutomaticSensorMaskedIntegration(
    targetWidth: Int,
    targetHeight: Int,
    frames: List<WeightedIntegrationFrame<CachedArgbFrame>>,
    maximumWorkingMemory: Long,
    sensorDefectMask: SensorDefectMask,
    sensorDefectOriginalFrameIndices: List<Int>,
    sensorMaskConstructionDurationMillis: Long,
    integrationSkyMask: SkyMask,
    candidateStore: ResultCandidateStore,
    onProgress: suspend (message: String, current: Int, total: Int) -> Unit
): ProfileIntegrationRun {
    var activeMask = sensorDefectMask
    var maskedReport: SensorDefectFilteringReport? = null
    var totalDurationMillis = 0L
    var unmaskedRetryUsed = false
    while (true) {
        val filteringAttempt = activeMask.enabled && activeMask.regions.isNotEmpty()
        val stackedWriter = candidateStore.createTemporaryWriter(
            if (filteringAttempt) "integrated-sky-masked" else "integrated-sky",
            targetWidth,
            targetHeight
        )
        val coverageWriter = candidateStore.createFloatPlaneWriter(
            if (filteringAttempt) "valid-coverage-masked" else "valid-coverage",
            targetWidth,
            targetHeight
        )
        val affectedWriter = if (filteringAttempt) {
            candidateStore.createFloatPlaneWriter(
                "sensor-defect-affected-output",
                targetWidth,
                targetHeight
            )
        } else {
            null
        }
        var integrationFinished = false
        val diagnostics = try {
            LinearWeightedIntegrator().integrate(
                outputWidth = targetWidth,
                outputHeight = targetHeight,
                frames = frames,
                maximumWorkingMemoryBytes = maximumWorkingMemory,
                openSource = { cached -> FileBackedArgbPixelSource(cached) },
                allowRobustClipping = false,
                sensorDefectMask = activeMask,
                includeOutputPixel = integrationSkyMask::contains,
                writeTile = { tile, pixels ->
                    stackedWriter.writeTile(
                        tile.left,
                        tile.top,
                        tile.width,
                        tile.height,
                        pixels
                    )
                },
                writeCoverageTile = { tile, coverage ->
                    coverageWriter.writeTile(
                        tile.left,
                        tile.top,
                        tile.width,
                        tile.height,
                        coverage
                    )
                },
                writeSensorDefectAffectedTile = { tile, affected ->
                    val values = FloatArray(affected.size)
                    affected.indices.forEach { index ->
                        if (affected[index]) values[index] = 1f
                    }
                    checkNotNull(affectedWriter).writeTile(
                        tile.left,
                        tile.top,
                        tile.width,
                        tile.height,
                        values
                    )
                },
                onTileCompleted = { tile ->
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            "Processing tile ${tile.index + 1} of ${tile.total}",
                            tile.index + 1,
                            tile.total
                        )
                    }
                }
            ).also { integrationFinished = true }
        } finally {
            if (!integrationFinished) {
                runCatching { stackedWriter.close() }
                runCatching { coverageWriter.close() }
                runCatching { affectedWriter?.close() }
            }
        }
        val stackedSky = stackedWriter.finish()
        val validCoverage = coverageWriter.finish()
        val sensorDefectAffectedOutput = affectedWriter?.finish()
        totalDurationMillis += diagnostics.processingDurationMillis
        val attemptReport = diagnostics.sensorDefectFiltering.copy(
            originalFrameIndices = sensorDefectOriginalFrameIndices,
            maskConstructionDurationMillis = sensorMaskConstructionDurationMillis
        )
        if (filteringAttempt) maskedReport = attemptReport
        if (filteringAttempt && shouldRetryAutomaticIntegrationWithoutMask(attemptReport)) {
            candidateStore.deleteTemporary(stackedSky)
            candidateStore.deleteTemporary(validCoverage)
            sensorDefectAffectedOutput?.let(candidateStore::deleteTemporary)
            activeMask = SensorDefectMask.empty(
                targetWidth,
                targetHeight,
                reason = "insufficient_coverage_unmasked_retry"
            )
            unmaskedRetryUsed = true
            continue
        }
        val selectedReport = (maskedReport ?: attemptReport).copy(
            originalFrameIndices = sensorDefectOriginalFrameIndices,
            maskConstructionDurationMillis = sensorMaskConstructionDurationMillis,
            sampleLevelFilteringApplied =
                maskedReport?.sampleLevelFilteringApplied == true && !unmaskedRetryUsed,
            filteringAppliedToFinalResult =
                maskedReport?.sampleLevelFilteringApplied == true && !unmaskedRetryUsed,
            unmaskedRetryUsed = unmaskedRetryUsed,
            fallbackOrRejectionReason = if (unmaskedRetryUsed) {
                "insufficient_coverage_unmasked_retry"
            } else {
                (maskedReport ?: attemptReport).fallbackOrRejectionReason
            }
        )
        return ProfileIntegrationRun(
            diagnostics = diagnostics,
            stackedSky = stackedSky,
            validCoverage = validCoverage,
            sensorDefectAffectedOutput = sensorDefectAffectedOutput,
            sensorDefectFiltering = selectedReport,
            totalDurationMillis = totalDurationMillis
        )
    }
}
