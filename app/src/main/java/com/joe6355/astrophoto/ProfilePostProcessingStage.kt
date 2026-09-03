package com.joe6355.astrophoto

import android.util.Log
import com.joe6355.astrophoto.processing.jpeg.v2.integration.ProfileIntegrationCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptivePresetProcessor
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptiveProcessingResult
import com.joe6355.astrophoto.processing.jpeg.v2.storage.CheckpointFiles
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import kotlinx.coroutines.CancellationException

internal suspend fun runProfilePostProcessing(
    checkpointStore: ProfileIntegrationCheckpointStore?,
    temporaryFiles: TemporaryPipelineFiles,
    stackedSky: FileBackedImage,
    referenceForeground: FileBackedImage,
    effectiveSkyAlpha: FileBackedFloatPlane,
    profile: AstroProcessingProfile,
    frameCount: Int,
    alignedStackStars: List<DetectedStar>,
    store: ResultCandidateStore,
    memoryBudget: JpegMemoryBudget,
    memoryTracker: PipelineMemoryTracker,
    sensorDefectAffectedOutput: FileBackedFloatPlane?,
    onProgress: suspend (String, Int, Int) -> Unit
): FileBackedAdaptiveProcessingResult {
    val inputSignature = listOfNotNull(stackedSky.file, referenceForeground.file,
        effectiveSkyAlpha.file, sensorDefectAffectedOutput?.file)
        .joinToString(":") { CheckpointFiles.sha256(it) }
    checkpointStore?.readPostProcessing(inputSignature, temporaryFiles)?.let { restored ->
        store.register(ResultCandidateType.PROCESSED, restored.image)
        onProgress("Постобработка восстановлена из checkpoint", 1, 1)
        return restored
    }
    val result = FileBackedAdaptivePresetProcessor().process(
        stackedSky, referenceForeground, effectiveSkyAlpha, profile, frameCount,
        alignedStackStars, store, memoryBudget, memoryTracker, sensorDefectAffectedOutput, onProgress
    )
    try {
        checkpointStore?.writePostProcessing(inputSignature, result)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("AstroPhotoCheckpoint", "Postprocessing checkpoint unavailable", error)
    }
    return result
}
