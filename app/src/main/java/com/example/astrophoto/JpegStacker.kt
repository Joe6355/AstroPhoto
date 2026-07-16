package com.example.astrophoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.example.astrophoto.ui.AstroExpandableSection
import com.example.astrophoto.ui.AstroProgressPanel
import com.example.astrophoto.ui.AstroSegmentedControl
import com.example.astrophoto.ui.AstroTestTags
import com.example.astrophoto.ui.theme.AstroColors
import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactMask
import com.example.astrophoto.processing.jpeg.v2.composition.MaskFeathering
import com.example.astrophoto.processing.jpeg.v2.composition.FileBackedSkyForegroundComposer
import com.example.astrophoto.processing.jpeg.v2.diagnostics.FrameRegistrationReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.FrameWeightReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.IntegrationReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.PipelineTimingCollector
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReport
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingReportWriter
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ReportWriteOutcome
import com.example.astrophoto.processing.jpeg.v2.diagnostics.ProcessingRunJournal
import com.example.astrophoto.processing.jpeg.v2.diagnostics.AppSpecificProcessingReportStore
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightCalculator
import com.example.astrophoto.processing.jpeg.v2.integration.FrameWeightInput
import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.masking.ForegroundProtectionMask
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskRefiner
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar as V2DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.example.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import com.example.astrophoto.processing.jpeg.v2.output.LosslessProcessedImageWriter
import com.example.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptivePresetProcessor
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.example.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.example.astrophoto.processing.jpeg.v2.quality.CleanStackValidationEvidence
import com.example.astrophoto.processing.jpeg.v2.quality.CleanStackExecutionPolicy
import com.example.astrophoto.processing.jpeg.v2.quality.CoverageUniformityValidator
import com.example.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.example.astrophoto.processing.jpeg.v2.quality.FileBackedResultQualityAnalyzer
import com.example.astrophoto.processing.jpeg.v2.quality.ResultSelectionPolicy
import com.example.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import com.example.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.example.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbFrameDiskCache
import com.example.astrophoto.processing.jpeg.v2.sampling.FileBackedArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.memory.ImageAllocationEstimate
import com.example.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.example.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.example.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.example.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.example.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles

data class JpegStackResult(
    val fileName: String,
    val displayPath: String,
    val contentUri: String?,
    val filePath: String?,
    val frameCount: Int,
    val sessionInfoUpdated: Boolean,
    val darkFrameCount: Int = 0,
    val shadowOffset: Int? = null,
    val masterDarkFileName: String? = null,
    val masterDarkDisplayPath: String? = null,
    val alignmentEnabled: Boolean = false,
    val astroStretchApplied: Boolean = false,
    val downscaled: Boolean = false,
    val profile: AstroProcessingProfile? = null,
    val selectedResultType: String? = null,
    val starsBefore: Int? = null,
    val starCount: Int? = null,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null,
    val warnings: List<String> = emptyList(),
    val additionalFiles: List<String> = emptyList()
)

class JpegStacker(private val context: Context) {
    suspend fun stack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        alignFrames: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit,
        onAlignment: suspend (
            current: Int,
            total: Int,
            message: String
        ) -> Unit = { _, _, _ -> },
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(frames.size >= 2) {
                "Недостаточно JPEG кадров для стеккинга"
            }
            require(frames.all {
                it.category == SessionFrameCategory.LIGHTS_JPEG
            }) {
                "Для стеккинга можно использовать только Lights/JPEG"
            }

            val dimensions = frames.map { frame ->
                readDimensions(frame)
                    ?: error("Не удалось прочитать JPEG: ${frame.fileName}")
            }
            if (source == ManualStackingSource.CROPPED) {
                require(dimensions.distinct().size == 1) {
                    "Selected cropped frames have different dimensions"
                }
            }
            val sourceWidth = dimensions.minOf { it.first }
            val sourceHeight = dimensions.minOf { it.second }
            require(sourceWidth > 0 && sourceHeight > 0) {
                "Не удалось прочитать JPEG"
            }
            val sourcePixelCount = sourceWidth.toLong() * sourceHeight
            val targetPixels = averageStackTargetPixels(sourcePixelCount, frames.size)
            val scale = if (sourcePixelCount > targetPixels) {
                sqrt(targetPixels.toDouble() / sourcePixelCount)
            } else {
                1.0
            }
            val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            val downscaled = targetWidth < sourceWidth || targetHeight < sourceHeight

            var average: Bitmap? = null
            var averageAccumulator: ArgbAverageAccumulator? = null
            val alignmentShifts = mutableListOf<AlignmentShift>()
            try {
                val alignmentReference = if (alignFrames) {
                    try {
                        createAlignmentReference(
                            frames.first(),
                            targetWidth,
                            targetHeight
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main.immediate) {
                            onAlignment(
                                1,
                                frames.size,
                                "Не удалось подготовить выравнивание. " +
                                    "Продолжаем без него."
                            )
                        }
                        null
                    }
                } else {
                    null
                }
                frames.forEachIndexed { index, frame ->
                    currentCoroutineContext().ensureActive()
                    val decoded = decodeMedianFrame(frame, targetWidth, targetHeight)
                        ?: error("Не удалось прочитать JPEG: ${frame.fileName}")
                    try {
                        val prepared = decoded
                        val shift = if (
                            alignmentReference != null && index > 0
                        ) {
                            findAlignmentOrZero(
                                reference = alignmentReference,
                                candidate = prepared,
                                frameNumber = index + 1,
                                totalFrames = frames.size,
                                safeMode = alignmentSafe,
                                source = source
                            ) { current, total, message ->
                                withContext(Dispatchers.Main.immediate) {
                                    onAlignment(current, total, message)
                                }
                            }
                        } else {
                            AlignmentShift.Zero
                        }
                        alignmentShifts += shift

                        if (average == null) {
                            average = prepared.copy(Bitmap.Config.ARGB_8888, true)
                                ?: error("Не удалось подготовить JPEG")
                            averageAccumulator = ArgbAverageAccumulator(
                                pixelCount = targetWidth * targetHeight,
                                maximumFrameCount = frames.size
                            )
                        } else {
                            addToManualRunningAverage(
                                accumulator = checkNotNull(averageAccumulator),
                                average = checkNotNull(average),
                                next = prepared,
                                frameNumber = index + 1,
                                dx = shift.dx,
                                dy = shift.dy
                            )
                        }
                    } finally {
                        decoded.takeUnless { it === average || it.isRecycled }?.recycle()
                    }
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(index + 1, frames.size)
                    }
                }

                if (alignFrames) {
                    average = cropToCommonAlignedRegion(
                        checkNotNull(average),
                        alignmentShifts
                    )
                }
                val output = checkNotNull(average)
                if (autoStretch) {
                    applyAstroStretchInPlace(output)
                }
                val now = System.currentTimeMillis()
                val outputType = if (alignFrames) {
                    ProcessedOutputType.AVERAGE_ALIGNED
                } else {
                    ProcessedOutputType.AVERAGE
                }
                val fileName = buildProcessedResultBaseName(outputType, now)
                val saved = saveBitmap(session, output, fileName)
                val infoUpdated = runCatching {
                    appendSessionInfo(
                        session = session,
                        fileName = saved.fileName,
                        frameCount = frames.size,
                        alignmentEnabled = alignFrames,
                        astroStretchApplied = autoStretch,
                        source = source,
                        processedAtMillis = now
                    )
                }.isSuccess

                JpegStackResult(
                    fileName = saved.fileName,
                    displayPath = saved.displayPath,
                    contentUri = saved.contentUri,
                    filePath = saved.filePath,
                    frameCount = frames.size,
                    sessionInfoUpdated = infoUpdated,
                    alignmentEnabled = alignFrames,
                    astroStretchApplied = autoStretch,
                    downscaled = downscaled
                )
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException(
                    "Недостаточно памяти для JPEG стеккинга этого размера",
                    error
                )
            } finally {
                average?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    suspend fun stackWithDarkFrames(
        session: SessionSummary,
        lightFrames: List<SessionFrame>,
        darkFrames: List<SessionFrame>,
        shadowOffset: Int,
        alignFrames: Boolean = false,
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        darkCrop: CropManifestEntry? = null,
        onProgress: suspend (
            message: String,
            current: Int,
            total: Int
        ) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(lightFrames.size >= 2) { "Недостаточно light frames" }
            require(darkFrames.isNotEmpty()) { "Dark frames не найдены" }
            require(shadowOffset in setOf(0, 8, 16, 32)) {
                "Недопустимая компенсация тени"
            }
            require(lightFrames.all {
                it.category == SessionFrameCategory.LIGHTS_JPEG
            }) {
                "Для стеккинга можно использовать только Lights/JPEG"
            }
            require(darkFrames.all {
                it.category == SessionFrameCategory.DARKS_JPEG
            }) {
                "Для master dark можно использовать только Darks/JPEG"
            }

            val allFrames = lightFrames + darkFrames
            val dimensions = allFrames.map { frame ->
                readDimensions(frame)
                    ?: error("Не удалось прочитать JPEG: ${frame.fileName}")
            }
            val lightShapes = dimensions.take(lightFrames.size).map { dimension ->
                decodedPixelFrameShape(dimension.first, dimension.second)
            }
            val darkShapes = dimensions.drop(lightFrames.size).map { dimension ->
                decodedPixelFrameShape(dimension.first, dimension.second)
            }
            if (darkCrop == null) {
                when (val validation = validateDarkFrames(darkShapes, lightShapes)) {
                    is DarkValidationResult.Valid -> Unit
                    is DarkValidationResult.Invalid -> error(validation.message)
                }
            } else {
                require(source == ManualStackingSource.CROPPED) {
                    "Dark crop is only valid for cropped Light frames"
                }
                require(lightShapes.all {
                    it.width == darkCrop.croppedWidth && it.height == darkCrop.croppedHeight
                }) { "Cropped Light dimensions do not match crop metadata" }
                require(darkShapes.all {
                    it.width == darkCrop.originalWidth && it.height == darkCrop.originalHeight
                }) { "Dark frame dimensions do not match original Light dimensions" }
            }
            val targetWidth = darkCrop?.croppedWidth ?: lightShapes.first().width
            val targetHeight = darkCrop?.croppedHeight ?: lightShapes.first().height

            var masterDark: Bitmap? = null
            var croppedMasterDark: Bitmap? = null
            var stacked: Bitmap? = null
            try {
                masterDark = averageFrames(
                    frames = darkFrames,
                    targetWidth = darkCrop?.originalWidth ?: targetWidth,
                    targetHeight = darkCrop?.originalHeight ?: targetHeight
                ) { current, total ->
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            "Создание master dark: $current из $total",
                            current,
                            total
                        )
                    }
                }

                if (darkCrop != null) {
                    val rect = darkCrop.pixelRect
                    require(rect.width == targetWidth && rect.height == targetHeight)
                    croppedMasterDark = Bitmap.createBitmap(
                        checkNotNull(masterDark),
                        rect.left,
                        rect.top,
                        rect.width,
                        rect.height
                    )
                }

                stacked = calibrateAndAverageLights(
                    lightFrames = lightFrames,
                    masterDark = croppedMasterDark ?: checkNotNull(masterDark),
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    shadowOffset = shadowOffset,
                    alignFrames = alignFrames,
                    alignmentSafe = alignmentSafe,
                    source = source
                ) { message, current, total ->
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            message,
                            current,
                            total
                        )
                    }
                }

                withContext(Dispatchers.Main.immediate) {
                    onProgress("Сохранение результата...", 0, 1)
                }
                val now = System.currentTimeMillis()
                val resultType = if (alignFrames) {
                    ProcessedOutputType.AVERAGE_DARK_ALIGNED
                } else {
                    ProcessedOutputType.AVERAGE_DARK
                }
                val resultFileName = buildProcessedResultBaseName(resultType, now)
                val masterDarkFileName = buildProcessedResultBaseName(
                    ProcessedOutputType.MASTER_DARK,
                    now
                )
                if (autoStretch) {
                    applyAstroStretchInPlace(checkNotNull(stacked))
                }
                val savedResult = saveBitmap(
                    session,
                    checkNotNull(stacked),
                    resultFileName
                )
                val savedMaster = try {
                    saveBitmap(
                        session,
                        checkNotNull(masterDark),
                        masterDarkFileName
                    )
                } catch (error: CancellationException) {
                    deleteSavedJpeg(savedResult)
                    throw error
                } catch (_: Exception) {
                    null
                }
                try {
                    currentCoroutineContext().ensureActive()
                } catch (error: CancellationException) {
                    savedMaster?.let(::deleteSavedJpeg)
                    deleteSavedJpeg(savedResult)
                    throw error
                }
                val infoUpdated = runCatching {
                    appendDarkStackSessionInfo(
                        session = session,
                        resultFileName = savedResult.fileName,
                        masterDarkFileName = savedMaster?.fileName,
                        lightFrameCount = lightFrames.size,
                        darkFrameCount = darkFrames.size,
                        shadowOffset = shadowOffset,
                        alignmentEnabled = alignFrames,
                        astroStretchApplied = autoStretch,
                        source = source,
                        processedAtMillis = now
                    )
                }.isSuccess

                JpegStackResult(
                    fileName = savedResult.fileName,
                    displayPath = savedResult.displayPath,
                    contentUri = savedResult.contentUri,
                    filePath = savedResult.filePath,
                    frameCount = lightFrames.size,
                    sessionInfoUpdated = infoUpdated,
                    darkFrameCount = darkFrames.size,
                    shadowOffset = shadowOffset,
                    masterDarkFileName = savedMaster?.fileName,
                    masterDarkDisplayPath = savedMaster?.displayPath,
                    alignmentEnabled = alignFrames,
                    astroStretchApplied = autoStretch
                )
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException(
                    "Недостаточно памяти для JPEG стеккинга этого размера",
                    error
                )
            } finally {
                stacked?.takeUnless(Bitmap::isRecycled)?.recycle()
                croppedMasterDark?.takeUnless(Bitmap::isRecycled)?.recycle()
                masterDark?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    suspend fun medianStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        alignFrames: Boolean,
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        onProgress: suspend (
            message: String,
            current: Int,
            total: Int
        ) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(frames.size >= 2) { "Недостаточно JPEG кадров" }
            require(frames.all {
                it.category == SessionFrameCategory.LIGHTS_JPEG
            }) {
                "Median поддерживает только Lights/JPEG"
            }
            val selectedFrames = frames.take(MAX_MEDIAN_FRAMES)
            val dimensions = selectedFrames.map { frame ->
                readDimensions(frame)
                    ?: error("Не удалось прочитать кадр: ${frame.fileName}")
            }
            if (source == ManualStackingSource.CROPPED) {
                require(dimensions.distinct().size == 1) {
                    "Selected cropped frames have different dimensions"
                }
            }
            val commonWidth = dimensions.minOf { it.first }
            val commonHeight = dimensions.minOf { it.second }
            require(commonWidth > 0 && commonHeight > 0) {
                "Не удалось прочитать кадр"
            }
            val pixelCount = commonWidth.toLong() * commonHeight
            val targetPixels = complexStackTargetPixels(
                sourcePixels = pixelCount,
                frameCount = selectedFrames.size,
                maxPixels = MAX_MEDIAN_PIXELS
            )
            val scale = if (pixelCount > targetPixels) {
                sqrt(targetPixels.toDouble() / pixelCount)
            } else {
                1.0
            }
            val targetWidth = (commonWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (commonHeight * scale).roundToInt().coerceAtLeast(1)
            val downscaled = targetWidth < commonWidth || targetHeight < commonHeight
            val preparedFrames = mutableListOf<MedianPreparedFrame>()
            var output: Bitmap? = null

            try {
                var alignmentReference: ManualAlignmentReference? = null
                selectedFrames.forEachIndexed { index, frame ->
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            "Подготовка кадра ${index + 1} из ${selectedFrames.size}",
                            index + 1,
                            selectedFrames.size
                        )
                    }
                    val bitmap = decodeMedianFrame(
                        frame,
                        targetWidth,
                        targetHeight
                    ) ?: error("Не удалось прочитать кадр: ${frame.fileName}")
                    val shift = if (alignFrames && index > 0) {
                        val reference = alignmentReference
                        if (reference == null) {
                            AlignmentShift.Zero
                        } else {
                            findAlignmentOrZero(
                                reference = reference,
                                candidate = bitmap,
                                frameNumber = index + 1,
                                totalFrames = selectedFrames.size,
                                safeMode = alignmentSafe,
                                source = source
                            ) { current, total, message ->
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(message, current, total)
                                }
                            }
                        }
                    } else {
                        AlignmentShift.Zero
                    }
                    if (alignFrames && index == 0) {
                        alignmentReference = try {
                            createManualAlignmentSample(bitmap)
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                onProgress(
                                    "Выравнивание недоступно, продолжаем без него",
                                    1,
                                    selectedFrames.size
                                )
                            }
                            null
                        }
                    }
                    preparedFrames += MedianPreparedFrame(bitmap, shift)
                }

                withContext(Dispatchers.Main.immediate) {
                    onProgress("Вычисление median...", 0, targetHeight)
                }
                output = calculateMedian(
                    frames = preparedFrames,
                    width = targetWidth,
                    height = targetHeight
                ) { completedRows ->
                    if (
                        completedRows == targetHeight ||
                        completedRows % maxOf(1, targetHeight / 10) == 0
                    ) {
                        withContext(Dispatchers.Main.immediate) {
                            onProgress(
                                "Вычисление median...",
                                completedRows,
                                targetHeight
                            )
                        }
                    }
                }
                if (alignFrames) {
                    output = cropToCommonAlignedRegion(
                        checkNotNull(output),
                        preparedFrames.map { it.shift }
                    )
                }

                withContext(Dispatchers.Main.immediate) {
                    onProgress("Сохранение результата...", 0, 1)
                }
                val now = System.currentTimeMillis()
                val outputType = if (alignFrames) {
                    ProcessedOutputType.MEDIAN_ALIGNED
                } else {
                    ProcessedOutputType.MEDIAN
                }
                val fileName = buildProcessedResultBaseName(outputType, now)
                if (autoStretch) {
                    applyAstroStretchInPlace(checkNotNull(output))
                }
                val saved = saveBitmap(session, checkNotNull(output), fileName)
                val infoUpdated = runCatching {
                    appendMedianSessionInfo(
                        session = session,
                        fileName = saved.fileName,
                        frameCount = selectedFrames.size,
                        alignmentEnabled = alignFrames,
                        downscaled = downscaled,
                        astroStretchApplied = autoStretch,
                        source = source,
                        processedAtMillis = now
                    )
                }.isSuccess
                JpegStackResult(
                    fileName = saved.fileName,
                    displayPath = saved.displayPath,
                    contentUri = saved.contentUri,
                    filePath = saved.filePath,
                    frameCount = selectedFrames.size,
                    sessionInfoUpdated = infoUpdated,
                    alignmentEnabled = alignFrames,
                    astroStretchApplied = autoStretch,
                    downscaled = downscaled
                )
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException(
                    "Недостаточно памяти для median stacking. " +
                        "Уменьшите количество кадров.",
                    error
                )
            } finally {
                output?.takeUnless(Bitmap::isRecycled)?.recycle()
                preparedFrames.forEach {
                    it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
                }
            }
        }
    }

    suspend fun sigmaStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        sigma: Double,
        alignFrames: Boolean,
        alignmentSafe: Boolean = true,
        autoStretch: Boolean = false,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        onProgress: suspend (
            message: String,
            current: Int,
            total: Int
        ) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(frames.size >= 2) { "Недостаточно JPEG кадров" }
            require(frames.all {
                it.category == SessionFrameCategory.LIGHTS_JPEG
            }) {
                "Sigma clipping поддерживает только Lights/JPEG"
            }
            require(sigma in SUPPORTED_SIGMA_VALUES) {
                "Неподдерживаемое значение sigma"
            }

            val selectedFrames = frames.take(MAX_SIGMA_FRAMES)
            val dimensions = selectedFrames.map { frame ->
                readDimensions(frame)
                    ?: error("Не удалось прочитать кадр: ${frame.fileName}")
            }
            if (source == ManualStackingSource.CROPPED) {
                require(dimensions.distinct().size == 1) {
                    "Selected cropped frames have different dimensions"
                }
            }
            val commonWidth = dimensions.minOf { it.first }
            val commonHeight = dimensions.minOf { it.second }
            require(commonWidth > 0 && commonHeight > 0) {
                "Не удалось прочитать кадр"
            }
            val pixelCount = commonWidth.toLong() * commonHeight
            val targetPixels = complexStackTargetPixels(
                sourcePixels = pixelCount,
                frameCount = selectedFrames.size,
                maxPixels = MAX_SIGMA_PIXELS
            )
            val scale = if (pixelCount > targetPixels) {
                sqrt(targetPixels.toDouble() / pixelCount)
            } else {
                1.0
            }
            val targetWidth = (commonWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (commonHeight * scale).roundToInt().coerceAtLeast(1)
            val downscaled = targetWidth < commonWidth || targetHeight < commonHeight
            val preparedFrames = mutableListOf<MedianPreparedFrame>()
            var output: Bitmap? = null

            try {
                var alignmentReference: ManualAlignmentReference? = null
                selectedFrames.forEachIndexed { index, frame ->
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            "Подготовка кадра ${index + 1} из ${selectedFrames.size}",
                            index + 1,
                            selectedFrames.size
                        )
                    }
                    val bitmap = decodeMedianFrame(
                        frame,
                        targetWidth,
                        targetHeight
                    ) ?: error("Не удалось прочитать кадр: ${frame.fileName}")
                    val shift = if (alignFrames && index > 0) {
                        val reference = alignmentReference
                        if (reference == null) {
                            AlignmentShift.Zero
                        } else {
                            findAlignmentOrZero(
                                reference = reference,
                                candidate = bitmap,
                                frameNumber = index + 1,
                                totalFrames = selectedFrames.size,
                                safeMode = alignmentSafe,
                                source = source
                            ) { current, total, message ->
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(message, current, total)
                                }
                            }
                        }
                    } else {
                        AlignmentShift.Zero
                    }
                    if (alignFrames && index == 0) {
                        alignmentReference = try {
                            createManualAlignmentSample(bitmap)
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                onProgress(
                                    "Выравнивание недоступно, продолжаем без него",
                                    1,
                                    selectedFrames.size
                                )
                            }
                            null
                        }
                    }
                    preparedFrames += MedianPreparedFrame(bitmap, shift)
                }

                withContext(Dispatchers.Main.immediate) {
                    onProgress("Расчёт sigma clipping...", 0, targetHeight)
                }
                output = calculateSigmaClipping(
                    frames = preparedFrames,
                    width = targetWidth,
                    height = targetHeight,
                    sigma = sigma
                ) { completedRows ->
                    if (
                        completedRows == targetHeight ||
                        completedRows % maxOf(1, targetHeight / 10) == 0
                    ) {
                        withContext(Dispatchers.Main.immediate) {
                            onProgress(
                                "Расчёт sigma clipping...",
                                completedRows,
                                targetHeight
                            )
                        }
                    }
                }
                if (alignFrames) {
                    output = cropToCommonAlignedRegion(
                        checkNotNull(output),
                        preparedFrames.map { it.shift }
                    )
                }

                withContext(Dispatchers.Main.immediate) {
                    onProgress("Сохранение результата...", 0, 1)
                }
                val now = System.currentTimeMillis()
                val outputType = if (alignFrames) {
                    ProcessedOutputType.SIGMA_ALIGNED
                } else {
                    ProcessedOutputType.SIGMA
                }
                val fileName = buildProcessedResultBaseName(outputType, now)
                if (autoStretch) {
                    applyAstroStretchInPlace(checkNotNull(output))
                }
                val saved = saveBitmap(session, checkNotNull(output), fileName)
                val infoUpdated = runCatching {
                    appendSigmaSessionInfo(
                        session = session,
                        fileName = saved.fileName,
                        frameCount = selectedFrames.size,
                        sigma = sigma,
                        alignmentEnabled = alignFrames,
                        downscaled = downscaled,
                        astroStretchApplied = autoStretch,
                        source = source,
                        processedAtMillis = now
                    )
                }.isSuccess
                JpegStackResult(
                    fileName = saved.fileName,
                    displayPath = saved.displayPath,
                    contentUri = saved.contentUri,
                    filePath = saved.filePath,
                    frameCount = selectedFrames.size,
                    sessionInfoUpdated = infoUpdated,
                    alignmentEnabled = alignFrames,
                    astroStretchApplied = autoStretch,
                    downscaled = downscaled
                )
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException(
                    "Недостаточно памяти для sigma clipping. " +
                        "Уменьшите количество кадров.",
                    error
                )
            } finally {
                output?.takeUnless(Bitmap::isRecycled)?.recycle()
                preparedFrames.forEach {
                    it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
                }
            }
        }
    }

    suspend fun profileStack(
        session: SessionSummary,
        frames: List<SessionFrame>,
        profile: AstroProcessingProfile,
        source: ManualStackingSource = ManualStackingSource.ORIGINAL,
        framesRejected: Int = 0,
        onProgress: suspend (
            message: String,
            current: Int,
            total: Int
        ) -> Unit
    ): Result<JpegStackResult> = withContext(Dispatchers.IO) {
        val memoryBudget = JpegMemoryBudget.current()
        val memoryTracker = PipelineMemoryTracker(
            eventLogger = { event -> Log.i("AstroPhotoJpegMemory", event) }
        )
        val runJournal = ProcessingRunJournal(context)
        var journalRunId: String? = null
        var pipelineFiles: TemporaryPipelineFiles? = null
        var currentStage = "Подготовка"
        val stackResult = runCatching {
            require(profile != AstroProcessingProfile.NORMAL) {
                "Обычный режим доступен в ручном JPEG stacking"
            }
            require(frames.size >= 2) {
                "Недостаточно JPEG кадров для профильной обработки"
            }
            require(frames.size >= profile.minimumFrames) {
                "Для профиля ${profile.title} рекомендуется минимум ${profile.minimumFrames} кадров"
            }
            require(frames.all { it.category == SessionFrameCategory.LIGHTS_JPEG }) {
                "Профили обработки используют только Lights/JPEG"
            }
            val journalRecord = runJournal.start(
                sessionFolder = session.folderName,
                preset = profile.name,
                runtimeMaxHeapBytes = memoryBudget.snapshot.maxHeapBytes,
                heapUsedAtStartBytes = memoryBudget.snapshot.usedHeapBytes,
                safeWorkingBudgetBytes = memoryBudget.safeWorkingBudgetBytes
            )
            journalRunId = journalRecord.runId
            val staleRunsRecovered = TemporaryPipelineFiles.cleanupStale(context.cacheDir)
            val temporaryFiles = TemporaryPipelineFiles.create(context.cacheDir)
            pipelineFiles = temporaryFiles
            val candidateStore = ResultCandidateStore(temporaryFiles)
            Log.i(
                "AstroPhotoJpegMemory",
                "run=${temporaryFiles.runId.take(8)} maxHeap=${memoryBudget.snapshot.maxHeapBytes} " +
                    "usedHeap=${memoryBudget.snapshot.usedHeapBytes} " +
                    "availableHeap=${memoryBudget.snapshot.availableHeapBytes} " +
                    "safeBudget=${memoryBudget.safeWorkingBudgetBytes} " +
                    "staleRunsRecovered=$staleRunsRecovered"
            )
            currentStage = "Выбор рецепта"
            val recipe = profileRecipe(profile, frames.size)
            val pipelineTiming = PipelineTimingCollector()
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "selectedPreset=${profile.name} inputFrameCount=${frames.size}"
            )
            val cappedFrames = frames.take(MAX_PROFILE_FRAMES)
            currentStage = "Чтение размеров кадров"
            val dimensionsByFrameKey = cappedFrames.associate { frame ->
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
            currentStage = "JPEG frame analysis"
            val analysisScale = minOf(
                1f,
                PROFILE_ANALYSIS_MAX_DIMENSION.toFloat() / maxOf(commonWidth, commonHeight)
            )
            val analysisWidth = maxOf(1, (commonWidth * analysisScale).roundToInt())
            val analysisHeight = maxOf(1, (commonHeight * analysisScale).roundToInt())
            val skyMaskEstimator = SkyMaskEstimator()
            val frameAnalyzer = JpegFrameAnalyzer()
            val frameAnalysisStarted = System.nanoTime()
            val rawAnalyzedFrames = cappedFrames.map { frame ->
                val analyzed = runCatching {
                    val sample = decodeMedianFrame(frame, analysisWidth, analysisHeight)
                        ?: error("Unable to decode JPEG for analysis")
                    try {
                        val image = bitmapToArgbImage(sample)
                        val skyMask = skyMaskEstimator.estimate(image)
                        ProfileAnalyzedFrame(
                            frame = frame,
                            analysis = frameAnalyzer.analyze(
                                frame.key,
                                frame.fileName,
                                image,
                                skyMask
                            ),
                            skyMask = skyMask
                        )
                    } finally {
                        sample.recycle()
                    }
                }.getOrElse { error ->
                    Log.w(
                        PROFILE_REGISTRATION_TAG,
                        "frame=${frame.fileName} analysisRejected reason=${error.message.orEmpty()}"
                    )
                    ProfileAnalyzedFrame(
                        frame = frame,
                        analysis = FrameAnalysis.invalid(frame.key, frame.fileName),
                        skyMask = SkyMaskResult(
                            SkyMask.empty(analysisWidth, analysisHeight),
                            confidence = 0f,
                            usedFallback = true
                        )
                    )
                }
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "frame=${frame.fileName} skyMaskConfidence=${formatMetric(analyzed.analysis.skyMaskConfidence)} " +
                        "skyMaskFallback=${analyzed.analysis.skyMaskUsedFallback} " +
                        "detectedStars=${analyzed.analysis.reliableStarCount}"
                )
                analyzed
            }
            pipelineTiming.record(
                "frame_analysis",
                (System.nanoTime() - frameAnalysisStarted) / 1_000_000L
            )
            journalRunId?.let { runJournal.update(it, "frame_analysis_completed") }
            currentStage = "Static artifact analysis"
            val staticArtifactStarted = System.nanoTime()
            val staticArtifactAnalyzer = StaticArtifactAnalyzer()
            val staticArtifactMask = staticArtifactAnalyzer.analyze(
                rawAnalyzedFrames.map { analyzed ->
                    ArtifactFrameObservation(analyzed.frame.key, analyzed.analysis.stars)
                },
                analysisWidth,
                analysisHeight
            )
            val analyzedFrames = rawAnalyzedFrames.map { analyzed ->
                analyzed.copy(
                    analysis = staticArtifactAnalyzer.excludeFrom(
                        analyzed.analysis,
                        staticArtifactMask
                    )
                )
            }
            pipelineTiming.record(
                "static_artifact_analysis",
                (System.nanoTime() - staticArtifactStarted) / 1_000_000L
            )
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "staticArtifactCandidates=${staticArtifactMask.regions.size} " +
                    "staticHotPixels=${staticArtifactMask.staticHotPixelCandidates.size} " +
                    "staticReflections=${staticArtifactMask.staticReflectionCandidates.size} " +
                    "staticArtifactMaskRatio=${formatMetric(staticArtifactMask.maskRatio)} " +
                    "staticArtifactConfidence=${formatMetric(staticArtifactMask.confidence)}"
            )
            val referenceSelectionStarted = System.nanoTime()
            val referenceSelection = ReferenceFrameSelector().select(analyzedFrames.map { it.analysis })
            pipelineTiming.record(
                "reference_selection",
                (System.nanoTime() - referenceSelectionStarted) / 1_000_000L
            )
            val selectedReference = analyzedFrames.first {
                it.analysis.id == referenceSelection.analysis.id
            }
            val referenceDimensions = checkNotNull(
                dimensionsByFrameKey[selectedReference.frame.key]
            )
            val targetWidth = referenceDimensions.first
            val targetHeight = referenceDimensions.second
            val selectedFrames = (
                listOf(selectedReference.frame) + cappedFrames.filterNot {
                    it.key == selectedReference.frame.key
                }
                ).take(MAX_PROFILE_FRAMES)
            val analysisByFrameKey = analyzedFrames.associateBy { it.frame.key }
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "selectedPreset=${profile.name} inputFrameCount=${frames.size} " +
                    "selectedReference=${selectedReference.frame.fileName} " +
                    "stars=${selectedReference.analysis.reliableStarCount} " +
                    "starWidth=${formatMetric(selectedReference.analysis.medianStarWidth)} " +
                    "ellipticity=${formatMetric(selectedReference.analysis.medianStarEllipticity)} " +
                    "noise=${formatMetric(selectedReference.analysis.backgroundNoise)} " +
                    "clipping=${formatMetric(selectedReference.analysis.clippingPercent)} " +
                    "score=${formatMetric(referenceSelection.score)}"
            )
            Log.i(
                PROFILE_REGISTRATION_TAG,
                "frame=${selectedReference.frame.fileName} " +
                    "detectedStars=${selectedReference.analysis.reliableStarCount} " +
                    "matchedStars=${selectedReference.analysis.reliableStarCount} " +
                    "inlierStars=${selectedReference.analysis.reliableStarCount} " +
                    "dx=0.0000 dy=0.0000 rotation=0.0000 scale=1.0000 residual=0.0000 " +
                    "confidence=1.0000 accepted=true rejectionReason=reference"
            )
            val warnings = mutableListOf<String>()
            var alignmentApplied = 0
            var alignmentRejected = 0
            var referenceStars = 0
            var finalStars = 0
            var sanityStatus = "passed"
            var fallback = "none"
            var fallbackReason = ""
            var transformSequenceScore = 0f
            val registrar = StarSimilarityRegistrar()
            val registrationReports = mutableListOf<FrameRegistrationReport>()

            try {
                currentStage = "Stage 1 registration"
                val registrationStarted = System.nanoTime()
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Star alignment", 0, selectedFrames.size)
                }
                referenceStars = selectedReference.analysis.reliableStarCount
                if (referenceStars < 4) {
                    warnings += "Звёзд найдено мало: $referenceStars"
                }
                val referenceRegistration = RegistrationResult(
                    dx = 0f,
                    dy = 0f,
                    rotationRadians = 0f,
                    scale = 1f,
                    detectedStars = referenceStars,
                    matchedStars = referenceStars,
                    inlierStars = referenceStars,
                    residualError = 0f,
                    confidence = 1f,
                    isReliable = true,
                    rejectionReason = null,
                    referenceStars = referenceStars,
                    registrationModel = "REFERENCE_IDENTITY",
                    scaleFixed = true,
                    rotationAllowed = false,
                    rotationRejectionReason = "reference_identity"
                )
                val allRegistrationsByKey = mutableMapOf(
                    selectedReference.frame.key to referenceRegistration
                )
                val acceptedProfileFrames = mutableListOf(
                    AcceptedProfileFrame(
                        selectedReference.frame,
                        selectedReference.analysis,
                        referenceRegistration,
                        cappedFrames.indexOfFirst { it.key == selectedReference.frame.key }
                    )
                )
                selectedFrames.drop(1).forEachIndexed { index, frame ->
                    currentCoroutineContext().ensureActive()
                    val frameNumber = index + 2
                    currentStage = "Star alignment: кадр $frameNumber из ${selectedFrames.size}"
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            "Star alignment кадра $frameNumber из ${selectedFrames.size}",
                            frameNumber,
                            selectedFrames.size
                        )
                    }
                    val analyzed = checkNotNull(analysisByFrameKey[frame.key])
                    val registration = registerProfileFrame(
                        reference = selectedReference.analysis,
                        candidate = analyzed.analysis,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        registrar = registrar,
                        forceTranslationOnly = false
                    )
                    allRegistrationsByKey[frame.key] = registration
                    logProfileRegistration(frame.fileName, registration)
                    if (registration.isReliable) {
                        acceptedProfileFrames += AcceptedProfileFrame(
                            frame,
                            analyzed.analysis,
                            registration,
                            cappedFrames.indexOfFirst { it.key == frame.key }
                        )
                    }
                }
                val sequenceValidation = TransformSequenceValidator().validate(
                    acceptedProfileFrames.map { accepted ->
                        OrderedRegistration(
                            frameId = accepted.frame.key,
                            captureIndex = accepted.captureIndex,
                            isReference = accepted.frame.key == selectedReference.frame.key,
                            registration = accepted.registration
                        )
                    },
                    retryTranslationOnly = { ordered ->
                        val accepted = acceptedProfileFrames.firstOrNull {
                            it.frame.key == ordered.frameId
                        } ?: return@validate null
                        if (accepted.frame.key == selectedReference.frame.key) return@validate null
                        registerProfileFrame(
                            reference = selectedReference.analysis,
                            candidate = accepted.analysis,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            registrar = registrar,
                            forceTranslationOnly = true
                        )
                    }
                )
                transformSequenceScore = sequenceValidation.score
                val validatedByKey = sequenceValidation.registrations.associate {
                    it.frameId to it.registration
                }
                acceptedProfileFrames.replaceAll { accepted ->
                    accepted.copy(
                        registration = validatedByKey[accepted.frame.key] ?: accepted.registration
                    )
                }
                acceptedProfileFrames.removeAll { !it.registration.isReliable }
                validatedByKey.forEach { (key, registration) ->
                    allRegistrationsByKey[key] = registration
                }
                registrationReports.clear()
                selectedFrames.forEach { frame ->
                    val registration = checkNotNull(allRegistrationsByKey[frame.key])
                    registrationReports += registration.toReport(frame.fileName)
                    logProfileRegistration(frame.fileName, registration)
                    if (!registration.isReliable) {
                        warnings += "Кадр ${frame.fileName} отклонён: " +
                            (registration.rejectionReason ?: "registration failed")
                    }
                }
                val acceptedFrames = acceptedProfileFrames.size
                alignmentApplied = (acceptedFrames - 1).coerceAtLeast(0)
                alignmentRejected = (selectedFrames.size - acceptedFrames).coerceAtLeast(0)
                if (acceptedFrames < 2) {
                    warnings += "Надёжно зарегистрирован только опорный кадр; будет сохранён лучший оригинал"
                }
                if (acceptedFrames < profile.minimumFrames) {
                    warnings += "После регистрации принято $acceptedFrames из ${selectedFrames.size} кадров"
                }
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "selectedPreset=${profile.name} totalAccepted=$acceptedFrames " +
                        "totalRejected=$alignmentRejected"
                )
                pipelineTiming.record(
                    "registration",
                    (System.nanoTime() - registrationStarted) / 1_000_000L
                )
                currentStage = "Calculating frame weights"
                val weightCalculationStarted = System.nanoTime()
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Calculating frame weights", 0, acceptedFrames)
                }
                val frameWeights = FrameWeightCalculator().calculate(
                    acceptedProfileFrames.mapIndexed { index, accepted ->
                        FrameWeightInput(
                            accepted.analysis,
                            accepted.registration,
                            isReference = index == 0
                        )
                    }
                )
                val weightsById = frameWeights.associateBy { it.frameId }
                acceptedProfileFrames.forEach { accepted ->
                    val weight = checkNotNull(weightsById[accepted.analysis.id])
                    Log.i(
                        PROFILE_REGISTRATION_TAG,
                        "frame=${accepted.frame.fileName} registrationWeight=${formatMetric(weight.registrationWeight)} " +
                            "sharpnessWeight=${formatMetric(weight.sharpnessWeight)} " +
                            "trailWeight=${formatMetric(weight.trailWeight)} " +
                            "noiseWeight=${formatMetric(weight.noiseWeight)} " +
                            "exposureWeight=${formatMetric(weight.exposureWeight)} " +
                            "rawWeight=${formatMetric(weight.rawWeight)} " +
                            "normalizedWeight=${formatMetric(weight.normalizedWeight)}"
                    )
                }
                pipelineTiming.record(
                    "weight_calculation",
                    (System.nanoTime() - weightCalculationStarted) / 1_000_000L
                )
                currentStage = "Stacking full-resolution image"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Stacking full-resolution image", 0, 1)
                }
                journalRunId?.let { runJournal.update(it, "registration_completed") }
                val pixelCount = targetWidth.toLong() * targetHeight
                val requiredCacheBytes = pixelCount * Int.SIZE_BYTES * acceptedFrames
                val requiredTemporaryBytes = requiredCacheBytes + pixelCount * 28L
                require(temporaryFiles.directory.usableSpace >=
                    requiredTemporaryBytes + MIN_CACHE_FREE_SPACE_BYTES
                ) {
                    "Недостаточно временного места для полноразмерной JPEG-обработки"
                }
                val cacheWriteContext = currentCoroutineContext()
                val decodeEstimate = ImageAllocationEstimate.bitmap(
                    targetWidth,
                    targetHeight,
                    "integration-frame-decode"
                )
                memoryBudget.requireAllocation(decodeEstimate)
                memoryTracker.recordBoundary("integration-frame-decode", decodeEstimate.bytes, 1)
                val cachedFrames = acceptedProfileFrames.mapIndexed { index, accepted ->
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(
                            "Preparing full-resolution frame ${index + 1} of $acceptedFrames",
                            index + 1,
                            acceptedFrames
                        )
                    }
                    val bitmap = decodeMedianFrame(accepted.frame, targetWidth, targetHeight)
                        ?: error("Не удалось прочитать кадр: ${accepted.frame.fileName}")
                    val cached = try {
                        ArgbFrameDiskCache.write(
                            bitmap,
                            temporaryFiles.file("frame-${index.toString().padStart(3, '0')}.argb")
                        ) {
                            if (it % 64 == 0) cacheWriteContext.ensureActive()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    accepted to cached
                }
                val integrationMaskEstimate = ImageAllocationEstimate.booleanMask(
                    targetWidth,
                    targetHeight,
                    "integration-sky-mask"
                )
                memoryBudget.requireAllocation(integrationMaskEstimate)
                memoryTracker.recordBoundary("integration-sky-mask", integrationMaskEstimate.bytes, 0)
                val initialFullResolutionSkyMask = scaleSkyMask(
                    selectedReference.skyMask.mask,
                    targetWidth,
                    targetHeight
                )
                val stackedWriter = candidateStore.createTemporaryWriter(
                    "integrated-sky",
                    targetWidth,
                    targetHeight
                )
                val coverageWriter = candidateStore.createFloatPlaneWriter(
                    "valid-coverage",
                    targetWidth,
                    targetHeight
                )
                require(memoryBudget.safeWorkingBudgetBytes >= MIN_PROFILE_WORKING_MEMORY_BYTES) {
                    "Недостаточно безопасной рабочей памяти для JPEG-интеграции"
                }
                val maximumWorkingMemory = minOf(
                    memoryBudget.safeWorkingBudgetBytes,
                    MAX_PROFILE_WORKING_MEMORY_BYTES
                )
                var integrationFinished = false
                val integrationDiagnostics = try {
                    LinearWeightedIntegrator().integrate(
                        outputWidth = targetWidth,
                        outputHeight = targetHeight,
                        frames = cachedFrames.map { (accepted, cached) ->
                            WeightedIntegrationFrame(
                                id = accepted.analysis.id,
                                source = cached,
                                transform = accepted.registration,
                                normalizedWeight = checkNotNull(
                                    weightsById[accepted.analysis.id]
                                ).normalizedWeight
                            )
                        },
                        maximumWorkingMemoryBytes = maximumWorkingMemory,
                        openSource = { cached -> FileBackedArgbPixelSource(cached) },
                        allowRobustClipping = false,
                        includeOutputPixel = { x, y -> initialFullResolutionSkyMask.contains(x, y) },
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
                    }
                }
                val stackedSky = stackedWriter.finish()
                val validCoverage = coverageWriter.finish()
                cachedFrames.forEach { (_, cached) -> cached.file.delete() }
                pipelineTiming.record("integration", integrationDiagnostics.processingDurationMillis)
                memoryTracker.recordBoundary(
                    "integration",
                    integrationDiagnostics.estimatedPeakWorkingMemoryBytes,
                    0
                )
                memoryTracker.recordTile(
                    "integration",
                    integrationDiagnostics.tileWidth,
                    integrationDiagnostics.tileHeight
                )
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "preset=${profile.name} reference=${selectedReference.frame.fileName} " +
                        "inputResolution=${commonWidth}x$commonHeight " +
                        "outputResolution=${integrationDiagnostics.outputWidth}x${integrationDiagnostics.outputHeight} " +
                        "tileSize=${integrationDiagnostics.tileWidth}x${integrationDiagnostics.tileHeight} " +
                        "acceptedFrames=${integrationDiagnostics.acceptedFrames} rejectedFrames=$alignmentRejected " +
                        "integrationMode=${integrationDiagnostics.mode} robustMode=${integrationDiagnostics.robustModeEnabled} " +
                        "validCoverage=${formatMetric(integrationDiagnostics.validCoveragePercent)} " +
                        "estimatedPeakWorkingMemory=${integrationDiagnostics.estimatedPeakWorkingMemoryBytes} " +
                        "resolutionChanged=${integrationDiagnostics.resolutionChanged}"
                )
                journalRunId?.let { runJournal.update(it, "integration_completed") }
                currentStage = "Refining sky mask"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Refining sky mask", 0, 3)
                }
                val skyMaskingStarted = System.nanoTime()
                val fullResolutionStars = scaleV2Stars(
                    selectedReference.analysis.stars,
                    selectedReference.analysis.width,
                    selectedReference.analysis.height,
                    targetWidth,
                    targetHeight
                )
                val maskStage = buildFileBackedReferenceAndMask(
                    selectedReference = selectedReference,
                    initialSkyMask = initialFullResolutionSkyMask,
                    fullResolutionStars = fullResolutionStars,
                    registrationConfidence = acceptedProfileFrames
                        .map { it.registration.confidence }
                        .average()
                        .toFloat(),
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    candidateStore = candidateStore,
                    memoryBudget = memoryBudget,
                    memoryTracker = memoryTracker
                )
                pipelineTiming.record(
                    "sky_masking",
                    (System.nanoTime() - skyMaskingStarted) / 1_000_000L
                )
                journalRunId?.let { runJournal.update(it, "reference_and_mask_completed") }

                currentStage = "Combining sky and foreground"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Combining sky and foreground", 2, 3)
                }
                val cleanWriter = candidateStore.createWriter(
                    ResultCandidateType.CLEAN_STACK,
                    targetWidth,
                    targetHeight
                )
                val effectiveAlphaWriter = candidateStore.createFloatPlaneWriter(
                    "effective-sky",
                    targetWidth,
                    targetHeight
                )
                val composite = FileBackedFloatPlaneReader(maskStage.featheredSkyMask).use { feathered ->
                    FileBackedFloatPlaneReader(validCoverage).use { coverage ->
                        FileBackedSkyForegroundComposer().compose(
                            stackedSky = stackedSky,
                            reference = maskStage.referenceCandidate,
                            featheredSkyMask = feathered,
                            validCoverage = coverage,
                            output = cleanWriter,
                            effectiveAlphaOutput = effectiveAlphaWriter,
                            memoryBudget = memoryBudget,
                            memoryTracker = memoryTracker
                        )
                    }
                }
                val cleanStackHandle = candidateStore.register(
                    ResultCandidateType.CLEAN_STACK,
                    composite.image
                )
                val effectiveSkyAlpha = composite.effectiveSkyAlpha
                candidateStore.deleteTemporary(maskStage.featheredSkyMask)
                pipelineTiming.record(
                    "foreground_composition",
                    composite.diagnostics.compositionDurationMillis
                )
                require(composite.diagnostics.maximumForegroundChannelDifference <= 1) {
                    "Композиция изменила защищённый foreground"
                }
                require(
                    composite.diagnostics.foregroundSharpnessAfter + FOREGROUND_SHARPNESS_TOLERANCE >=
                        composite.diagnostics.foregroundSharpnessBefore
                ) {
                    "Резкость защищённого foreground снизилась"
                }
                journalRunId?.let { runJournal.update(it, "clean_stack_composed") }

                currentStage = "Clean stack validation"
                val qualityAnalysisStarted = System.nanoTime()
                val fullStaticArtifactMask = staticArtifactMask.scaledTo(targetWidth, targetHeight)
                val qualityAnalyzer = FileBackedResultQualityAnalyzer(
                    staticArtifactMask = fullStaticArtifactMask
                )
                val referenceCandidate = StoredResultCandidate(
                    ResultCandidateType.REFERENCE,
                    maskStage.referenceCandidate,
                    qualityAnalyzer.analyze(
                        maskStage.referenceCandidate,
                        maskStage.referenceCandidate,
                        effectiveSkyAlpha
                    )
                )
                val qualityGate = AstroResultQualityGate()
                val referenceDecision = qualityGate.evaluateReference(referenceCandidate)
                require(referenceDecision.accepted) {
                    "Selected JPEG reference failed structural validation: " +
                        referenceDecision.hardFailureReasons.joinToString("|")
                }
                val cleanStackCandidate = StoredResultCandidate(
                    ResultCandidateType.CLEAN_STACK,
                    cleanStackHandle,
                    qualityAnalyzer.analyze(
                        cleanStackHandle,
                        maskStage.referenceCandidate,
                        effectiveSkyAlpha
                    )
                )
                val beforeMetrics = profileMetricsFromQuality(cleanStackCandidate.metrics)
                logProfileStage(profile, source, "skyForegroundComposite", beforeMetrics)
                val retentionValidation = ReferenceStarRetentionValidator().validate(
                    maskStage.referenceCandidate,
                    cleanStackHandle,
                    fullResolutionStars
                )
                val coverageValidation = CoverageUniformityValidator().validate(
                    validCoverage,
                    effectiveSkyAlpha
                )
                val lineArtifactValidation = LineArtifactDetector().compare(
                    maskStage.referenceCandidate,
                    cleanStackHandle,
                    effectiveSkyAlpha
                )
                val cleanStackEvidence = CleanStackValidationEvidence(
                    referenceStarRetention = retentionValidation,
                    coverageUniformity = coverageValidation,
                    lineArtifacts = lineArtifactValidation,
                    transformSequenceScore = transformSequenceScore,
                    acceptedFrameCount = acceptedFrames,
                    transformSequenceValid = acceptedFrames >= 2 && transformSequenceScore >= 0.50f
                )
                val cleanStackDecision = qualityGate.evaluateCleanStack(
                    referenceCandidate,
                    cleanStackCandidate,
                    profile,
                    cleanStackEvidence
                )
                Log.i(
                    "AstroPhotoJpegCleanStack",
                    "accepted=${cleanStackDecision.accepted} " +
                        "retention=${formatMetric(retentionValidation.metrics.retentionRatio)} " +
                        "coverageScore=${formatMetric(coverageValidation.metrics.uniformityScore)} " +
                        "lineArtifactScore=${formatMetric(lineArtifactValidation.metrics.lineArtifactScore)} " +
                        "hardFailures=${cleanStackDecision.hardFailureReasons.joinToString("|")}"
                )
                journalRunId?.let { runJournal.update(it, "clean_stack_validated") }

                val stage4Executed = CleanStackExecutionPolicy()
                    .shouldExecuteStage4(cleanStackDecision)
                val processedCandidate: StoredResultCandidate?
                val processedDecision: QualityGateDecision
                if (stage4Executed) {
                    currentStage = "File-backed adaptive processing"
                    val alignedStackStars = qualityAnalyzer.detectStars(
                        stackedSky,
                        effectiveSkyAlpha
                    )
                    val adaptiveResult = FileBackedAdaptivePresetProcessor().process(
                        stackedSky = stackedSky,
                        referenceForeground = maskStage.referenceCandidate,
                        effectiveSkyAlpha = effectiveSkyAlpha,
                        profile = profile,
                        frameCount = acceptedFrames,
                        alignedStackStars = alignedStackStars,
                        store = candidateStore,
                        memoryBudget = memoryBudget,
                        memoryTracker = memoryTracker,
                        onProgress = { message, current, total ->
                            currentStage = message
                            withContext(Dispatchers.Main.immediate) {
                                onProgress(message, current, total)
                            }
                        }
                    )
                    pipelineTiming.recordAll(adaptiveResult.diagnostics.stageDurationsMillis)
                    processedCandidate = StoredResultCandidate(
                        ResultCandidateType.PROCESSED,
                        adaptiveResult.image,
                        qualityAnalyzer.analyze(
                            adaptiveResult.image,
                            maskStage.referenceCandidate,
                            effectiveSkyAlpha
                        )
                    )
                    processedDecision = qualityGate.evaluateProcessed(
                        referenceCandidate,
                        cleanStackCandidate,
                        processedCandidate,
                        profile,
                        acceptedFrames
                    )
                    Log.i(
                        ADAPTIVE_PROCESSING_TAG,
                        "preset=${profile.name} fileBacked=true " +
                            "skyMedianBefore=${formatMetric(adaptiveResult.diagnostics.before.luminanceMedian)} " +
                            "skyMedianAfter=${formatMetric(adaptiveResult.diagnostics.after.luminanceMedian)} " +
                            "foregroundDifferenceOutsideMask=" +
                            "${adaptiveResult.diagnostics.foregroundDifferenceOutsideMask}"
                    )
                } else {
                    pipelineTiming.record("stage4_skipped", 0L)
                    processedCandidate = null
                    processedDecision = QualityGateDecision(
                        accepted = false,
                        score = 0f,
                        hardFailureReasons = listOf("stage4_skipped_clean_stack_invalid"),
                        warningReasons = cleanStackDecision.warningReasons,
                        metrics = referenceCandidate.metrics
                    )
                }
                journalRunId?.let {
                    runJournal.update(
                        it,
                        if (stage4Executed) "stage4_completed" else "stage4_skipped"
                    )
                }
                currentStage = "Final quality analysis"
                val finalSelection = ResultSelectionPolicy().select(
                    referenceCandidate,
                    cleanStackCandidate,
                    processedCandidate,
                    processedDecision,
                    cleanStackDecision
                )
                pipelineTiming.record(
                    "quality_analysis",
                    (System.nanoTime() - qualityAnalysisStarted) / 1_000_000L
                )
                val selectedProfileMetrics = profileMetricsFromQuality(finalSelection.selected.metrics)
                finalStars = finalSelection.selected.metrics.reliableStarCount
                sanityStatus = if (processedDecision.accepted) "passed" else "fallback"
                fallback = finalSelection.internalFallbackLabel ?: "none"
                fallbackReason = finalSelection.fallbackReason.orEmpty()
                if (finalSelection.fallbackUsed) {
                    warnings += qualityFallbackWarning(
                        finalSelection.selected.type,
                        fallbackReason
                    )
                }
                logProfileStage(profile, source, "finalQualityGate", selectedProfileMetrics)
                journalRunId?.let { runJournal.update(it, "final_candidate_selected") }

                if (profile == AstroProcessingProfile.MAX_STARS) {
                    warnings += "Режим может усилить шум и артефакты"
                }
                if (alignmentRejected > 0) {
                    warnings += "Кадров отклонено из-за ненадёжной регистрации: $alignmentRejected"
                }
                Log.i(
                    PROFILE_REGISTRATION_TAG,
                    "preset=${profile.name} reference=${selectedReference.frame.fileName} " +
                        "refinedSkyMaskConfidence=${formatMetric(maskStage.confidence)} " +
                        "initialSkyRatio=${formatMetric(maskStage.initialSkyRatio)} " +
                        "refinedSkyRatio=${formatMetric(maskStage.refinedSkyRatio)} " +
                        "protectedForegroundRatio=${formatMetric(maskStage.protectedForegroundRatio)} " +
                        "thinStructureProtectedPixelCount=${maskStage.thinStructureProtectedPixels} " +
                        "protectionRadius=${maskStage.protectionRadius} featherRadius=${maskStage.featherRadius} " +
                        "validSkyCoverageRatio=${formatMetric(composite.diagnostics.validSkyCoverageRatio)} " +
                        "output=${composite.diagnostics.outputWidth}x${composite.diagnostics.outputHeight}"
                )

                val now = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
                val requestedFileName = "${profile.filePrefix}_$timestamp.png"
                val frameNameById = acceptedProfileFrames.associate {
                    it.analysis.id to it.frame.fileName
                }
                var processingReport = ProcessingReport(
                    timestampMillis = now,
                    presetId = profile.name,
                    presetDisplayName = profile.title,
                    inputFrameCount = frames.size + framesRejected,
                    eligibleFrameCount = selectedFrames.size,
                    acceptedFrameCount = acceptedFrames,
                    rejectedFrameCount = framesRejected +
                        (frames.size - selectedFrames.size) + alignmentRejected,
                    selectedReference = selectedReference.frame.fileName,
                    skyMaskConfidence = maskStage.confidence,
                    skyRatio = alphaCoverageRatio(effectiveSkyAlpha),
                    foregroundRatio = maskStage.protectedForegroundRatio,
                    registrations = registrationReports.toList(),
                    frameWeights = frameWeights.map { weight ->
                        FrameWeightReport(
                            frameName = frameNameById[weight.frameId] ?: weight.frameId,
                            registrationWeight = weight.registrationWeight,
                            sharpnessWeight = weight.sharpnessWeight,
                            trailWeight = weight.trailWeight,
                            noiseWeight = weight.noiseWeight,
                            exposureWeight = weight.exposureWeight,
                            normalizedWeight = weight.normalizedWeight
                        )
                    },
                    integration = IntegrationReport(
                        mode = integrationDiagnostics.mode.name,
                        robustMode = integrationDiagnostics.robustModeEnabled,
                        inputWidth = commonWidth,
                        inputHeight = commonHeight,
                        outputWidth = integrationDiagnostics.outputWidth,
                        outputHeight = integrationDiagnostics.outputHeight,
                        tileWidth = integrationDiagnostics.tileWidth,
                        tileHeight = integrationDiagnostics.tileHeight,
                        resolutionChanged = integrationDiagnostics.resolutionChanged,
                        validCoveragePercent = integrationDiagnostics.validCoveragePercent,
                        estimatedWorkingMemoryBytes = integrationDiagnostics.estimatedPeakWorkingMemoryBytes,
                        outputAllocationBytes = 0L,
                        diskCacheBytes = requiredCacheBytes,
                        robustModeReason = integrationDiagnostics.robustModeReason
                    ),
                    stage4Parameters = ExistingPresetParameterMapper.parametersFor(
                        profile,
                        acceptedFrames
                    ),
                    referenceMetrics = referenceCandidate.metrics,
                    cleanStackMetrics = cleanStackCandidate.metrics,
                    processedMetrics = processedCandidate?.metrics ?: referenceCandidate.metrics,
                    cleanStackDecision = cleanStackDecision,
                    processedDecision = processedDecision,
                    selectedCandidateType = finalSelection.selected.type.name,
                    fallbackUsed = finalSelection.fallbackUsed,
                    fallbackReason = finalSelection.fallbackReason,
                    internalFallbackLabel = finalSelection.internalFallbackLabel,
                    warnings = (
                        warnings + cleanStackDecision.warningReasons +
                            processedDecision.warningReasons
                        ).distinct(),
                    outputPngDisplayName = requestedFileName,
                    stageDurationsMillis = pipelineTiming.snapshot(),
                    stage4Executed = stage4Executed,
                    cleanStackAccepted = cleanStackDecision.accepted,
                    cleanStackRejectionReasons = cleanStackDecision.hardFailureReasons,
                    referenceReliableStarCount = retentionValidation.metrics.referenceReliableStarCount,
                    retainedReferenceStarCount = retentionValidation.metrics.retainedReferenceStarCount,
                    referenceStarRetentionRatio = retentionValidation.metrics.retentionRatio,
                    referenceStarContrastBefore = retentionValidation.metrics.medianContrastBefore,
                    referenceStarContrastAfter = retentionValidation.metrics.medianContrastAfter,
                    referenceStarWidthBefore = retentionValidation.metrics.medianWidthBefore,
                    referenceStarWidthAfter = retentionValidation.metrics.medianWidthAfter,
                    referenceStarSmearRate = retentionValidation.metrics.lineLikeSmearRate,
                    coverageMinimum = coverageValidation.metrics.minimumCoverage,
                    coverageMedian = coverageValidation.metrics.medianCoverage,
                    coverageMaximum = coverageValidation.metrics.maximumCoverage,
                    coverageUniformityScore = coverageValidation.metrics.uniformityScore,
                    coverageWedgeDiscontinuityScore = coverageValidation.metrics.wedgeDiscontinuityScore,
                    lineArtifactScore = lineArtifactValidation.metrics.lineArtifactScore,
                    fanPatternScore = lineArtifactValidation.metrics.fanPatternScore,
                    transformSequenceScore = transformSequenceScore,
                    staticArtifactCandidates = staticArtifactMask.regions.size,
                    staticArtifactMaskRatio = staticArtifactMask.maskRatio,
                    runtimeMaxHeapBytes = memoryBudget.snapshot.maxHeapBytes,
                    heapUsedAtRunStartBytes = memoryBudget.snapshot.usedHeapBytes,
                    safeWorkingBudgetBytes = memoryBudget.safeWorkingBudgetBytes,
                    peakEstimatedResidentBytes = memoryTracker.peakEstimatedResidentBytes,
                    peakObservedHeapBytes = memoryTracker.peakObservedHeapBytes,
                    maximumSimultaneousFullResolutionCandidates =
                        memoryTracker.maximumSimultaneousFullResolutionHeapImages,
                    referenceCandidateBytesOnDisk = referenceCandidate.image.expectedBytes,
                    cleanStackCandidateBytesOnDisk = cleanStackCandidate.image.expectedBytes,
                    processedCandidateBytesOnDisk = processedCandidate?.image?.expectedBytes ?: 0L,
                    tileSizePerStage = memoryTracker.tileSizes(),
                    haloSizePerStage = memoryTracker.haloSizes(),
                    memoryPressureRetries = memoryTracker.memoryPressureRetries,
                    finalBitmapAllocationBytes = 0L,
                    lastCompletedStage = "report_prepared",
                    reportPublicationMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "MEDIASTORE_FILES"
                    } else {
                        "LEGACY_PUBLIC_FILE"
                    },
                    reportFallbackUsed = false,
                    staleRunRecoveryInformation = if (staleRunsRecovered > 0) {
                        "cleaned_$staleRunsRecovered"
                    } else {
                        null
                    }
                )
                var reportJson = processingReport.toJson()
                temporaryFiles.atomicTextFile("final-report.json", reportJson)
                journalRunId?.let { runJournal.update(it, "report_prepared") }
                currentStage = "Saving lossless PNG"
                withContext(Dispatchers.Main.immediate) {
                    onProgress("Saving lossless PNG", 3, 3)
                }
                val pngWritingStarted = System.nanoTime()
                val saved = FileBackedImageReader(finalSelection.selected.image).use { selectedReader ->
                    LosslessProcessedImageWriter(context).write(
                        session,
                        selectedReader,
                        requestedFileName
                    )
                }
                pipelineTiming.record(
                    "png_writing",
                    (System.nanoTime() - pngWritingStarted) / 1_000_000L
                )
                val fileName = saved.fileName
                processingReport = processingReport.copy(
                    outputPngDisplayName = fileName,
                    stageDurationsMillis = pipelineTiming.snapshot(),
                    lastCompletedStage = "png_saved"
                )
                reportJson = processingReport.toJson()
                temporaryFiles.atomicTextFile("final-report.json", reportJson)
                journalRunId?.let { runJournal.update(it, "png_saved") }

                currentStage = "Writing processing report"
                val reportOutcome = try {
                    ReportWriteOutcome.Written(
                        ProcessingReportWriter(context).write(
                            session,
                            fileName,
                            reportJson
                        )
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    ReportWriteOutcome.Failed(error.message ?: error::class.java.simpleName)
                }
                val additionalFiles = when (reportOutcome) {
                    is ReportWriteOutcome.Written -> {
                        pipelineTiming.record(
                            "report_writing",
                            reportOutcome.value.writeDurationMillis
                        )
                        if (reportOutcome.value.fallbackUsed) {
                            processingReport = processingReport.copy(
                                reportPublicationMode = reportOutcome.value.publicationMode,
                                reportFallbackUsed = true
                            )
                            reportJson = processingReport.toJson()
                            temporaryFiles.atomicTextFile("final-report.json", reportJson)
                            runCatching {
                                AppSpecificProcessingReportStore(context).write(
                                    session.folderName,
                                    fileName,
                                    reportJson
                                )
                            }.onSuccess {
                                warnings += "Processing report stored in app-specific fallback"
                            }.onFailure { error ->
                                warnings += "Processing report fallback update failed: " +
                                    (error.message ?: error::class.java.simpleName)
                            }
                        }
                        listOf(reportOutcome.value.fileName)
                    }
                    is ReportWriteOutcome.Failed -> {
                        warnings += "Processing report was not written: ${reportOutcome.reason}"
                        val recovery = File(
                            context.cacheDir,
                            "jpeg-report-recovery-${journalRunId?.take(8) ?: "unknown"}.json"
                        )
                        runCatching { recovery.writeText(reportJson, Charsets.UTF_8) }
                        emptyList()
                    }
                }
                journalRunId?.let { runJournal.update(it, "report_published") }
                Log.i(
                    "AstroPhotoJpegMemory",
                    "peakEstimated=${memoryTracker.peakEstimatedResidentBytes} " +
                        "peakObservedHeap=${memoryTracker.peakObservedHeapBytes} " +
                        "maxFullHeapImages=${memoryTracker.maximumSimultaneousFullResolutionHeapImages} " +
                        "retries=${memoryTracker.memoryPressureRetries} " +
                        "candidateStorage=FILE_BACKED_ARGB_8888 finalBitmapBytes=0"
                )
                Log.i(
                    "AstroPhotoJpegTiming",
                    pipelineTiming.snapshot().entries.joinToString(" ") { (stage, duration) ->
                        "$stage=${duration}ms"
                    }
                )
                Log.i(
                    "AstroPhotoProfile",
                    "profile=${profile.name} source=${source.metadataValue} " +
                        "inputFrames=${selectedFrames.size} acceptedFrames=$acceptedFrames " +
                        "alignedFrames=$alignmentApplied alignmentFailures=$alignmentRejected " +
                        "stackingMethod=linearWeighted starsBefore=${beforeMetrics.stars} " +
                        "starsAfter=${selectedProfileMetrics.stars} " +
                        "backgroundBefore=${beforeMetrics.background} " +
                        "backgroundAfter=${selectedProfileMetrics.background} sanity=$sanityStatus " +
                        "fallback=$fallback fallbackReason=$fallbackReason outputName=$fileName"
                )
                val infoUpdated = runCatching {
                    appendProfileSessionInfo(
                        session = session,
                        fileName = fileName,
                        profile = profile,
                        method = "Full-resolution JPEG v2 Stage 7 file-backed pipeline",
                        framesUsed = acceptedFrames,
                        framesRejected = framesRejected + (frames.size - selectedFrames.size) +
                            alignmentRejected,
                        alignmentMode = "STAR_SIMILARITY",
                        alignmentApplied = alignmentApplied,
                        alignmentRejected = alignmentRejected,
                        roiMode = recipe.roiName,
                        backgroundRemoval = "ADAPTIVE_SKY_GRADIENT",
                        stretchMode = "ADAPTIVE_ASINH",
                        starBoost = "LOCAL_STAR_CONTRAST",
                        starsBefore = referenceStars,
                        starsAfter = finalStars,
                        source = source,
                        sanity = sanityStatus,
                        fallback = fallback,
                        fallbackReason = fallbackReason,
                        warnings = warnings.distinct(),
                        processedAtMillis = now
                    )
                }.isSuccess
                JpegStackResult(
                    fileName = fileName,
                    displayPath = saved.displayPath,
                    contentUri = saved.contentUri,
                    filePath = saved.filePath,
                    frameCount = acceptedFrames,
                    sessionInfoUpdated = infoUpdated,
                    alignmentEnabled = alignmentApplied > 0,
                    astroStretchApplied = true,
                    downscaled = false,
                    profile = profile,
                    selectedResultType = finalSelection.selected.type.name,
                    starsBefore = cleanStackCandidate.metrics.reliableStarCount,
                    starCount = finalStars,
                    fallbackUsed = finalSelection.fallbackUsed,
                    fallbackReason = finalSelection.fallbackReason,
                    warnings = warnings.distinct(),
                    additionalFiles = additionalFiles
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException(
                    "Недостаточно памяти для полноразмерной JPEG-обработки; разрешение результата не уменьшалось.",
                    error
                )
            }
        }
        runCatching { pipelineFiles?.close() }
        journalRunId?.let { runId ->
            when {
                stackResult.isSuccess -> runCatching {
                    runJournal.markCompleted(runId, "completed")
                }
                stackResult.exceptionOrNull() is CancellationException -> runCatching {
                    runJournal.markCompleted(runId, "cancelled")
                }
                else -> runCatching {
                    runJournal.recordFailure(
                        runId,
                        currentStage,
                        stackResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                    )
                }
            }
        }
        stackResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            Log.i(
                "AstroPhotoProfile",
                "profile=${profile.name} source=${source.metadataValue} inputFrames=${frames.size} " +
                    "alignedFrames=0 alignmentFailures=0 stackingMethod=unknown " +
                    "starsBefore=-1 starsAfter=-1 backgroundBefore=-1 backgroundAfter=-1 " +
                    "sanity=failed fallback=none fallbackReason=${error.message.orEmpty()} outputName="
            )
            Log.e(
                "AstroPhotoProcessing",
                "Profile ${profile.title} failed at $currentStage: ${error.message}",
                error
            )
            return@withContext Result.failure(
                IllegalStateException(
                    "Ошибка на этапе: $currentStage. " +
                        (error.message ?: "Неизвестная ошибка"),
                    error
                )
            )
        }
        stackResult
    }

    private data class FileBackedMaskStageResult(
        val referenceCandidate: FileBackedImage,
        val featheredSkyMask: FileBackedFloatPlane,
        val confidence: Float,
        val initialSkyRatio: Float,
        val refinedSkyRatio: Float,
        val protectedForegroundRatio: Float,
        val thinStructureProtectedPixels: Int,
        val protectionRadius: Int,
        val featherRadius: Int
    )

    private fun buildFileBackedReferenceAndMask(
        selectedReference: ProfileAnalyzedFrame,
        initialSkyMask: SkyMask,
        fullResolutionStars: List<V2DetectedStar>,
        registrationConfidence: Float,
        targetWidth: Int,
        targetHeight: Int,
        candidateStore: ResultCandidateStore,
        memoryBudget: JpegMemoryBudget,
        memoryTracker: PipelineMemoryTracker
    ): FileBackedMaskStageResult {
        val writer = candidateStore.createWriter(
            ResultCandidateType.REFERENCE,
            targetWidth,
            targetHeight
        )
        val decodeEstimate = ImageAllocationEstimate.bitmap(
            targetWidth,
            targetHeight,
            "reference-bitmap-decode"
        )
        memoryBudget.requireAllocation(decodeEstimate)
        memoryTracker.recordBoundary("reference-bitmap-decode", decodeEstimate.bytes, 1)
        val bitmap = decodeMedianFrame(selectedReference.frame, targetWidth, targetHeight)
            ?: error("Не удалось прочитать опорный кадр для композиции")
        try {
            val row = IntArray(targetWidth)
            for (y in 0 until targetHeight) {
                bitmap.getPixels(row, 0, targetWidth, 0, y, targetWidth, 1)
                writer.writeRow(y, row)
            }
        } finally {
            bitmap.recycle()
        }
        val reference = candidateStore.register(ResultCandidateType.REFERENCE, writer.finish())
        val maskStageBytes = ImageAllocationEstimate.intArray(targetWidth, targetHeight).bytes +
            ImageAllocationEstimate.floatArray(targetWidth, targetHeight).bytes +
            ImageAllocationEstimate.booleanMask(targetWidth, targetHeight).bytes * 4L
        memoryBudget.requireAllocation(
            ImageAllocationEstimate("reference-mask-stage", maskStageBytes)
        )
        memoryTracker.recordBoundary("reference-mask-stage", maskStageBytes, 1)

        val referencePixels = IntArray(targetWidth * targetHeight)
        FileBackedImageReader(reference).use { reader ->
            val row = IntArray(targetWidth)
            for (y in 0 until targetHeight) {
                reader.readArgbRow(y, row)
                row.copyInto(referencePixels, destinationOffset = y * targetWidth)
            }
        }
        val referenceImage = ArgbPixelImage(targetWidth, targetHeight, referencePixels)
        val initialRefinedMask = SkyMaskRefiner().refine(
            initialMask = initialSkyMask,
            reference = referenceImage,
            stars = fullResolutionStars,
            initialConfidence = selectedReference.skyMask.confidence,
            initialUsedFallback = selectedReference.skyMask.usedFallback,
            registrationConfidence = registrationConfidence
        )
        val protection = ForegroundProtectionMask().detect(
            referenceImage,
            fullResolutionStars
        )
        val finalSkyPixels = initialRefinedMask.binaryMask.copyPixels()
        val protectionPixels = protection.mask.copyPixels()
        finalSkyPixels.indices.forEach { index ->
            if (protectionPixels[index]) finalSkyPixels[index] = false
        }
        val finalBinarySkyMask = SkyMask(targetWidth, targetHeight, finalSkyPixels)
        val feathering = MaskFeathering().feather(
            initialRefinedMask.binaryMask,
            protection.mask
        )
        val featherWriter = candidateStore.createFloatPlaneWriter(
            "feathered-sky",
            targetWidth,
            targetHeight
        )
        val alphaRow = FloatArray(targetWidth)
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                alphaRow[x] = feathering.alphaMask.alphaAt(x, y)
            }
            featherWriter.writeRow(y, alphaRow)
        }
        return FileBackedMaskStageResult(
            referenceCandidate = reference,
            featheredSkyMask = featherWriter.finish(),
            confidence = initialRefinedMask.confidence,
            initialSkyRatio = initialRefinedMask.diagnostics.initialSkyRatio,
            refinedSkyRatio = finalBinarySkyMask.retainedFraction(),
            protectedForegroundRatio = 1f - finalBinarySkyMask.retainedFraction(),
            thinStructureProtectedPixels = protection.protectedPixelCount,
            protectionRadius = protection.dilationRadius,
            featherRadius = feathering.broadRadius
        )
    }

    private fun alphaCoverageRatio(plane: FileBackedFloatPlane): Float {
        var positive = 0L
        FileBackedFloatPlaneReader(plane).use { reader ->
            val row = FloatArray(plane.width)
            for (y in 0 until plane.height) {
                reader.readAlphaRow(y, row)
                positive += row.count { it > 0f }
            }
        }
        return positive.toFloat() / (plane.width.toLong() * plane.height).coerceAtLeast(1L)
    }

    private fun profileMetricsFromQuality(
        metrics: com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
    ): ProfileSanityMetrics = ProfileSanityMetrics(
        width = metrics.width,
        height = metrics.height,
        stars = metrics.reliableStarCount,
        averageStarContrast = metrics.medianStarLocalContrast,
        blackPercent = metrics.blackBorderRatio * 100f,
        whitePercent = maxOf(
            metrics.channelClippingPercent.red,
            metrics.channelClippingPercent.green,
            metrics.channelClippingPercent.blue
        ),
        background = (metrics.skyMedian * 255f).roundToInt().coerceIn(0, 255),
        dynamicRange = ((metrics.skyHighPercentile - metrics.skyLowPercentile) * 255f)
            .roundToInt().coerceAtLeast(0),
        channelsValid = true,
        medianStarContrast = metrics.medianStarLocalContrast,
        lowPercentile = (metrics.skyLowPercentile * 255f).roundToInt().coerceIn(0, 255),
        highPercentile = (metrics.skyHighPercentile * 255f).roundToInt().coerceIn(0, 255),
        backgroundSpread = (metrics.skyMad * 255f).roundToInt().coerceAtLeast(0),
        largeScaleBanding = metrics.banding.combinedScore
    )

    suspend fun loadResultPreview(
        result: JpegStackResult,
        maxSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            result.contentUri != null
        ) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(result.contentUri),
                    Size(maxSize, maxSize),
                    null
                )
            }.getOrNull()
        } else {
            result.filePath?.let { decodeSampledFile(it, maxSize) }
        }
    }

    private fun readDimensions(frame: SessionFrame): Pair<Int, Int>? {
        return readOrientedJpegDimensions { openFrame(frame) }
    }

    private fun decodeFrame(frame: SessionFrame): Bitmap? =
        decodeOrientedJpeg(openStream = { openFrame(frame) })

    private fun openFrame(frame: SessionFrame): InputStream? =
        if (frame.contentUri != null) {
            context.contentResolver.openInputStream(Uri.parse(frame.contentUri))
        } else {
            frame.filePath?.let { File(it).inputStream() }
        }

    private data class MedianPreparedFrame(
        val bitmap: Bitmap,
        val shift: AlignmentShift
    )

    private data class ProfileAnalyzedFrame(
        val frame: SessionFrame,
        val analysis: FrameAnalysis,
        val skyMask: SkyMaskResult
    )

    private data class AcceptedProfileFrame(
        val frame: SessionFrame,
        val analysis: FrameAnalysis,
        val registration: RegistrationResult,
        val captureIndex: Int = 0
    )

    private fun decodeMedianFrame(
        frame: SessionFrame,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val dimensions = readDimensions(frame) ?: return null
        var sampleSize = 1
        while (
            dimensions.first / (sampleSize * 2) >= targetWidth &&
            dimensions.second / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        val decoded = decodeOrientedJpeg({ openFrame(frame) }, sampleSize) ?: return null
        if (decoded.width == targetWidth && decoded.height == targetHeight) {
            return decoded
        }
        return try {
            Bitmap.createScaledBitmap(
                decoded,
                targetWidth,
                targetHeight,
                true
            )
        } finally {
            decoded.recycle()
        }
    }

    private suspend fun calculateMedian(
        frames: List<MedianPreparedFrame>,
        width: Int,
        height: Int,
        onRowCompleted: suspend (Int) -> Unit
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val sourceRows = Array(frames.size) { IntArray(width) }
            val outputRow = IntArray(width)
            val colors = IntArray(frames.size)
            val redValues = IntArray(frames.size)
            val greenValues = IntArray(frames.size)
            val blueValues = IntArray(frames.size)

            for (y in 0 until height) {
                currentCoroutineContext().ensureActive()
                frames.forEachIndexed { index, frame ->
                    readShiftedPixels(
                        bitmap = frame.bitmap,
                        destination = sourceRows[index],
                        top = y,
                        rows = 1,
                        dx = frame.shift.dx,
                        dy = frame.shift.dy,
                        fillColor = 0xFF000000.toInt()
                    )
                }
                for (x in 0 until width) {
                    frames.indices.forEach { index ->
                        colors[index] = sourceRows[index][x]
                    }
                    outputRow[x] = medianArgbPixel(
                        colors = colors,
                        redValues = redValues,
                        greenValues = greenValues,
                        blueValues = blueValues
                    )
                }
                output.setPixels(outputRow, 0, width, 0, y, width, 1)
                onRowCompleted(y + 1)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private suspend fun calculateSigmaClipping(
        frames: List<MedianPreparedFrame>,
        width: Int,
        height: Int,
        sigma: Double,
        signalPreserving: Boolean = false,
        onRowCompleted: suspend (Int) -> Unit
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val sourceRows = Array(frames.size) { IntArray(width) }
            val outputRow = IntArray(width)
            val colors = IntArray(frames.size)
            val redValues = IntArray(frames.size)
            val greenValues = IntArray(frames.size)
            val blueValues = IntArray(frames.size)
            val sortedScratch = IntArray(frames.size)

            for (y in 0 until height) {
                currentCoroutineContext().ensureActive()
                frames.forEachIndexed { index, frame ->
                    readShiftedPixels(
                        bitmap = frame.bitmap,
                        destination = sourceRows[index],
                        top = y,
                        rows = 1,
                        dx = frame.shift.dx,
                        dy = frame.shift.dy,
                        fillColor = 0xFF000000.toInt()
                    )
                }
                for (x in 0 until width) {
                    frames.indices.forEach { index ->
                        val color = sourceRows[index][x]
                        colors[index] = color
                        if (signalPreserving) {
                            redValues[index] = color ushr 16 and 0xFF
                            greenValues[index] = color ushr 8 and 0xFF
                            blueValues[index] = color and 0xFF
                        }
                    }
                    outputRow[x] = if (signalPreserving) {
                        val red = signalPreservingSigmaChannel(
                            redValues,
                            sigma,
                            sortedScratch = sortedScratch
                        )
                        val green = signalPreservingSigmaChannel(
                            greenValues,
                            sigma,
                            sortedScratch = sortedScratch
                        )
                        val blue = signalPreservingSigmaChannel(
                            blueValues,
                            sigma,
                            sortedScratch = sortedScratch
                        )
                        0xFF000000.toInt() or
                            (red shl 16) or
                            (green shl 8) or
                            blue
                    } else {
                        sigmaClipArgbPixel(
                            colors = colors,
                            sigmaThreshold = sigma,
                            iterations = 1,
                            redValues = redValues,
                            greenValues = greenValues,
                            blueValues = blueValues
                        )
                    }
                }
                output.setPixels(outputRow, 0, width, 0, y, width, 1)
                onRowCompleted(y + 1)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private data class ManualAlignmentReference(
        val image: ArgbPixelImage,
        val scaleX: Float,
        val scaleY: Float
    )

    private fun createAlignmentReference(
        frame: SessionFrame,
        targetWidth: Int,
        targetHeight: Int
    ): ManualAlignmentReference {
        val bitmap = decodePreparedFrame(frame, targetWidth, targetHeight)
            ?: error("Не удалось подготовить опорный кадр")
        return try {
            createManualAlignmentSample(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun findAlignmentOrZero(
        reference: ManualAlignmentReference,
        candidate: Bitmap,
        frameNumber: Int,
        totalFrames: Int,
        safeMode: Boolean,
        source: ManualStackingSource,
        onAlignment: suspend (
            current: Int,
            total: Int,
            message: String
        ) -> Unit
    ): AlignmentShift {
        onAlignment(
            frameNumber,
            totalFrames,
            "Выравнивание кадра $frameNumber из $totalFrames"
        )
        return try {
            val candidateSample = createManualAlignmentSample(candidate)
            val maxShift = ceil(30f / reference.scaleX).toInt().coerceAtLeast(1)
            val diagnostic = alignManualImages(
                reference.image,
                candidateSample.image,
                safeMode = safeMode,
                maxShiftPx = maxShift
            )
            val shift = diagnostic.shift.copy(
                dx = (diagnostic.shift.dx * reference.scaleX).roundToInt().coerceIn(-30, 30),
                dy = (diagnostic.shift.dy * reference.scaleY).roundToInt().coerceIn(-30, 30)
            )
            Log.i(
                "AstroPhotoAlignment",
                "source=${source.metadataValue} starsDetected=${diagnostic.starsDetected} " +
                    "matches=${diagnostic.matches} shiftX=${shift.dx} shiftY=${shift.dy} " +
                    "confidence=${"%.3f".format(Locale.US, diagnostic.confidence)} " +
                    "method=${diagnostic.method} fallbackReason=${diagnostic.fallbackReason.orEmpty()}"
            )
            onAlignment(
                frameNumber,
                totalFrames,
                "Выравнивание кадра $frameNumber из $totalFrames: " +
                    "dx=${shift.dx}, dy=${shift.dy}, method=${diagnostic.method}"
            )
            shift
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onAlignment(
                frameNumber,
                totalFrames,
                "Не удалось выровнять кадр $frameNumber. " +
                    "Продолжаем без выравнивания."
            )
            AlignmentShift.Zero
        }
    }

    private fun createManualAlignmentSample(bitmap: Bitmap): ManualAlignmentReference {
        val maxDimension = 640f
        val scale = minOf(
            1f,
            maxDimension / maxOf(bitmap.width, bitmap.height).toFloat()
        )
        val width = maxOf(1, (bitmap.width * scale).roundToInt())
        val height = maxOf(1, (bitmap.height * scale).roundToInt())
        val thumbnail = if (
            width == bitmap.width && height == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, false)
        }

        try {
            val pixels = IntArray(width * height)
            thumbnail.getPixels(pixels, 0, width, 0, 0, width, height)
            return ManualAlignmentReference(
                image = ArgbPixelImage(width, height, pixels),
                scaleX = bitmap.width.toFloat() / width,
                scaleY = bitmap.height.toFloat() / height
            )
        } finally {
            if (thumbnail !== bitmap) thumbnail.recycle()
        }
    }

    private fun registerProfileFrame(
        reference: FrameAnalysis,
        candidate: FrameAnalysis,
        targetWidth: Int,
        targetHeight: Int,
        registrar: StarSimilarityRegistrar,
        forceTranslationOnly: Boolean
    ): RegistrationResult {
        if (!candidate.decodeValid) {
            return RegistrationResult.rejected(
                referenceStars = reference.reliableStarCount,
                detectedStars = 0,
                reason = "JPEG analysis failed"
            )
        }
        val registration = registrar.registerAutomatic(
            referenceStars = reference.stars,
            candidateStars = candidate.stars,
            imageWidth = reference.width,
            imageHeight = reference.height,
            forceTranslationOnly = forceTranslationOnly
        )
        val scaleX = targetWidth.toFloat() / reference.width.coerceAtLeast(1)
        val scaleY = targetHeight.toFloat() / reference.height.coerceAtLeast(1)
        return registration.copy(
            dx = registration.dx * scaleX,
            dy = registration.dy * scaleY,
            scale = 1f,
            scaleFixed = true,
            rawDx = registration.rawDx * scaleX,
            rawDy = registration.rawDy * scaleY
        )
    }

    private fun logProfileRegistration(fileName: String, registration: RegistrationResult) {
        Log.i(
            PROFILE_REGISTRATION_TAG,
            "frame=$fileName detectedStars=${registration.detectedStars} " +
                "matchedStars=${registration.matchedStars} inlierStars=${registration.inlierStars} " +
                "dx=${formatMetric(registration.dx)} dy=${formatMetric(registration.dy)} " +
                "rotation=${formatMetric(registration.rotationRadians)} " +
                "scale=${formatMetric(registration.scale)} " +
                "model=${registration.registrationModel} scaleFixed=${registration.scaleFixed} " +
                "rotationAllowed=${registration.rotationAllowed} " +
                "rotationRejectionReason=${registration.rotationRejectionReason.orEmpty()} " +
                "occupiedCells=${registration.occupiedDistributionCells} " +
                "horizontalSpan=${formatMetric(registration.horizontalDistributionSpan)} " +
                "verticalSpan=${formatMetric(registration.verticalDistributionSpan)} " +
                "distributionScore=${formatMetric(registration.spatialDistributionScore)} " +
                "residual=${formatMetric(registration.residualError)} " +
                "rawDx=${formatMetric(registration.rawDx)} rawDy=${formatMetric(registration.rawDy)} " +
                "rawRotation=${formatMetric(registration.rawRotationRadians)} " +
                "sequenceScore=${formatMetric(registration.transformSequenceScore)} " +
                "sequenceDeviation=${formatMetric(registration.transformSequenceDeviation)} " +
                "neighborDelta=${formatMetric(registration.neighborTransformDelta)} " +
                "retryUsed=${registration.transformRetryUsed} " +
                "confidence=${formatMetric(registration.confidence)} " +
                "accepted=${registration.isReliable} " +
                "rejectionReason=${registration.rejectionReason.orEmpty()}"
        )
    }

    private fun scaleV2Stars(
        stars: List<V2DetectedStar>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<V2DetectedStar> {
        val scaleX = targetWidth.toFloat() / sourceWidth.coerceAtLeast(1)
        val scaleY = targetHeight.toFloat() / sourceHeight.coerceAtLeast(1)
        val sizeScale = (scaleX + scaleY) / 2f
        return stars.map { star ->
            star.copy(
                x = star.x * scaleX,
                y = star.y * scaleY,
                width = star.width * sizeScale
            )
        }
    }

    private fun scaleSkyMask(mask: SkyMask, width: Int, height: Int): SkyMask =
        SkyMask(
            width,
            height,
            BooleanArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val sourceX = (x.toLong() * mask.width / width).toInt().coerceIn(0, mask.width - 1)
                val sourceY = (y.toLong() * mask.height / height).toInt().coerceIn(0, mask.height - 1)
                mask.contains(sourceX, sourceY)
            }
        )

    private fun formatMetric(value: Float): String =
        if (value.isFinite()) "%.4f".format(Locale.US, value) else "n/a"

    private fun bitmapToArgbImage(bitmap: Bitmap): ArgbPixelImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ArgbPixelImage(bitmap.width, bitmap.height, pixels)
    }

    private fun bitmapFromArgbImage(image: ArgbPixelImage): Bitmap =
        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(image.pixels, 0, image.width, 0, 0, image.width, image.height)
        }

    private fun analyzeProfileBitmap(
        bitmap: Bitmap,
        recipe: AstroProfileRecipe
    ): ProfileSanityMetrics = analyzeProfileImage(
        bitmapToArgbImage(bitmap),
        recipe.roi,
        recipe.sensitivity
    )

    private fun logProfileStage(
        profile: AstroProcessingProfile,
        source: ManualStackingSource,
        stage: String,
        metrics: ProfileSanityMetrics
    ) {
        Log.i(
            "AstroPhotoProfileStage",
            "profile=${profile.name} source=${source.metadataValue} stage=$stage " +
                "median=${metrics.medianLuminance} low=${metrics.lowPercentile} " +
                "high=${metrics.highPercentile} black=${metrics.blackPercent} " +
                "white=${metrics.whitePercent} stars=${metrics.stars} " +
                "starContrast=${metrics.medianStarContrast} " +
                "backgroundSpread=${metrics.backgroundSpread} " +
                "banding=${metrics.largeScaleBanding}"
        )
    }

    private suspend fun averageFrames(
        frames: List<SessionFrame>,
        targetWidth: Int,
        targetHeight: Int,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Bitmap {
        var average: Bitmap? = null
        var averageAccumulator: ArgbAverageAccumulator? = null
        try {
            frames.forEachIndexed { index, frame ->
                currentCoroutineContext().ensureActive()
                var prepared = decodeFrame(frame)
                    ?: error("Не удалось прочитать JPEG: ${frame.fileName}")
                try {
                    val validation = validateDarkFrames(
                        darkFrames = listOf(
                            decodedPixelFrameShape(prepared.width, prepared.height)
                        ),
                        lightFrames = listOf(
                            decodedPixelFrameShape(targetWidth, targetHeight)
                        )
                    )
                    if (validation is DarkValidationResult.Invalid) {
                        error("${validation.message}: ${frame.fileName}")
                    }
                    if (average == null) {
                        average = prepared.copy(Bitmap.Config.ARGB_8888, true)
                            ?: error("Не удалось подготовить JPEG")
                        averageAccumulator = ArgbAverageAccumulator(
                            pixelCount = targetWidth * targetHeight,
                            maximumFrameCount = frames.size
                        )
                    } else {
                        addToMasterDarkRunningAverage(
                            accumulator = checkNotNull(averageAccumulator),
                            average = checkNotNull(average),
                            next = prepared,
                            frameNumber = index + 1
                        )
                    }
                } finally {
                    prepared.takeUnless {
                        it === average || it.isRecycled
                    }?.recycle()
                }
                onProgress(index + 1, frames.size)
            }
            return checkNotNull(average)
        } catch (error: Throwable) {
            average?.takeUnless(Bitmap::isRecycled)?.recycle()
            throw error
        }
    }

    private suspend fun calibrateAndAverageLights(
        lightFrames: List<SessionFrame>,
        masterDark: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        shadowOffset: Int,
        alignFrames: Boolean,
        alignmentSafe: Boolean,
        source: ManualStackingSource,
        onProgress: suspend (
            message: String,
            current: Int,
            total: Int
        ) -> Unit
    ): Bitmap {
        val output = Bitmap.createBitmap(
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888
        )
        val averageAccumulator = ArgbAverageAccumulator(
            pixelCount = targetWidth * targetHeight,
            maximumFrameCount = lightFrames.size
        )
        val alignmentShifts = mutableListOf<AlignmentShift>()
        try {
            val alignmentReference = if (alignFrames) {
                try {
                    createAlignmentReference(
                        lightFrames.first(),
                        targetWidth,
                        targetHeight
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    onProgress(
                        "Не удалось подготовить выравнивание. " +
                            "Продолжаем без него.",
                        1,
                        lightFrames.size
                    )
                    null
                }
            } else {
                null
            }
            lightFrames.forEachIndexed { index, frame ->
                currentCoroutineContext().ensureActive()
                val light = decodePreparedFrame(
                    frame,
                    targetWidth,
                    targetHeight
                ) ?: error("Не удалось прочитать JPEG: ${frame.fileName}")
                try {
                    val shift = if (
                        alignmentReference != null && index > 0
                    ) {
                        findAlignmentOrZero(
                            reference = alignmentReference,
                            candidate = light,
                            frameNumber = index + 1,
                            totalFrames = lightFrames.size,
                            safeMode = alignmentSafe,
                            source = source
                        ) { current, total, message ->
                            onProgress(message, current, total)
                        }
                    } else {
                        AlignmentShift.Zero
                    }
                    alignmentShifts += shift
                    subtractDarkAndAverage(
                        accumulator = averageAccumulator,
                        average = output,
                        light = light,
                        masterDark = masterDark,
                        frameNumber = index + 1,
                        shadowOffset = shadowOffset,
                        dx = shift.dx,
                        dy = shift.dy
                    )
                } finally {
                    light.takeUnless(Bitmap::isRecycled)?.recycle()
                }
                onProgress(
                    "Обработка кадра ${index + 1} из ${lightFrames.size}",
                    index + 1,
                    lightFrames.size
                )
            }
            return if (alignFrames) {
                cropToCommonAlignedRegion(output, alignmentShifts)
            } else {
                output
            }
        } catch (error: Throwable) {
            output.takeUnless(Bitmap::isRecycled)?.recycle()
            throw error
        }
    }

    private fun decodePreparedFrame(
        frame: SessionFrame,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = decodeMedianFrame(frame, targetWidth, targetHeight)

    private suspend fun subtractDarkAndAverage(
        accumulator: ArgbAverageAccumulator,
        average: Bitmap,
        light: Bitmap,
        masterDark: Bitmap,
        frameNumber: Int,
        shadowOffset: Int,
        dx: Int,
        dy: Int
    ) {
        val width = average.width
        val rowCount = 32
        val averagePixels = IntArray(width * rowCount)
        val lightPixels = IntArray(width * rowCount)
        val darkPixels = IntArray(width * rowCount)
        val calibratedPixels = IntArray(width * rowCount)
        var top = 0

        while (top < average.height) {
            currentCoroutineContext().ensureActive()
            val rows = minOf(rowCount, average.height - top)
            val pixelCount = width * rows
            if (frameNumber > 1) {
                average.getPixels(
                    averagePixels,
                    0,
                    width,
                    0,
                    top,
                    width,
                    rows
                )
            }
            readShiftedPixels(
                bitmap = light,
                destination = lightPixels,
                top = top,
                rows = rows,
                dx = dx,
                dy = dy,
                fillColor = 0
            )
            readShiftedPixels(
                bitmap = masterDark,
                destination = darkPixels,
                top = top,
                rows = rows,
                dx = dx,
                dy = dy,
                fillColor = 0xFF000000.toInt()
            )
            subtractMasterDarkArgb(
                lightPixels = lightPixels,
                darkPixels = darkPixels,
                outputPixels = calibratedPixels,
                neutralOffset = shadowOffset,
                pixelCount = pixelCount
            )

            for (pixelIndex in 0 until pixelCount) {
                if (lightPixels[pixelIndex] ushr 24 == 0) {
                    calibratedPixels[pixelIndex] = 0xFF000000.toInt()
                }
            }
            if (frameNumber == 1) {
                calibratedPixels.copyInto(averagePixels, endIndex = pixelCount)
            } else {
                updateRunningAverageArgb(
                    accumulator = accumulator,
                    averagePixels = averagePixels,
                    nextPixels = calibratedPixels,
                    frameNumber = frameNumber,
                    pixelCount = pixelCount,
                    accumulatorOffset = top * width
                )
            }

            average.setPixels(averagePixels, 0, width, 0, top, width, rows)
            top += rows
        }
    }

    private fun applyAstroStretchInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val row = IntArray(width)
        val histogram = IntArray(256)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            row.forEach { color -> histogram[pixelLuminance(color)]++ }
        }
        val parameters = manualAstroStretchParameters(
            histogram,
            width.toLong() * bitmap.height.toLong()
        )
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            row.indices.forEach { x -> row[x] = stretchArgbColor(row[x], parameters) }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private suspend fun addToRunningAverage(
        accumulator: ArgbAverageAccumulator,
        average: Bitmap,
        next: Bitmap,
        frameNumber: Int,
        dx: Int = 0,
        dy: Int = 0
    ) {
        val width = average.width
        val rowCount = 32
        val averagePixels = IntArray(width * rowCount)
        val nextPixels = IntArray(width * rowCount)
        var top = 0

        while (top < average.height) {
            currentCoroutineContext().ensureActive()
            val rows = minOf(rowCount, average.height - top)
            val pixelCount = width * rows
            average.getPixels(averagePixels, 0, width, 0, top, width, rows)
            readShiftedPixels(
                bitmap = next,
                destination = nextPixels,
                top = top,
                rows = rows,
                dx = dx,
                dy = dy,
                fillColor = 0xFF000000.toInt()
            )

            updateRunningAverageArgb(
                accumulator = accumulator,
                averagePixels = averagePixels,
                nextPixels = nextPixels,
                frameNumber = frameNumber,
                pixelCount = pixelCount,
                accumulatorOffset = top * width
            )

            average.setPixels(averagePixels, 0, width, 0, top, width, rows)
            top += rows
        }
    }

    private suspend fun addToMasterDarkRunningAverage(
        accumulator: ArgbAverageAccumulator,
        average: Bitmap,
        next: Bitmap,
        frameNumber: Int
    ) {
        val width = average.width
        val rowCount = 32
        val averagePixels = IntArray(width * rowCount)
        val nextPixels = IntArray(width * rowCount)
        var top = 0

        while (top < average.height) {
            currentCoroutineContext().ensureActive()
            val rows = minOf(rowCount, average.height - top)
            val pixelCount = width * rows
            average.getPixels(averagePixels, 0, width, 0, top, width, rows)
            next.getPixels(nextPixels, 0, width, 0, top, width, rows)
            updateMasterDarkRunningAverageArgb(
                accumulator = accumulator,
                averagePixels = averagePixels,
                nextPixels = nextPixels,
                frameNumber = frameNumber,
                pixelCount = pixelCount,
                accumulatorOffset = top * width
            )
            average.setPixels(averagePixels, 0, width, 0, top, width, rows)
            top += rows
        }
    }

    private suspend fun addToManualRunningAverage(
        accumulator: ArgbAverageAccumulator,
        average: Bitmap,
        next: Bitmap,
        frameNumber: Int,
        dx: Int,
        dy: Int
    ) {
        val width = average.width
        val rowCount = 32
        val averagePixels = IntArray(width * rowCount)
        val nextPixels = IntArray(width * rowCount)
        var top = 0

        while (top < average.height) {
            currentCoroutineContext().ensureActive()
            val rows = minOf(rowCount, average.height - top)
            val pixelCount = width * rows
            average.getPixels(averagePixels, 0, width, 0, top, width, rows)
            readShiftedPixels(
                bitmap = next,
                destination = nextPixels,
                top = top,
                rows = rows,
                dx = dx,
                dy = dy,
                fillColor = 0xFF000000.toInt()
            )
            updateRunningAverageArgb(
                accumulator = accumulator,
                averagePixels = averagePixels,
                nextPixels = nextPixels,
                frameNumber = frameNumber,
                pixelCount = pixelCount,
                accumulatorOffset = top * width
            )
            average.setPixels(averagePixels, 0, width, 0, top, width, rows)
            top += rows
        }
    }

    private fun readShiftedPixels(
        bitmap: Bitmap,
        destination: IntArray,
        top: Int,
        rows: Int,
        dx: Int,
        dy: Int,
        fillColor: Int
    ) {
        val width = bitmap.width
        destination.fill(fillColor, 0, width * rows)

        val region = shiftedCopyRegion(width, bitmap.height, top, rows, dx, dy)
            ?: return

        bitmap.getPixels(
            destination,
            (region.destinationY - top) * width + region.destinationX,
            width,
            region.sourceX,
            region.sourceY,
            region.width,
            region.height
        )
    }

    internal suspend fun saveBitmap(
        session: SessionSummary,
        bitmap: Bitmap,
        fileName: String
    ): SavedProcessedImage {
        currentCoroutineContext().ensureActive()
        val jpegQuality = CameraSettingsStore(context).load().jpegQuality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val destination = processedImageDestination(session.folderName)
            val relativePath = destination.relativePath
            val finalFileName = findUniqueProcessedResultName(fileName) { candidate ->
                mediaStoreProcessedNameExists(relativePath, candidate)
            }
            val uri = resolver.insert(
                processedImagesCollection(destination.collection),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, destination.mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            ) ?: error("Не удалось сохранить результат")

            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                        error("Не удалось сохранить результат")
                    }
                } ?: error("Не удалось сохранить результат")
                currentCoroutineContext().ensureActive()
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                val savedInfo = resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0).orEmpty().ifBlank { finalFileName } to
                            cursor.getLong(1)
                    } else {
                        finalFileName to 0L
                    }
                } ?: (finalFileName to 0L)
                if (savedInfo.second <= 0L) {
                    resolver.delete(uri, null, null)
                    error("Файл результата не был сохранён")
                }
                currentCoroutineContext().ensureActive()
                val savedFileName = savedInfo.first
                Log.d(
                    "ProcessedResult",
                    "resultName=$savedFileName storedUri=$uri " +
                        "relativePath=$relativePath resolvedCollection=stored"
                )
                return retainedMediaStoreImage(
                    destination = destination,
                    actualFileName = savedFileName,
                    insertedUri = uri.toString()
                )
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val directory = File(
            pictures,
            "AstroPhoto/${session.folderName}/Processed"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Не удалось создать папку Processed")
        }
        val finalFileName = findUniqueProcessedResultName(fileName) { candidate ->
            File(directory, candidate).exists() ||
                File(directory, "$candidate.tmp").exists()
        }
        val file = File(directory, finalFileName)
        val tempFile = File(directory, "$finalFileName.tmp")
        var moved = false
        try {
            require(tempFile.createNewFile()) {
                "Не удалось создать временный файл результата"
            }
            FileOutputStream(tempFile, false).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                    error("Не удалось сохранить результат")
                }
            }
            currentCoroutineContext().ensureActive()
            require(tempFile.length() > 0L) { "Файл результата не был сохранён" }
            try {
                Files.move(tempFile.toPath(), file.toPath())
                moved = true
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Не удалось создать уникальный файл результата",
                    error
                )
            }
            currentCoroutineContext().ensureActive()
            require(file.length() > 0L) { "Файл результата не был сохранён" }
        } catch (error: Exception) {
            tempFile.delete()
            if (moved) file.delete()
            throw error
        }
        return SavedProcessedImage(
            fileName = finalFileName,
            displayPath = file.absolutePath,
            contentUri = null,
            filePath = file.absolutePath
        )
    }

    private fun deleteSavedJpeg(saved: SavedProcessedImage) {
        saved.contentUri?.let { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            return
        }
        saved.filePath?.let { path -> runCatching { File(path).delete() } }
    }

    private fun mediaStoreProcessedNameExists(
        relativePath: String,
        fileName: String
    ): Boolean {
        val resolver = context.contentResolver
        return resolver.query(
            processedImagesCollection(),
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath),
            null
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: error("Не удалось проверить имя файла результата")
    }

    private fun appendSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        alignmentEnabled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedFrames: $frameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("alignmentEnabled: $alignmentEnabled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendLine("processedAt: $processedAt")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendDarkStackSessionInfo(
        session: SessionSummary,
        resultFileName: String,
        masterDarkFileName: String?,
        lightFrameCount: Int,
        darkFrameCount: Int,
        shadowOffset: Int,
        alignmentEnabled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$resultFileName")
            appendLine("processedType: JPEG stacking + Dark Frames")
            appendLine("processedLightFrames: $lightFrameCount")
            appendLine("processedDarkFrames: $darkFrameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("shadowOffset: $shadowOffset")
            appendLine("darkSubtractionMode: Safe")
            appendLine("alignmentEnabled: $alignmentEnabled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            masterDarkFileName?.let {
                appendLine("masterDarkFile: Processed/$it")
            }
            appendLine("processedAt: $processedAt")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendMedianSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        alignmentEnabled: Boolean,
        downscaled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Median JPEG stacking")
            appendLine("medianFrames: $frameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("medianAlignmentEnabled: $alignmentEnabled")
            appendLine("medianDownscaled: $downscaled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendSigmaSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        sigma: Double,
        alignmentEnabled: Boolean,
        downscaled: Boolean,
        astroStretchApplied: Boolean,
        source: ManualStackingSource,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Sigma clipping JPEG stacking")
            appendLine("sigmaValue: $sigma")
            appendLine("sigmaFrames: $frameCount")
            appendLine("source=${source.metadataValue}")
            appendLine("sigmaAlignmentEnabled: $alignmentEnabled")
            appendLine("sigmaDownscaled: $downscaled")
            appendLine("astroStretchApplied: $astroStretchApplied")
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendProfileSessionInfo(
        session: SessionSummary,
        fileName: String,
        profile: AstroProcessingProfile,
        method: String,
        framesUsed: Int,
        framesRejected: Int,
        alignmentMode: String,
        alignmentApplied: Int,
        alignmentRejected: Int,
        roiMode: String,
        backgroundRemoval: String,
        stretchMode: String,
        starBoost: String,
        starsBefore: Int,
        starsAfter: Int,
        source: ManualStackingSource,
        sanity: String,
        fallback: String,
        fallbackReason: String,
        warnings: List<String>,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Astro profile JPEG")
            appendLine("profile: ${profile.title}")
            appendLine("source=${source.metadataValue}")
            appendLine("method: $method")
            appendLine("framesUsed: $framesUsed")
            appendLine("framesRejected: $framesRejected")
            appendLine("alignmentMode: $alignmentMode")
            appendLine("alignmentAppliedFrames: $alignmentApplied")
            appendLine("alignmentRejectedFrames: $alignmentRejected")
            appendLine("roiMode: $roiMode")
            appendLine("backgroundRemoval: $backgroundRemoval")
            appendLine("stretchMode: $stretchMode")
            appendLine("starBoost: $starBoost")
            appendLine("starsBefore: $starsBefore")
            appendLine("starsAfter: $starsAfter")
            appendLine("sanity: $sanity")
            appendLine("fallback: $fallback")
            appendLine("fallbackReason: $fallbackReason")
            warnings.forEachIndexed { index, warning ->
                appendLine("profileWarning${index + 1}: $warning")
            }
            appendLine("outputFile: Processed/$fileName")
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun appendMediaStoreSessionInfo(
        session: SessionSummary,
        block: String
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/AstroPhoto/${session.folderName}/"
        val existingUri = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf("session_info.txt", relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }

        val existingContent = existingUri?.let { uri ->
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.orEmpty()
        var inserted = false
        val uri = existingUri ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "session_info.txt")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        )?.also { inserted = true } ?: error("Не удалось обновить session_info.txt")

        try {
            val base = existingContent.ifBlank {
                "sessionName: ${session.sessionName}\n"
            }
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(base.trimEnd())
                writer.write(block)
            } ?: error("Не удалось обновить session_info.txt")
            if (inserted) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
        } catch (error: Exception) {
            if (inserted) resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun appendLegacySessionInfo(
        session: SessionSummary,
        block: String
    ) {
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val file = File(
            pictures,
            "AstroPhoto/${session.folderName}/session_info.txt"
        )
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText("sessionName: ${session.sessionName}\n")
        }
        file.appendText(block)
    }

    private fun decodeSampledFile(path: String, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxSize ||
            bounds.outHeight / sampleSize > maxSize
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun complexStackTargetPixels(
        sourcePixels: Long,
        frameCount: Int,
        maxPixels: Long
    ): Long {
        val seriesLimit = when {
            frameCount <= 6 -> maxPixels
            frameCount <= 15 -> MAX_COMPLEX_STACK_PIXELS_MEDIUM_SERIES
            else -> MAX_COMPLEX_STACK_PIXELS_MANY_FRAMES
        }
        val bytesPerPixel = frameCount.toLong() * ARGB_BYTES_PER_PIXEL +
            COMPLEX_STACK_OUTPUT_BYTES_PER_PIXEL
        val heapLimit = processingMemoryBudgetBytes() / bytesPerPixel.coerceAtLeast(1L)
        return minOf(sourcePixels, seriesLimit, heapLimit).coerceAtLeast(1L)
    }

    internal fun appendRawStackSessionInfo(
        session: SessionSummary,
        fileName: String,
        frameCount: Int,
        rejectedFrameCount: Int,
        alignedFrameCount: Int,
        downscaled: Boolean,
        processedAtMillis: Long
    ) {
        val processedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(processedAtMillis))
        val block = buildString {
            appendLine()
            appendLine("processedFile: Processed/$fileName")
            appendLine("processedType: Linear RAW16 stacking")
            appendLine("rawFrames: $frameCount")
            appendLine("rawRejectedFrames: $rejectedFrameCount")
            appendLine("rawSubpixelAlignedFrames: $alignedFrameCount")
            appendLine("rawDownscaled: $downscaled")
            appendLine("rawToneMap: percentile asinh sRGB")
            appendLine("processedAt: $processedAt")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendMediaStoreSessionInfo(session, block)
        } else {
            appendLegacySessionInfo(session, block)
        }
    }

    private fun averageStackTargetPixels(sourcePixels: Long, frameCount: Int): Long {
        val accumulatorBytes = if (frameCount <= MAX_PACKED_AVERAGE_FRAMES) {
            PACKED_AVERAGE_ACCUMULATOR_BYTES_PER_PIXEL
        } else {
            UNPACKED_AVERAGE_ACCUMULATOR_BYTES_PER_PIXEL
        }
        val bytesPerPixel = AVERAGE_BITMAP_BYTES_PER_PIXEL + accumulatorBytes
        val heapLimit = processingMemoryBudgetBytes() / bytesPerPixel
        return minOf(sourcePixels, MAX_AVERAGE_PIXELS, heapLimit).coerceAtLeast(1L)
    }

    private fun processingMemoryBudgetBytes(): Long =
        (Runtime.getRuntime().maxMemory() / 2L)
            .coerceIn(MIN_PROCESSING_MEMORY_BYTES, MAX_PROCESSING_MEMORY_BYTES)

    private fun cropToCommonAlignedRegion(
        bitmap: Bitmap,
        shifts: List<AlignmentShift>
    ): Bitmap {
        val region = commonAlignedRegion(bitmap.width, bitmap.height, shifts)
        if (
            region.left == 0 && region.top == 0 &&
            region.right == bitmap.width && region.bottom == bitmap.height
        ) {
            return bitmap
        }
        val cropped = Bitmap.createBitmap(
            bitmap,
            region.left,
            region.top,
            region.width,
            region.height
        )
        bitmap.recycle()
        return cropped
    }

    companion object {
        private const val MAX_AVERAGE_PIXELS = 24_000_000L
        private const val MAX_MEDIAN_FRAMES = 30
        private const val MAX_MEDIAN_PIXELS = 8_000_000L
        private const val MAX_SIGMA_FRAMES = 30
        private const val MAX_SIGMA_PIXELS = 8_000_000L
        private const val MAX_PROFILE_FRAMES = 30
        private const val PROFILE_ANALYSIS_MAX_DIMENSION = 960
        private const val PROFILE_REGISTRATION_TAG = "AstroPhotoJpegV2"
        private const val ADAPTIVE_PROCESSING_TAG = "AstroPhotoJpegStage4"
        private const val MIN_SKY_ALPHA_FOR_ADAPTIVE_STATISTICS = 0.98f
        private const val MIN_PROFILE_WORKING_MEMORY_BYTES = 64L * 1024L * 1024L
        private const val MAX_PROFILE_WORKING_MEMORY_BYTES = 256L * 1024L * 1024L
        private const val MIN_CACHE_FREE_SPACE_BYTES = 32L * 1024L * 1024L
        private const val FOREGROUND_SHARPNESS_TOLERANCE = 0.05f
        private const val MAX_COMPLEX_STACK_PIXELS_MANY_FRAMES = 2_500_000L
        private const val MAX_COMPLEX_STACK_PIXELS_MEDIUM_SERIES = 4_000_000L
        private const val ARGB_BYTES_PER_PIXEL = 4L
        private const val COMPLEX_STACK_OUTPUT_BYTES_PER_PIXEL = 8L
        private const val AVERAGE_BITMAP_BYTES_PER_PIXEL = 12L
        private const val PACKED_AVERAGE_ACCUMULATOR_BYTES_PER_PIXEL = 4L
        private const val UNPACKED_AVERAGE_ACCUMULATOR_BYTES_PER_PIXEL = 12L
        private const val MAX_PACKED_AVERAGE_FRAMES = 1024
        private const val MIN_PROCESSING_MEMORY_BYTES = 16L * 1024L * 1024L
        private const val MAX_PROCESSING_MEMORY_BYTES = 256L * 1024L * 1024L
        private val SUPPORTED_SIGMA_VALUES = setOf(1.5, 2.0, 2.5, 3.0)
    }
}

internal fun RegistrationResult.toReport(frameName: String): FrameRegistrationReport =
    FrameRegistrationReport(
        frameName = frameName,
        accepted = isReliable,
        rejectionReason = rejectionReason,
        detectedStars = detectedStars,
        matchedStars = matchedStars,
        inlierStars = inlierStars,
        dx = dx,
        dy = dy,
        rotationRadians = rotationRadians,
        scale = scale,
        residualError = residualError,
        confidence = confidence,
        registrationModel = registrationModel,
        scaleFixed = scaleFixed,
        rotationAllowed = rotationAllowed,
        rotationRejectionReason = rotationRejectionReason,
        occupiedDistributionCells = occupiedDistributionCells,
        horizontalDistributionSpan = horizontalDistributionSpan,
        verticalDistributionSpan = verticalDistributionSpan,
        spatialDistributionScore = spatialDistributionScore,
        rawDx = rawDx,
        rawDy = rawDy,
        rawRotationRadians = rawRotationRadians,
        transformSequenceScore = transformSequenceScore,
        transformSequenceDeviation = transformSequenceDeviation,
        neighborTransformDelta = neighborTransformDelta,
        transformRetryUsed = transformRetryUsed
    )

internal fun qualityFallbackWarning(
    selectedType: ResultCandidateType,
    reason: String
): String {
    val cause = when {
        "star_" in reason || "retention" in reason || "smear" in reason || "points" in reason ->
            "звёзды стали менее резкими или заметными"
        "coverage" in reason -> "покрытие неба оказалось неоднородным"
        "line" in reason || "fan" in reason || "streak" in reason ->
            "появились новые линейные артефакты"
        "transform_sequence" in reason || "registered_frames" in reason ->
            "движение кадров оказалось недостаточно надёжным"
        "noise" in reason || "mad" in reason -> "увеличился шум фона"
        "median" in reason || "gray" in reason -> "фон стал слишком ярким"
        "banding" in reason -> "усилился бандинг"
        "foreground" in reason || "wire" in reason || "edge" in reason ->
            "изменился защищённый передний план"
        "dimension" in reason || "aspect" in reason || "crop" in reason || "border" in reason ->
            "нарушились размеры или границы изображения"
        "confidence" in reason -> "статистика неба была недостаточно надёжной"
        else -> "результат не прошёл финальную проверку качества"
    }
    val saved = if (selectedType == ResultCandidateType.CLEAN_STACK) {
        "Сохранён чистый стек."
    } else {
        "Сохранён выбранный опорный кадр."
    }
    val action = if (selectedType == ResultCandidateType.REFERENCE) {
        "Стекинг отклонён: $cause."
    } else {
        "Preset-обработка отклонена: $cause."
    }
    return "$action $saved"
}

private fun resultCandidateTitle(type: String?): String = when (type) {
    ResultCandidateType.PROCESSED.name -> "обработанный"
    ResultCandidateType.CLEAN_STACK.name -> "чистый стек"
    ResultCandidateType.REFERENCE.name -> "опорный кадр"
    else -> "неизвестно"
}

private enum class JpegStackingMode(val title: String) {
    AVERAGE("Average"),
    AVERAGE_DARK("Average + Dark"),
    MEDIAN("Median"),
    SIGMA("Sigma clipping")
}

private enum class StackAlignmentMode(val title: String) {
    OFF("OFF"),
    SAFE("SAFE"),
    AGGRESSIVE("AGGRESSIVE")
}

private enum class StackProcessingWorkflow(val title: String) {
    QUICK("Быстро"),
    QUALITY("Качество"),
    MANUAL("Ручная")
}

enum class ProcessingUiMode(val title: String) {
    READY("Готовые режимы"),
    MANUAL("Ручная обработка")
}

@Composable
fun ProcessingModeSelector(
    selected: ProcessingUiMode,
    onSelected: (ProcessingUiMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    AstroSegmentedControl(
        options = ProcessingUiMode.entries,
        selected = selected,
        label = { it.title },
        onSelected = onSelected,
        enabled = enabled,
        modifier = modifier.testTag(AstroTestTags.ProcessingModeTabs)
    )
}

@Composable
fun ProcessingProfileAvailability(
    unavailableReason: String?,
    availableFrames: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = unavailableReason?.let { "Недоступно: $it" }
            ?: "Доступно кадров: $availableFrames",
        modifier = modifier.testTag(AstroTestTags.ProcessingProfileAvailability),
        color = if (unavailableReason == null) {
            AstroColors.Success
        } else {
            AstroColors.Warning
        },
        style = MaterialTheme.typography.bodySmall
    )
}

internal fun processingProfileUnavailableReason(
    profile: AstroProcessingProfile,
    availableFrames: Int,
    sourceError: String?,
    loading: Boolean,
    running: Boolean,
    operationsEnabled: Boolean
): String? = when {
    loading -> "кадры ещё загружаются"
    running -> "обработка уже выполняется"
    !operationsEnabled -> "сначала завершите другую операцию"
    sourceError != null -> sourceError
    availableFrames < profile.minimumFrames ->
        "нужно минимум ${profile.minimumFrames} кадров"
    else -> null
}

internal fun canStartProcessing(running: Boolean, jobActive: Boolean): Boolean =
    !running && !jobActive

private fun StackProcessingWorkflow.displayTitle(): String = when (this) {
    StackProcessingWorkflow.QUICK -> "\u0411\u044B\u0441\u0442\u0440\u043E"
    StackProcessingWorkflow.QUALITY -> "\u041A\u0430\u0447\u0435\u0441\u0442\u0432\u043E"
    StackProcessingWorkflow.MANUAL -> "\u0420\u0443\u0447\u043D\u0430\u044F"
}

private const val MAX_MEDIAN_FRAMES_UI = 30
private const val MAX_SIGMA_FRAMES_UI = 30
private const val MAX_PROFILE_FRAMES_UI = 30

@Composable
fun JpegStackingBlock(
    session: SessionSummary,
    refreshKey: Int,
    onStackCompleted: () -> Unit,
    onOpenHelp: (HelpTopic) -> Unit = {},
    onOpenResults: () -> Unit = {},
    operationsEnabled: Boolean = true,
    onOperationStateChanged: (Boolean) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val framesRepository = remember {
        SessionFramesRepository(context.applicationContext)
    }
    val cropsRepository = remember { CroppedFramesRepository(context.applicationContext) }
    val marksStore = remember { FrameMarksStore(context.applicationContext) }
    val stacker = remember { JpegStacker(context.applicationContext) }
    val rawStacker = remember { RawStacker(context.applicationContext) }
    val rawSidecarStore = remember { AstroRawSidecarStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()

    var frames by remember(session.folderName) {
        mutableStateOf<List<SessionFrame>>(emptyList())
    }
    var marks by remember(session.folderName) { mutableStateOf(FrameMarks()) }
    var cropManifest by remember(session.folderName) { mutableStateOf(CropManifest()) }
    var stackingSource by remember(session.folderName) {
        mutableStateOf(ManualStackingSource.ORIGINAL)
    }
    var loading by remember(session.folderName) { mutableStateOf(true) }
    var favoritesOnly by remember(session.folderName) { mutableStateOf(false) }
    var stackingMode by remember(session.folderName) {
        mutableStateOf(JpegStackingMode.AVERAGE)
    }
    var alignmentMode by remember(session.folderName) {
        mutableStateOf(StackAlignmentMode.SAFE)
    }
    var workflow by remember(session.folderName) {
        mutableStateOf(StackProcessingWorkflow.QUICK)
    }
    var autoStretchAfterStacking by remember(session.folderName) {
        mutableStateOf(false)
    }
    var sigmaValue by remember(session.folderName) { mutableStateOf(2.0) }
    var showSigmaConfirmation by remember(session.folderName) {
        mutableStateOf(false)
    }
    var helpTopic by remember(session.folderName) {
        mutableStateOf<HelpTopic?>(null)
    }
    var shadowOffset by remember(session.folderName) { mutableIntStateOf(16) }
    var stacking by remember(session.folderName) { mutableStateOf(false) }
    var processingJob by remember(session.folderName) { mutableStateOf<Job?>(null) }
    var progressCurrent by remember(session.folderName) { mutableIntStateOf(0) }
    var progressTotal by remember(session.folderName) { mutableIntStateOf(0) }
    var status by remember(session.folderName) { mutableStateOf<String?>(null) }
    var rawStatus by remember(session.folderName) { mutableStateOf<String?>(null) }
    var rawSidecarKeys by remember(session.folderName) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var result by remember(session.folderName) {
        mutableStateOf<JpegStackResult?>(null)
    }
    var profileResults by remember(session.folderName) {
        mutableStateOf<List<JpegStackResult>>(emptyList())
    }
    var manualProcessingExpanded by remember(session.folderName) {
        mutableStateOf(true)
    }
    var processingUiMode by remember(session.folderName) {
        mutableStateOf(ProcessingUiMode.READY)
    }
    var preview by remember(session.folderName) { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember(session.folderName) { mutableStateOf(false) }

    LaunchedEffect(session.folderName, refreshKey) {
        if (!stacking) {
            loading = true
            frames = framesRepository.loadFrames(session)
            rawSidecarKeys = withContext(Dispatchers.IO) {
                frames.asSequence()
                    .filter { it.category == SessionFrameCategory.LIGHTS_RAW }
                    .filter { frame ->
                        rawSidecarStore.openForFrame(session, frame)?.use { true } ?: false
                    }
                    .mapTo(mutableSetOf()) { it.key }
            }
            cropManifest = cropsRepository.loadManifest(session)
            marks = marksStore.loadOrCreate(session)
            loading = false
        }
    }
    LaunchedEffect(stacking) {
        onOperationStateChanged(stacking)
    }
    DisposableEffect(Unit) {
        onDispose { onOperationStateChanged(false) }
    }

    val jpegFrames = frames.filter {
        it.category == SessionFrameCategory.LIGHTS_JPEG
    }
    val rawFrames = frames.filter {
        it.category == SessionFrameCategory.LIGHTS_RAW
    }
    val excludedLightKeys = marks.bad + marks.autoBad
    val badFrames = jpegFrames.filter { it.key in excludedLightKeys }
    val usableRawFrames = rawFrames
        .filter { it.key in rawSidecarKeys }
        .filterNot { it.key in excludedLightKeys }
    val missingRawSidecars = rawFrames.count { it.key !in rawSidecarKeys }
    val eligibleFrames = selectEligibleLightFrames(
        frames = frames,
        marks = marks,
        favoritesOnly = false
    )
    val cropRecords = cropsRepository.records(cropManifest, frames)
    val sourceSelection = resolveStackingSource(
        originals = jpegFrames,
        crops = cropRecords,
        marks = marks,
        favoritesOnly = favoritesOnly,
        source = stackingSource
    )
    val selectedFrames = (sourceSelection as? StackingSourceSelection.Valid)?.frames.orEmpty()
    val selectedCropEntries = (sourceSelection as? StackingSourceSelection.Valid)?.entries.orEmpty()
    val sourceError = (sourceSelection as? StackingSourceSelection.Invalid)?.message
    val darkFrames = frames.filter {
        it.category == SessionFrameCategory.DARKS_JPEG
    }
    val badDarkFrames = darkFrames.filter { it.key in marks.bad }
    val usableDarkFrames = darkFrames.filterNot { it.key in marks.bad }
    val useDarkFrames = stackingMode == JpegStackingMode.AVERAGE_DARK
    val medianMode = stackingMode == JpegStackingMode.MEDIAN
    val sigmaMode = stackingMode == JpegStackingMode.SIGMA
    val alignFrames = alignmentMode != StackAlignmentMode.OFF
    val alignmentSafe = alignmentMode == StackAlignmentMode.SAFE
    val darkCropResult = if (useDarkFrames && stackingSource == ManualStackingSource.CROPPED) {
        runCatching { commonDarkCrop(selectedCropEntries) }
    } else {
        null
    }

    fun applyWorkflow(selectedWorkflow: StackProcessingWorkflow) {
        workflow = selectedWorkflow
        when (selectedWorkflow) {
            StackProcessingWorkflow.QUICK -> {
                stackingMode = JpegStackingMode.AVERAGE
                alignmentMode = StackAlignmentMode.SAFE
                autoStretchAfterStacking = false
                shadowOffset = 16
            }
            StackProcessingWorkflow.QUALITY -> {
                stackingMode = when {
                    usableDarkFrames.isNotEmpty() -> JpegStackingMode.AVERAGE_DARK
                    selectedFrames.size >= 6 -> JpegStackingMode.SIGMA
                    else -> JpegStackingMode.AVERAGE
                }
                alignmentMode = StackAlignmentMode.SAFE
                autoStretchAfterStacking = true
                shadowOffset = 16
            }
            StackProcessingWorkflow.MANUAL -> Unit
        }
    }

    fun startStacking() {
        if (!canStartProcessing(stacking, processingJob?.isActive == true)) return
        stacking = true
        progressCurrent = 0
        progressTotal = when {
            useDarkFrames -> usableDarkFrames.size
            medianMode -> minOf(selectedFrames.size, MAX_MEDIAN_FRAMES_UI)
            sigmaMode -> minOf(selectedFrames.size, MAX_SIGMA_FRAMES_UI)
            else -> selectedFrames.size
        }
        result = null
        status = "Подготовка кадров..."
        processingJob = coroutineScope.launch {
            try {
                val stackResult = when {
                    sigmaMode -> stacker.sigmaStack(
                        session = session,
                        frames = selectedFrames.take(MAX_SIGMA_FRAMES_UI),
                        sigma = sigmaValue,
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource
                    ) { message, current, total ->
                        progressCurrent = current
                        progressTotal = total
                        status = message
                    }
                    medianMode -> stacker.medianStack(
                        session = session,
                        frames = selectedFrames.take(MAX_MEDIAN_FRAMES_UI),
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource
                    ) { message, current, total ->
                        progressCurrent = current
                        progressTotal = total
                        status = message
                    }
                    useDarkFrames -> stacker.stackWithDarkFrames(
                        session = session,
                        lightFrames = selectedFrames,
                        darkFrames = usableDarkFrames,
                        shadowOffset = shadowOffset,
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource,
                        darkCrop = darkCropResult?.getOrNull()
                    ) { message, current, total ->
                        progressCurrent = current
                        progressTotal = total
                        status = message
                    }
                    else -> stacker.stack(
                        session = session,
                        frames = selectedFrames,
                        alignFrames = alignFrames,
                        alignmentSafe = alignmentSafe,
                        autoStretch = autoStretchAfterStacking,
                        source = stackingSource,
                        onProgress = { current, total ->
                            progressCurrent = current
                            progressTotal = total
                            val message = "Обработка кадра $current из $total"
                            status = message
                        },
                        onAlignment = { current, total, message ->
                            progressCurrent = current
                            progressTotal = total
                            status = message
                        }
                    )
                }
                stackResult.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                stackResult.fold(
                    onSuccess = {
                        result = it
                        status = buildString {
                            append("Готово: ${it.fileName}")
                            if (it.additionalFiles.isNotEmpty()) {
                                append("\nadditional: ${it.additionalFiles.joinToString()}")
                            }
                            if (!it.sessionInfoUpdated) {
                                append(
                                    "\nФайл сохранён, но не удалось " +
                                        "обновить session_info.txt"
                                )
                            }
                            if (it.downscaled) {
                                append(
                                    if (sigmaMode) {
                                        "\nSigma результат сохранён в уменьшенном " +
                                            "размере из-за ограничений памяти."
                                    } else {
                                        "\nMedian сохранён в уменьшенном " +
                                            "размере из-за ограничений памяти."
                                    }
                                )
                            }
                        }
                        onStackCompleted()
                    },
                    onFailure = {
                        Log.e("AstroPhotoProcessing", "Manual processing failed", it)
                        val message = it.message ?: "Не удалось сохранить результат"
                        status = message
                    }
                )
            } catch (error: CancellationException) {
                status = "Обработка остановлена"
                throw error
            } catch (error: Throwable) {
                Log.e("AstroPhotoProcessing", "Manual processing crashed", error)
                status = error.message ?: "Не удалось выполнить обработку"
            } finally {
                stacking = false
                processingJob = null
            }
        }
    }

    fun startRawStacking() {
        if (!canStartProcessing(stacking, processingJob?.isActive == true)) return
        stacking = true
        progressCurrent = 0
        progressTotal = usableRawFrames.size
        result = null
        status = "Подготовка линейных RAW-кадров…"
        rawStatus = status
        processingJob = coroutineScope.launch {
            try {
                val rawResult = rawStacker.stack(
                    session = session,
                    frames = usableRawFrames
                ) { message, current, total ->
                    status = message
                    rawStatus = message
                    progressCurrent = current
                    progressTotal = total
                }
                rawResult.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                rawResult.fold(
                    onSuccess = { created ->
                        result = created
                        status = buildString {
                            append("Готово: ${created.fileName}")
                            created.warnings.forEach { append("\n$it") }
                        }
                        rawStatus = status
                        onStackCompleted()
                    },
                    onFailure = { error ->
                        Log.e("AstroPhotoProcessing", "RAW processing failed", error)
                        status = error.message ?: "Не удалось обработать RAW"
                        rawStatus = status
                    }
                )
            } catch (error: CancellationException) {
                status = "Обработка RAW остановлена"
                rawStatus = status
                throw error
            } catch (error: Throwable) {
                Log.e("AstroPhotoProcessing", "RAW processing crashed", error)
                status = error.message ?: "Не удалось обработать RAW"
                rawStatus = status
            } finally {
                stacking = false
                processingJob = null
            }
        }
    }

    fun startProfile(profile: AstroProcessingProfile) {
        if (!canStartProcessing(stacking, processingJob?.isActive == true)) return
        val validSource = sourceSelection as? StackingSourceSelection.Valid
        if (validSource == null) {
            status = sourceError ?: "Источник кадров недоступен"
            return
        }
        if (validSource.frames.size < profile.minimumFrames) {
            status = "Для ${profile.title} нужно минимум ${profile.minimumFrames} кадров"
            return
        }
        stacking = true
        progressCurrent = 0
        progressTotal = validSource.frames.size
        status = "Подготовка профиля ${profile.title}..."
        processingJob = coroutineScope.launch {
            try {
                val profileResult = stacker.profileStack(
                    session = session,
                    frames = validSource.frames,
                    profile = profile,
                    source = stackingSource,
                    framesRejected = badFrames.size + validSource.missingCropCount
                ) { message, current, total ->
                    status = message
                    progressCurrent = current
                    progressTotal = total
                }
                profileResult.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                profileResult.fold(
                    onSuccess = { created ->
                        profileResults = profileResults + created
                        status = buildString {
                            append("Готово: ${created.fileName}")
                            append("\nПринято кадров: ${created.frameCount} / ${validSource.frames.size}")
                            append("\nВыбран результат: ${resultCandidateTitle(created.selectedResultType)}")
                            append("\nЗвёзды: ${created.starsBefore ?: 0} → ${created.starCount ?: 0}")
                            append("\nFallback: ${if (created.fallbackUsed) "да" else "нет"}")
                            created.warnings.forEach { append("\n$it") }
                        }
                        onStackCompleted()
                    },
                    onFailure = { error ->
                        status = error.message ?: "Профильная обработка не удалась"
                    }
                )
            } catch (error: CancellationException) {
                status = "Обработка остановлена"
                throw error
            } finally {
                stacking = false
                processingJob = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            if (rawFrames.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "RAW 16-bit — линейный стек",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Баланс чёрного и белого, проявка Bayer, " +
                                "субпиксельное выравнивание и мягкая растяжка.",
                            color = AstroColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Готово к RAW16-обработке: ${usableRawFrames.size} " +
                                "из ${rawFrames.size}"
                        )
                        if (missingRawSidecars > 0) {
                            Text(
                                text = "Без служебного .araw: $missingRawSidecars. " +
                                    "Эти DNG нужно переснять после обновления приложения.",
                                color = AstroColors.Warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (usableRawFrames.size < 2) {
                            Text(
                                text = "Нужно минимум два RAW-кадра без отметки «брак».",
                                color = AstroColors.Warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { startRawStacking() },
                            enabled = operationsEnabled && !loading && !stacking &&
                                usableRawFrames.size >= 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                        ) {
                            Text("Сложить RAW в линейном пространстве")
                        }
                        rawStatus?.let { message ->
                            Text(
                                text = message,
                                color = if (
                                    message.startsWith("Готово") ||
                                    message.startsWith("Чтение") ||
                                    message.startsWith("Линейная") ||
                                    message.startsWith("Растяжка") ||
                                    message.startsWith("Подготовка")
                                ) {
                                    AstroColors.TextSecondary
                                } else {
                                    AstroColors.Error
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JPEG стеккинг",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { helpTopic = HelpTopic.STACKING }) {
                    Text("?")
                }
            }
            Text(
                text = "Складывает JPEG light frames без кадров, " +
                    "помеченных как брак.",
                color = AstroColors.TextSecondary
            )
            Text("Найдено Lights/JPEG: ${jpegFrames.size}")
            Text("Light frames используется: ${selectedFrames.size}")
            Text("Исключено light-брака: ${badFrames.size}")
            Text("Доступно Cropped JPEG: ${cropRecords.size}")
            Text("Источник", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ManualStackingSource.entries.forEach { option ->
                    FilterChip(
                        selected = stackingSource == option,
                        onClick = { stackingSource = option },
                        enabled = !loading && !stacking,
                        label = {
                            Text(
                                if (option == ManualStackingSource.ORIGINAL) {
                                    "Original JPEG"
                                } else {
                                    "Cropped JPEG"
                                }
                            )
                        }
                    )
                }
            }
            sourceError?.let { Text(it, color = AstroColors.Error) }

            ProcessingModeSelector(
                selected = processingUiMode,
                onSelected = { processingUiMode = it },
                enabled = !stacking,
                modifier = Modifier.fillMaxWidth()
            )

            if (stacking) {
                AstroProgressPanel(
                    title = "Идёт обработка",
                    step = status ?: "Подготовка кадров…",
                    progress = if (progressTotal > 0) {
                        progressCurrent.toFloat() / progressTotal.coerceAtLeast(1)
                    } else {
                        null
                    },
                    onCancel = {
                        status = "Остановка обработки…"
                        processingJob?.cancel()
                    },
                    modifier = Modifier.testTag(AstroTestTags.ProcessingProgress)
                )
            }
            if (processingUiMode == ProcessingUiMode.READY) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AstroTestTags.ProcessingReadyModes),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                Text(
                    text = "Готовые режимы",
                    style = MaterialTheme.typography.titleMedium
                )
                AstroProcessingProfile.entries.filterNot { it == AstroProcessingProfile.NORMAL }
                    .forEach { profile ->
                    val unavailableReason = processingProfileUnavailableReason(
                        profile = profile,
                        availableFrames = selectedFrames.size,
                        sourceError = sourceError,
                        loading = loading,
                        running = stacking,
                        operationsEnabled = operationsEnabled
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(profile.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                profile.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            ProcessingProfileAvailability(
                                unavailableReason = unavailableReason,
                                availableFrames = selectedFrames.size
                            )
                            AstroExpandableSection(title = "Подробнее") {
                                Text(
                                    "Минимум кадров: ${profile.minimumFrames}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Источник: ${stackingSource.metadataValue}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = { startProfile(profile) },
                                enabled = unavailableReason == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Запустить ${profile.title}")
                            }
                        }
                    }
                }
                }
            }
            if (profileResults.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = AstroColors.SuccessSurface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Созданы результаты",
                            fontWeight = FontWeight.SemiBold
                        )
                        profileResults.forEach { created ->
                            Text(
                                text = "• ${created.fileName}",
                                color = AstroColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            created.additionalFiles.forEach { fileName ->
                                Text(
                                    text = "  + $fileName",
                                    color = AstroColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Text(
                            text = "Откройте раздел «Результаты обработки», чтобы сравнить варианты.",
                            color = AstroColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = onOpenResults,
                            enabled = !stacking,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Открыть результаты")
                        }
                    }
                }
            }

            if (processingUiMode == ProcessingUiMode.MANUAL) {
            Card(
                modifier = Modifier.testTag(AstroTestTags.ProcessingManual),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ручная обработка",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Average, Average + Dark, Median, Sigma и детальные настройки.",
                                color = AstroColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(
                            onClick = {
                                manualProcessingExpanded = !manualProcessingExpanded
                            },
                            enabled = !stacking
                        ) {
                            Text(if (manualProcessingExpanded) "Скрыть" else "Открыть")
                        }
                    }
                    if (manualProcessingExpanded) {
            Text(
                text = "Метод stacking",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StackProcessingWorkflow.entries.forEach { option ->
                    FilterChip(
                        selected = workflow == option,
                        onClick = { applyWorkflow(option) },
                        enabled = !loading && !stacking,
                        label = { Text(option.displayTitle()) }
                    )
                }
            }
            Text(
                text = "\u0411\u044B\u0441\u0442\u0440\u043E: Average + SAFE alignment. \u041A\u0430\u0447\u0435\u0441\u0442\u0432\u043E: \u043C\u044F\u0433\u043A\u0438\u0439 Astro Stretch \u0438 Safe Dark, \u0435\u0441\u043B\u0438 \u0435\u0441\u0442\u044C dark frames.",
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JpegStackingMode.entries.chunked(2).forEach { modes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = stackingMode == mode,
                                onClick = {
                                    workflow = StackProcessingWorkflow.MANUAL
                                    stackingMode = mode
                                },
                                enabled = !loading && !stacking,
                                label = { Text(mode.title) }
                            )
                        }
                    }
                }
            }

            Text(
                text = "Выравнивание",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StackAlignmentMode.entries.forEach { mode ->
                    FilterChip(
                        selected = alignmentMode == mode,
                        onClick = {
                            workflow = StackProcessingWorkflow.MANUAL
                            alignmentMode = mode
                        },
                        enabled = !loading && !stacking,
                        label = { Text(mode.title) }
                    )
                }
            }
            Text(
                text = "SAFE применяет сдвиг только при уверенном совпадении; AGGRESSIVE оставлен для ручных экспериментов.",
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Дополнительная обработка",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Только избранные")
                Switch(
                    checked = favoritesOnly,
                    onCheckedChange = { favoritesOnly = it },
                    enabled = !loading && !stacking
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        medianMode -> "Выравнивать перед median"
                        sigmaMode -> "Выравнивать перед sigma"
                        else -> "Выравнивать кадры"
                    }
                )
                Switch(
                    checked = alignFrames,
                    onCheckedChange = {
                        workflow = StackProcessingWorkflow.MANUAL
                        alignmentMode = if (it) {
                            StackAlignmentMode.SAFE
                        } else {
                            StackAlignmentMode.OFF
                        }
                    },
                    enabled = !loading && !stacking
                )
            }
            Text(
                text = "Помогает, если телефон немного сдвинулся между кадрами.",
                color = AstroColors.TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Astro Stretch after stacking")
                Switch(
                    checked = autoStretchAfterStacking,
                    onCheckedChange = {
                        workflow = StackProcessingWorkflow.MANUAL
                        autoStretchAfterStacking = it
                    },
                    enabled = !loading && !stacking
                )
            }
            Text(
                text = "\u041C\u044F\u0433\u043A\u043E \u0432\u044B\u0442\u044F\u0433\u0438\u0432\u0430\u0435\u0442 \u0441\u043B\u0430\u0431\u044B\u0435 \u0437\u0432\u0451\u0437\u0434\u044B. \u0415\u0441\u043B\u0438 \u043D\u0443\u0436\u0435\u043D \u00AB\u0447\u0438\u0441\u0442\u044B\u0439\u00BB stack, \u043E\u0441\u0442\u0430\u0432\u044C\u0442\u0435 \u0432\u044B\u043A\u043B.",
                color = AstroColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            if (useDarkFrames) {
                Text(
                    text = "Dark Frames",
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Найдено Darks/JPEG: ${darkFrames.size}")
                Text("Dark frames используется: ${usableDarkFrames.size}")
                Text("Исключено dark-брака: ${badDarkFrames.size}")
                Text(
                    text = "Компенсация тени",
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 8, 16, 32).forEach { offset ->
                        FilterChip(
                            selected = shadowOffset == offset,
                            onClick = { shadowOffset = offset },
                            enabled = !stacking,
                            label = { Text(offset.toString()) }
                        )
                    }
                }

                if (darkFrames.isEmpty()) {
                    Text(
                        text = "Dark frames не найдены. Можно выполнить обычный " +
                            "stacking без вычитания шума.",
                        color = AstroColors.Warning
                    )
                    Button(
                        onClick = {
                            stackingMode = JpegStackingMode.AVERAGE
                            status = "Выбран обычный JPEG stacking"
                        },
                        enabled = !stacking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text("Продолжить без Dark Frames")
                    }
                } else if (usableDarkFrames.isEmpty()) {
                    Text(
                        text = "Все dark frames помечены как брак",
                        color = AstroColors.Error
                    )
                }
            }
            if (medianMode && selectedFrames.size < 3) {
                Text(
                    text = "Для median желательно хотя бы 3 кадра.",
                    color = AstroColors.Warning
                )
            }
            if (medianMode && selectedFrames.size > MAX_MEDIAN_FRAMES_UI) {
                Text(
                    text = "Median может быть медленным. Будут использованы " +
                        "первые $MAX_MEDIAN_FRAMES_UI кадров.",
                    color = AstroColors.Warning
                )
            }
            if (sigmaMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sigma",
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { helpTopic = HelpTopic.STACKING_METHODS }
                    ) {
                        Text("?")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1.5, 2.0, 2.5, 3.0).forEach { value ->
                        FilterChip(
                            selected = sigmaValue == value,
                            onClick = { sigmaValue = value },
                            enabled = !stacking,
                            label = { Text(value.toString()) }
                        )
                    }
                }
                Text(
                    text = "Меньше sigma — агрессивнее удаление выбросов, " +
                        "больше — мягче.",
                    color = AstroColors.TextSecondary
                )
                if (selectedFrames.size < 4) {
                    Text(
                        text = "Для sigma clipping желательно минимум 4 кадра. " +
                            "Лучше используйте Average или Median.",
                        color = AstroColors.Warning
                    )
                }
                if (selectedFrames.size > MAX_SIGMA_FRAMES_UI) {
                    Text(
                        text = "Sigma clipping может быть медленным. Будут " +
                            "использованы первые $MAX_SIGMA_FRAMES_UI кадров.",
                        color = AstroColors.Warning
                    )
                }
            }
            if (!operationsEnabled) {
                Text(
                    text = "Сначала завершите текущую операцию",
                    color = AstroColors.Warning
                )
            }

            Button(
                onClick = {
                    when {
                        sourceError != null -> {
                            status = sourceError
                        }
                        darkCropResult?.isFailure == true -> {
                            status = darkCropResult.exceptionOrNull()?.message
                        }
                        jpegFrames.isNotEmpty() && eligibleFrames.isEmpty() -> {
                            status = "Все кадры помечены как брак"
                        }
                        jpegFrames.size < 2 -> {
                            status = "Недостаточно JPEG кадров для стеккинга"
                        }
                        selectedFrames.size < 2 -> {
                            status = if (favoritesOnly) {
                                "Недостаточно избранных JPEG кадров для стеккинга"
                            } else {
                                "Недостаточно JPEG кадров для стеккинга"
                            }
                        }
                        useDarkFrames && darkFrames.isEmpty() -> {
                            status = "Dark frames не найдены"
                        }
                        useDarkFrames && usableDarkFrames.isEmpty() -> {
                            status = "Все dark frames помечены как брак"
                        }
                        sigmaMode && selectedFrames.size < 4 -> {
                            showSigmaConfirmation = true
                        }
                        else -> {
                            startStacking()
                        }
                    }
                },
                enabled = !loading && !stacking && operationsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    when {
                        stacking -> "Стеккинг..."
                        sigmaMode && alignFrames -> "Sigma JPEG + Alignment"
                        sigmaMode -> "Sigma JPEG"
                        medianMode && alignFrames -> "Median JPEG + Alignment"
                        medianMode -> "Median JPEG"
                        useDarkFrames -> "Сложить JPEG с Dark Frames"
                        else -> "Сложить JPEG"
                    }
                )
            }
                    }
                }
            }
            }

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            status?.takeUnless { stacking }?.let {
                Text(
                    text = it,
                    color = if (
                        it.startsWith("Готово") ||
                        it.startsWith("Обработка") ||
                        it.startsWith("Подготовка") ||
                        it.startsWith("Создание") ||
                        it.startsWith("Сохранение") ||
                        it.startsWith("Выравнивание")
                    ) {
                        AstroColors.Success
                    } else {
                        AstroColors.Error
                    }
                )
            }

            result?.let { stackResult ->
                AstroExpandableSection(title = "Технические сведения") {
                    Text(
                        text = stackResult.displayPath,
                        color = AstroColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    stackResult.masterDarkDisplayPath?.let { masterPath ->
                        Text(
                            text = "Master Dark: $masterPath",
                            color = AstroColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(
                    onClick = {
                        previewLoading = true
                        coroutineScope.launch {
                            preview = stacker.loadResultPreview(stackResult, 1600)
                            previewLoading = false
                            if (preview == null) {
                                status = "Не удалось открыть результат"
                            }
                        }
                    },
                    enabled = !previewLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text(
                        if (previewLoading) {
                            "Открытие..."
                        } else {
                            "Открыть результат"
                        }
                    )
                }
            }
    }

    if (showSigmaConfirmation) {
        AlertDialog(
            onDismissRequest = { showSigmaConfirmation = false },
            title = { Text("Мало кадров для Sigma clipping") },
            text = {
                Text(
                    "Для sigma clipping желательно минимум 4 кадра. " +
                        "С текущим количеством результат может быть нестабильным. " +
                        "Всё равно продолжить?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSigmaConfirmation = false
                        startStacking()
                    }
                ) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSigmaConfirmation = false }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    helpTopic?.let { topic ->
        HelpTopicDialog(
            topic = topic,
            onOpenHelp = onOpenHelp,
            onDismiss = { helpTopic = null }
        )
    }

    preview?.let { bitmap ->
        DisposableEffect(bitmap) {
            onDispose {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        Dialog(
            onDismissRequest = { preview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Результат JPEG стеккинга",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = result?.fileName.orEmpty(),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(
                    onClick = { preview = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text("Назад")
                }
            }
        }
    }
}
