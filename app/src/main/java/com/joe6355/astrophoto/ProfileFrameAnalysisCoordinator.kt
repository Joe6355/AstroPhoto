package com.joe6355.astrophoto

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ProfileAnalysisCheckpointStore
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateDetector
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.PipelineTimingCollector
import com.joe6355.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import com.joe6355.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import com.joe6355.astrophoto.processing.jpeg.v2.registration.CaptureSequenceFrame
import com.joe6355.astrophoto.processing.jpeg.v2.registration.CaptureSequenceIndexResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

internal data class ProfileAnalyzedFrame(
    val frame: SessionFrame,
    val analysis: FrameAnalysis,
    val skyMask: SkyMaskResult,
    val persistentSensorObservation: PersistentSensorFrameObservation
)

internal data class ProfileAnalysisPreparation(
    val frames: List<SessionFrame>,
    val captureIndexByFrameKey: Map<String, Int>,
    val dimensionsByFrameKey: Map<String, Pair<Int, Int>>,
    val commonWidth: Int,
    val commonHeight: Int,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val analyzedFrames: List<ProfileAnalyzedFrame>,
    val checkpointStore: ProfileAnalysisCheckpointStore
)

internal class ProfileFrameAnalysisCoordinator(
    private val context: Context,
    private val readDimensions: (SessionFrame) -> Pair<Int, Int>?,
    private val decodeFrame: (SessionFrame, Int, Int) -> Bitmap?
) {
    suspend fun prepare(
        sessionFolder: String,
        frames: List<SessionFrame>,
        profile: AstroProcessingProfile,
        source: ManualStackingSource,
        pipelineTiming: PipelineTimingCollector,
        runJournal: ProcessingRunJournal,
        journalRunId: String?,
        onStage: (String) -> Unit,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): ProfileAnalysisPreparation {
        val captureIndexByFrameKey = profileCaptureIndices(frames)
        onStage("Чтение размеров кадров")
        val dimensionsByFrameKey = frames.associate { frame ->
            frame.key to (readDimensions(frame)
                ?: error("Не удалось прочитать кадр: ${frame.fileName}"))
        }
        val dimensions = dimensionsByFrameKey.values.toList()
        if (source == ManualStackingSource.CROPPED) {
            require(dimensions.distinct().size == 1) {
                "Selected cropped frames have different dimensions"
            }
        }
        val commonWidth = dimensions.minOf { it.first }
        val commonHeight = dimensions.minOf { it.second }
        onStage("Анализ JPEG-кадров")
        val analysisScale = minOf(
            1f,
            PROFILE_ANALYSIS_MAX_DIMENSION.toFloat() / maxOf(commonWidth, commonHeight)
        )
        val analysisWidth = maxOf(1, (commonWidth * analysisScale).roundToInt())
        val analysisHeight = maxOf(1, (commonHeight * analysisScale).roundToInt())
        val checkpointStore = ProfileAnalysisCheckpointStore.open(
            context = context,
            sessionFolder = sessionFolder,
            profile = profile,
            frames = frames,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight
        )
        val frameAnalysisStarted = System.nanoTime()
        val analyzedFrames = analyze(
            frames = frames,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            captureIndexByFrameKey = captureIndexByFrameKey,
            checkpointStore = checkpointStore,
            onProgress = onProgress
        )
        pipelineTiming.record(
            "frame_analysis",
            (System.nanoTime() - frameAnalysisStarted) / 1_000_000L
        )
        journalRunId?.let { runJournal.update(it, "frame_analysis_completed") }
        return ProfileAnalysisPreparation(
            frames = frames,
            captureIndexByFrameKey = captureIndexByFrameKey,
            dimensionsByFrameKey = dimensionsByFrameKey,
            commonWidth = commonWidth,
            commonHeight = commonHeight,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            analyzedFrames = analyzedFrames,
            checkpointStore = checkpointStore
        )
    }

    private fun profileCaptureIndices(frames: List<SessionFrame>): Map<String, Int> =
        CaptureSequenceIndexResolver.resolve(frames.map { frame ->
            CaptureSequenceFrame(frame.key, frame.fileName, frame.createdAtMillis)
        })

    private suspend fun analyze(
        frames: List<SessionFrame>,
        analysisWidth: Int,
        analysisHeight: Int,
        captureIndexByFrameKey: Map<String, Int>,
        checkpointStore: ProfileAnalysisCheckpointStore,
        onProgress: suspend (message: String, current: Int, total: Int) -> Unit
    ): List<ProfileAnalyzedFrame> {
        val skyMaskEstimator = SkyMaskEstimator()
        val frameAnalyzer = JpegFrameAnalyzer()
        val persistentSensorDetector = PersistentSensorCandidateDetector()
        return frames.mapIndexed { index, frame ->
            val captureIndex = captureIndexByFrameKey.getValue(frame.key)
            val restored = checkpointStore.read(frame, captureIndex)
            val analyzed = restored?.let { checkpoint ->
                ProfileAnalyzedFrame(
                    frame = frame,
                    analysis = checkpoint.analysis,
                    skyMask = checkpoint.skyMask,
                    persistentSensorObservation = checkpoint.sensorObservation
                )
            } ?: analyzeFrame(
                frame = frame,
                captureIndex = captureIndex,
                analysisWidth = analysisWidth,
                analysisHeight = analysisHeight,
                skyMaskEstimator = skyMaskEstimator,
                frameAnalyzer = frameAnalyzer,
                persistentSensorDetector = persistentSensorDetector
            )
            if (restored == null) {
                runCatching {
                    checkpointStore.write(
                        frame = frame,
                        captureIndex = captureIndex,
                        analysis = analyzed.analysis,
                        skyMask = analyzed.skyMask,
                        observation = analyzed.persistentSensorObservation
                    )
                }.onFailure { error ->
                    Log.w(TAG, "frame=${frame.fileName} checkpointWriteFailed=${error.message.orEmpty()}")
                }
            }
            Log.i(
                TAG,
                "frame=${frame.fileName} skyMaskConfidence=" +
                    "${formatMetric(analyzed.analysis.skyMaskConfidence)} " +
                    "skyMaskFallback=${analyzed.analysis.skyMaskUsedFallback} " +
                    "detectedStars=${analyzed.analysis.reliableStarCount}"
            )
            withContext(Dispatchers.Main.immediate) {
                onProgress(
                    if (restored != null) {
                        "Восстановление анализа ${index + 1} из ${frames.size}"
                    } else {
                        "Анализ JPEG-кадров ${index + 1} из ${frames.size}"
                    },
                    index + 1,
                    frames.size
                )
            }
            analyzed
        }
    }

    private fun analyzeFrame(
        frame: SessionFrame,
        captureIndex: Int,
        analysisWidth: Int,
        analysisHeight: Int,
        skyMaskEstimator: SkyMaskEstimator,
        frameAnalyzer: JpegFrameAnalyzer,
        persistentSensorDetector: PersistentSensorCandidateDetector
    ): ProfileAnalyzedFrame = runCatching {
        val sample = decodeFrame(frame, analysisWidth, analysisHeight)
            ?: error("Unable to decode JPEG for analysis")
        try {
            val image = sample.toArgbImage()
            val skyMask = skyMaskEstimator.estimate(image)
            ProfileAnalyzedFrame(
                frame = frame,
                analysis = frameAnalyzer.analyze(frame.key, frame.fileName, image, skyMask),
                skyMask = skyMask,
                persistentSensorObservation = persistentSensorDetector.observe(
                    frameId = frame.key,
                    originalCaptureIndex = captureIndex,
                    image = image,
                    skyMask = skyMask.mask
                )
            )
        } finally {
            sample.recycle()
        }
    }.getOrElse { error ->
        Log.w(TAG, "frame=${frame.fileName} analysisRejected reason=${error.message.orEmpty()}")
        ProfileAnalyzedFrame(
            frame = frame,
            analysis = FrameAnalysis.invalid(frame.key, frame.fileName),
            skyMask = SkyMaskResult(
                SkyMask.empty(analysisWidth, analysisHeight),
                confidence = 0f,
                usedFallback = true
            ),
            persistentSensorObservation = PersistentSensorFrameObservation(
                frameId = frame.key,
                originalCaptureIndex = captureIndex,
                width = analysisWidth,
                height = analysisHeight,
                candidates = emptyList()
            )
        )
    }

    private fun Bitmap.toArgbImage(): ArgbPixelImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun formatMetric(value: Float): String =
        if (value.isFinite()) "%.4f".format(Locale.US, value) else "n/a"

    private companion object {
        const val TAG = "AstroPhotoJpegV2"
        const val PROFILE_ANALYSIS_MAX_DIMENSION = 960
    }
}
